package de.tobisk.inkdav

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tobisk.inkdav.data.*
import de.tobisk.inkdav.dav.normalizeDavBaseUrl
import de.tobisk.inkdav.files.LocalFileBrowser
import de.tobisk.inkdav.files.LocalFileEntry
import de.tobisk.inkdav.files.LocalFolderLocation
import de.tobisk.inkdav.settings.InkDavSettings
import de.tobisk.inkdav.sync.SyncWorker
import de.tobisk.inkdav.tasks.RecurringTaskProjector
import de.tobisk.inkdav.update.AppUpdater
import de.tobisk.inkdav.update.UpdateState
import de.tobisk.inkdav.widgets.WidgetUpdater
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class Destination(val label: String, val mark: String) {
    CALENDAR("Calendar", "□"),
    TASKS("Tasks", "✓"),
    FILES("Files", "▤"),
    SYNC("Sync", "↻"),
    SETTINGS("Settings", "⚙")
}
enum class CalendarMode { YEAR, MONTH, WEEK, DAY }

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as InkDavApplication).container
    private val dao = container.database.dao()
    private val appUpdater = AppUpdater(application)
    private val localFileBrowser = LocalFileBrowser(application)

    val destination = MutableStateFlow(Destination.CALENDAR)
    val selectedDate = MutableStateFlow(LocalDate.now())
    val calendarMode = MutableStateFlow(CalendarMode.MONTH)
    val selectedFileCollection = MutableStateFlow<String?>(null)
    val selectedFileParent = MutableStateFlow<String?>(null)
    val selectedMirror = MutableStateFlow<String?>(null)
    val selectedMirrorParent = MutableStateFlow("")
    val editingEvent = MutableStateFlow<CalendarEventEntity?>(null)
    val editingOccurrence = MutableStateFlow<CalendarOccurrenceEntity?>(null)
    val editingTask = MutableStateFlow<DavTaskEntity?>(null)
    val localFiles = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    val localFolderStack = MutableStateFlow<List<LocalFolderLocation>>(emptyList())
    val localFilesError = MutableStateFlow<String?>(null)
    val updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    val accounts = dao.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val collections = dao.observeCollections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tasks = dao.observeTasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scheduledTasks = dao.observeTasks().map { source ->
        source.mapNotNull { RecurringTaskProjector.next(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingCount = dao.observePendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val conflictingEvents = dao.observeConflictingEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val conflictingTasks = dao.observeConflictingTasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val mirrors = dao.observeMirrors().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = container.preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InkDavSettings())

    init {
        viewModelScope.launch {
            container.preferences.settings.map { value -> value.localFilesRootUri }.distinctUntilChanged().collectLatest { root ->
                if (root == null) {
                    localFolderStack.value = emptyList()
                    localFiles.value = emptyList()
                } else if (localFolderStack.value.isEmpty()) {
                    loadLocalRoot(root)
                }
            }
        }
    }

    val events = combine(selectedDate, calendarMode) { date, mode -> visibleInterval(date, mode) }
        .flatMapLatest { (start, end) -> dao.observeOccurrences(start, end) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val files = combine(selectedFileCollection, selectedFileParent) { collection, parent -> collection to parent }
        .flatMapLatest { (collection, parent) ->
            if (collection == null) flowOf(emptyList()) else dao.observeFiles(collection, parent)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mirrorFiles = combine(selectedMirror, selectedMirrorParent) { mirror, parent -> mirror to parent }
        .flatMapLatest { (mirror, parent) ->
            if (mirror == null) flowOf(emptyList()) else dao.observeMirrorChildren(mirror, parent)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun sync() = SyncWorker.enqueue(getApplication())

    fun currentAppVersion(): String = appUpdater.currentVersion()

    fun checkForUpdates() {
        if (updateState.value is UpdateState.Checking || updateState.value is UpdateState.Downloading) return
        viewModelScope.launch {
            updateState.value = UpdateState.Checking
            updateState.value = runCatching {
                val release = appUpdater.latestRelease()
                if (!appUpdater.isNewer(release.version)) {
                    UpdateState.UpToDate(appUpdater.currentVersion())
                } else {
                    updateState.value = UpdateState.Downloading(release.version, 0, -1)
                    val apk = appUpdater.download(release) { received, total ->
                        updateState.value = UpdateState.Downloading(release.version, received, total)
                    }
                    UpdateState.Ready(release.version, apk.absolutePath)
                }
            }.getOrElse { error ->
                UpdateState.Error(error.message ?: "The update check failed.")
            }
        }
    }

    fun markUpdateInstallPrompted(apkPath: String) {
        val ready = updateState.value as? UpdateState.Ready ?: return
        if (ready.apkPath == apkPath) updateState.value = ready.copy(installPrompted = true)
    }

    fun resolveEventConflict(event: CalendarEventEntity, keepBoth: Boolean) = viewModelScope.launch {
        if (keepBoth) {
            container.offlineRepository.keepBothEventConflict(event)
        } else {
            container.offlineRepository.resolveEventConflictWithServer(event)
        }
        sync()
    }

    fun resolveTaskConflict(task: DavTaskEntity, keepBoth: Boolean) = viewModelScope.launch {
        if (keepBoth) {
            container.offlineRepository.keepBothTaskConflict(task)
        } else {
            container.offlineRepository.resolveTaskConflictWithServer(task)
        }
        sync()
    }

    fun addAccount(name: String, baseUrl: String, username: String, password: CharArray, nasDrive: Boolean) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val kind = if (nasDrive) AccountKind.NASDRIVE else AccountKind.DAV
            val normalized = normalizeDavBaseUrl(baseUrl, kind)
            dao.upsertAccount(
                DavAccountEntity(id, name.trim(), normalized, username.trim(), kind)
            )
            container.credentials.put(id, password)
            WidgetUpdater.updateAll(getApplication())
            sync()
        }
    }

    fun updateCredentials(account: DavAccountEntity, password: CharArray) {
        viewModelScope.launch {
            container.credentials.put(account.id, password)
            dao.upsertAccount(account.copy(lastSyncError = null))
            sync()
        }
    }

    fun removeAccount(account: DavAccountEntity) = viewModelScope.launch {
        val mirrorUris = dao.collections(account.id).flatMap { dao.mirrorsForCollection(it.id) }.map { it.localTreeUri }.distinct()
        container.credentials.remove(account.id)
        dao.removeAccountData(account)
        mirrorUris.forEach { value ->
            runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    Uri.parse(value),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        WidgetUpdater.updateAll(getApplication())
    }

    fun createEvent(
        collectionId: String,
        title: String,
        start: Long,
        end: Long,
        allDay: Boolean,
        recurrenceRule: String?
    ) {
        viewModelScope.launch {
            container.offlineRepository.createEvent(collectionId, title, start, end, allDay, recurrenceRule)
            WidgetUpdater.updateAll(getApplication())
            sync()
        }
    }

    fun createTask(collectionId: String, title: String, due: LocalDate?, priority: Int = 0, notes: String = "") {
        viewModelScope.launch {
            val dueMillis = due?.atTime(9, 0)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            container.offlineRepository.createTask(collectionId, title, dueMillis, priority, notes)
            WidgetUpdater.updateAll(getApplication())
            sync()
        }
    }

    fun toggleTask(task: DavTaskEntity) {
        viewModelScope.launch {
            container.offlineRepository.toggleTask(task)
            WidgetUpdater.updateAll(getApplication())
            sync()
        }
    }

    fun openOccurrence(occurrence: CalendarOccurrenceEntity) = viewModelScope.launch {
        editingOccurrence.value = occurrence
        editingEvent.value = dao.masterEvent(occurrence.collectionId, occurrence.uid) ?: dao.event(occurrence.sourceEventId)
    }
    fun openTask(task: DavTaskEntity) {
        editingTask.value = task
    }
    fun updateEvent(
        event: CalendarEventEntity,
        title: String,
        description: String,
        location: String,
        start: Long,
        end: Long,
        allDay: Boolean,
        recurrenceRule: String?,
        entireSeries: Boolean
    ) = viewModelScope.launch {
        val occurrence = editingOccurrence.value
        if (!entireSeries && occurrence != null && event.recurrenceRule != null) {
            container.offlineRepository.updateEventOccurrence(event, occurrence, title, description, location, start, end, allDay)
        } else {
            container.offlineRepository.updateEvent(event, title, description, location, start, end, allDay, recurrenceRule)
        }
        editingEvent.value = null
        editingOccurrence.value = null
        WidgetUpdater.updateAll(getApplication())
        sync()
    }
    fun deleteEvent(event: CalendarEventEntity, entireSeries: Boolean) = viewModelScope.launch {
        val occurrence = editingOccurrence.value
        if (!entireSeries && occurrence != null && event.recurrenceRule != null) {
            container.offlineRepository.deleteEventOccurrence(event, occurrence)
        } else {
            container.offlineRepository.deleteEvent(event)
        }
        editingEvent.value =
            null
        editingOccurrence.value = null
        WidgetUpdater.updateAll(getApplication())
        sync()
    }
    fun updateTask(task: DavTaskEntity, title: String, notes: String, dueMillis: Long?, priority: Int) = viewModelScope.launch {
        container.offlineRepository.updateTask(task, title, notes, dueMillis, priority)
        editingTask.value = null
        WidgetUpdater.updateAll(getApplication())
        sync()
    }
    fun deleteTask(task: DavTaskEntity) = viewModelScope.launch {
        container.offlineRepository.deleteTask(task)
        editingTask.value = null
        WidgetUpdater.updateAll(getApplication())
        sync()
    }

    fun selectFileCollection(id: String, rootHref: String) {
        selectedMirror.value = null
        selectedFileCollection.value = id
        selectedFileParent.value = rootHref
    }
    fun selectMirror(id: String) {
        selectedFileCollection.value = null
        selectedFileParent.value = null
        selectedMirror.value = id
        selectedMirrorParent.value = ""
    }
    fun selectLocalFiles() {
        selectedFileCollection.value = null
        selectedFileParent.value = null
        selectedMirror.value = null
    }
    fun openMirrorFolder(path: String) {
        selectedMirrorParent.value = path
    }
    fun upMirrorFolder() {
        selectedMirrorParent.value = selectedMirrorParent.value.substringBeforeLast('/', "")
    }
    fun openLocalFile(uri: String, mimeType: String?) {
        val application = getApplication<Application>()
        val parsed = Uri.parse(uri)
        val viewUri = if (parsed.scheme == "file") {
            FileProvider.getUriForFile(application, "${application.packageName}.updates", File(requireNotNull(parsed.path)))
        } else {
            parsed
        }
        val intent = Intent(Intent.ACTION_VIEW, viewUri).apply {
            setDataAndType(viewUri, mimeType ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { application.startActivity(intent) }
    }

    @Suppress("DEPRECATION")
    fun useDeviceStorageRoot() = viewModelScope.launch {
        val uri = Uri.fromFile(Environment.getExternalStorageDirectory()).toString()
        container.preferences.setLocalFilesRoot(uri)
        loadLocalRoot(uri)
    }

    fun setLocalFilesRoot(uri: Uri) = viewModelScope.launch {
        val resolver = getApplication<Application>().contentResolver
        val oldRoot = settings.value.localFilesRootUri
        if (uri.scheme == "content") resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        container.preferences.setLocalFilesRoot(uri.toString())
        if (oldRoot != null && oldRoot != uri.toString() && Uri.parse(oldRoot).scheme == "content") {
            runCatching {
                resolver.releasePersistableUriPermission(Uri.parse(oldRoot), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        loadLocalRoot(uri.toString())
    }

    fun clearLocalFilesRoot() = viewModelScope.launch {
        settings.value.localFilesRootUri?.takeIf { Uri.parse(it).scheme == "content" }?.let { value ->
            runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    Uri.parse(value),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        container.preferences.setLocalFilesRoot(null)
        localFolderStack.value = emptyList()
        localFiles.value = emptyList()
        localFilesError.value = null
    }

    fun openLocalFolder(entry: LocalFileEntry) = viewModelScope.launch {
        val root = settings.value.localFilesRootUri ?: return@launch
        runCatching { localFileBrowser.folder(root, entry.relativePath, entry.name) }
            .onSuccess { (location, children) ->
                localFolderStack.value += location
                localFiles.value = children
                localFilesError.value = null
            }.onFailure { localFilesError.value = it.message ?: "The folder could not be opened." }
    }

    fun upLocalFolder() = viewModelScope.launch {
        val parentStack = localFolderStack.value.dropLast(1)
        val parent = parentStack.lastOrNull() ?: return@launch
        val root = settings.value.localFilesRootUri ?: return@launch
        runCatching {
            if (parent.relativePath.isBlank()) localFileBrowser.root(root) else localFileBrowser.folder(root, parent.relativePath, parent.name)
        }.onSuccess { (_, children) ->
            localFolderStack.value = parentStack
            localFiles.value = children
            localFilesError.value = null
        }.onFailure { localFilesError.value = it.message ?: "The parent folder could not be opened." }
    }

    private suspend fun loadLocalRoot(uri: String) {
        runCatching { localFileBrowser.root(uri) }
            .onSuccess { (location, children) ->
                localFolderStack.value = listOf(location)
                localFiles.value = children
                localFilesError.value = null
            }.onFailure {
                localFolderStack.value = emptyList()
                localFiles.value = emptyList()
                localFilesError.value = it.message ?: "The local file permission is unavailable."
            }
    }

    fun openFolder(href: String) {
        selectedFileParent.value = href
    }
    fun openFile(file: FileNodeEntity) {
        val uri = DocumentsContract.buildDocumentUri("de.tobisk.inkdav.documents", "file:${file.id}")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mimeType ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { getApplication<Application>().startActivity(intent) }
    }
    fun toggleOffline(file: FileNodeEntity) = viewModelScope.launch {
        container.offlineRepository.toggleOffline(file)
        sync()
    }
    fun addMirror(uri: Uri) = viewModelScope.launch {
        val collectionId = selectedFileCollection.value ?: return@launch
        val href = selectedFileParent.value ?: return@launch
        if (dao.mirrorForRemoteRoot(collectionId, href) != null || dao.mirrorForLocalTree(uri.toString()) != null) return@launch
        getApplication<Application>().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        dao.upsertMirror(
            MirrorBindingEntity(
                UUID.randomUUID().toString(),
                collectionId,
                href,
                uri.toString(),
                uri.lastPathSegment ?: "Local mirror"
            )
        )
        sync()
    }
    fun setMirrorEnabled(mirror: MirrorBindingEntity, enabled: Boolean) = viewModelScope.launch {
        dao.upsertMirror(mirror.copy(enabled = enabled))
        if (enabled) sync()
    }
    fun removeMirror(mirror: MirrorBindingEntity) = viewModelScope.launch {
        dao.removeMirror(mirror)
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                Uri.parse(mirror.localTreeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
    fun setCalendarWindow(pastDays: Int, futureMonths: Int) = viewModelScope.launch { container.preferences.setCalendarWindow(pastDays, futureMonths) }
    fun setEink(bold: Boolean, pages: Boolean) = viewModelScope.launch { container.preferences.setEink(bold, pages) }
    fun setCalendarVisible(collectionId: String, visible: Boolean) = viewModelScope.launch { container.preferences.setCalendarVisible(collectionId, visible) }

    private fun visibleInterval(date: LocalDate, mode: CalendarMode): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = when (mode) {
            CalendarMode.YEAR -> date.withDayOfYear(1)
            CalendarMode.MONTH -> date.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            CalendarMode.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            CalendarMode.DAY -> date
        }
        val end = when (mode) {
            CalendarMode.YEAR -> start.plusYears(1)
            CalendarMode.MONTH -> start.plusDays(42)
            CalendarMode.WEEK -> start.plusDays(14)
            CalendarMode.DAY -> start.plusDays(1)
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli() to end.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

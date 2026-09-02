package de.tobisk.inkdav

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tobisk.inkdav.data.*
import de.tobisk.inkdav.settings.InkDavSettings
import de.tobisk.inkdav.sync.SyncWorker
import de.tobisk.inkdav.widgets.WidgetUpdater
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
enum class TaskMode { LISTS, SCHEDULE }

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as InkDavApplication).container
    private val dao = container.database.dao()

    val destination = MutableStateFlow(Destination.CALENDAR)
    val selectedDate = MutableStateFlow(LocalDate.now())
    val calendarMode = MutableStateFlow(CalendarMode.MONTH)
    val taskMode = MutableStateFlow(TaskMode.SCHEDULE)
    val selectedFileCollection = MutableStateFlow<String?>(null)
    val selectedFileParent = MutableStateFlow<String?>(null)
    val editingEvent = MutableStateFlow<CalendarEventEntity?>(null)
    val editingTask = MutableStateFlow<DavTaskEntity?>(null)

    val accounts = dao.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val collections = dao.observeCollections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tasks = dao.observeTasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingCount = dao.observePendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val mirrors = dao.observeMirrors().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = container.preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InkDavSettings())

    val events = combine(selectedDate, calendarMode) { date, mode -> visibleInterval(date, mode) }
        .flatMapLatest { (start, end) -> dao.observeOccurrences(start, end) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val files = combine(selectedFileCollection, selectedFileParent) { collection, parent -> collection to parent }
        .flatMapLatest { (collection, parent) ->
            if (collection == null) flowOf(emptyList()) else dao.observeFiles(collection, parent)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun sync() = SyncWorker.enqueue(getApplication())

    fun addAccount(name: String, baseUrl: String, username: String, password: CharArray, nasDrive: Boolean) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val normalized = baseUrl.trim().let { if (it.endsWith('/')) it else "$it/" }
            dao.upsertAccount(
                DavAccountEntity(id, name.trim(), normalized, username.trim(), if (nasDrive) AccountKind.NASDRIVE else AccountKind.DAV)
            )
            container.credentials.put(id, password)
            WidgetUpdater.updateAll(getApplication())
            sync()
        }
    }

    fun createEvent(collectionId: String, title: String, date: LocalDate, hour: Int, allDay: Boolean) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val start = date.atTime(if (allDay) LocalTime.MIDNIGHT else LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()
            val end = start + if (allDay) 86_400_000 else 3_600_000
            container.offlineRepository.createEvent(collectionId, title, start, end, allDay)
            WidgetUpdater.updateAll(getApplication())
            sync()
        }
    }

    fun createTask(collectionId: String, title: String, due: LocalDate?) {
        viewModelScope.launch {
            val dueMillis = due?.atTime(9, 0)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            container.offlineRepository.createTask(collectionId, title, dueMillis)
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
        editingEvent.value =
            dao.event(occurrence.sourceEventId)
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
        allDay: Boolean
    ) = viewModelScope.launch {
        container.offlineRepository.updateEvent(event, title, description, location, start, end, allDay)
        editingEvent.value = null
        WidgetUpdater.updateAll(getApplication())
        sync()
    }
    fun deleteEvent(event: CalendarEventEntity) = viewModelScope.launch {
        container.offlineRepository.deleteEvent(event)
        editingEvent.value =
            null
        WidgetUpdater.updateAll(getApplication())
        sync()
    }
    fun updateTask(task: DavTaskEntity, title: String, notes: String, dueMillis: Long?) = viewModelScope.launch {
        container.offlineRepository.updateTask(task, title, notes, dueMillis)
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
        selectedFileCollection.value = id
        selectedFileParent.value = rootHref
    }

    fun openFolder(href: String) {
        selectedFileParent.value = href
    }
    fun toggleOffline(file: FileNodeEntity) = viewModelScope.launch {
        container.offlineRepository.toggleOffline(file)
        sync()
    }
    fun addMirror(uri: Uri) = viewModelScope.launch {
        val collectionId = selectedFileCollection.value ?: return@launch
        val href = selectedFileParent.value ?: return@launch
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
            CalendarMode.WEEK -> start.plusDays(7)
            CalendarMode.DAY -> start.plusDays(1)
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli() to end.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

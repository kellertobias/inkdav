package de.tobisk.inkdav.sync

import de.tobisk.inkdav.data.*
import de.tobisk.inkdav.dav.DavClient
import de.tobisk.inkdav.dav.DavHttpException
import de.tobisk.inkdav.dav.IcalendarCodec
import de.tobisk.inkdav.dav.RecurrenceProjector
import de.tobisk.inkdav.files.MirrorSyncEngine
import de.tobisk.inkdav.security.CredentialStore
import de.tobisk.inkdav.settings.UserPreferences
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.flow.first

class SyncEngine(
    private val dao: InkDavDao,
    private val credentials: CredentialStore,
    private val dav: DavClient,
    private val preferences: UserPreferences,
    private val offlineDirectory: File,
    private val mirrorSyncEngine: MirrorSyncEngine
) {
    private data class DiscoveredCollection(
        val local: DavCollectionEntity,
        val advertisedSyncToken: String?
    )

    suspend fun synchronizeAll(includeFiles: Boolean = true): Boolean = dao.enabledAccounts()
        .filter { includeFiles || it.kind != AccountKind.NASDRIVE }
        .map { account ->
            runCatching { synchronize(account, includeFiles) }.isSuccess
        }.all { it }

    suspend fun synchronize(account: DavAccountEntity, includeFiles: Boolean = true) {
        val password = credentials.get(account.id) ?: error("Credentials unavailable")
        try {
            drainOutbox(account, password)
            val discovered = dav.discoverCollections(account, password)
            val existingCollections = dao.collections(account.id).associateBy { "${it.href}|${it.kind}" }
            val discoveredCollections = discovered.flatMap { remote ->
                buildList {
                    if (remote.isCalendar &&
                        remote.supportsEvents
                    ) {
                        add(
                            discoveredCollection(
                                account,
                                remote.href,
                                remote.displayName,
                                CollectionKind.CALENDAR,
                                remote.syncToken,
                                remote.ctag,
                                existingCollections
                            )
                        )
                    }
                    if (remote.isCalendar &&
                        remote.supportsTasks
                    ) {
                        add(
                            discoveredCollection(
                                account,
                                remote.href,
                                remote.displayName,
                                CollectionKind.TASK_LIST,
                                remote.syncToken,
                                remote.ctag,
                                existingCollections
                            )
                        )
                    }
                    if (account.kind == AccountKind.NASDRIVE &&
                        remote.isCollection
                    ) {
                        add(
                            discoveredCollection(
                                account,
                                remote.href,
                                remote.displayName,
                                CollectionKind.FILE_ROOT,
                                remote.syncToken,
                                remote.ctag,
                                existingCollections
                            )
                        )
                    }
                }
            }
            dao.upsertCollections(discoveredCollections.map { it.local })

            val settings = preferences.settings.first()
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val from = now.minusDays(settings.calendarPastDays.toLong()).toInstant()
            val until = now.plusMonths(settings.calendarFutureMonths.toLong()).toInstant()
            discoveredCollections.forEach { discoveredCollection ->
                val collection = discoveredCollection.local
                when (collection.kind) {
                    CollectionKind.CALENDAR -> pullCalendar(
                        account,
                        password,
                        collection,
                        discoveredCollection.advertisedSyncToken,
                        "VEVENT",
                        from,
                        until
                    )
                    CollectionKind.TASK_LIST -> pullCalendar(
                        account,
                        password,
                        collection,
                        discoveredCollection.advertisedSyncToken,
                        "VTODO",
                        from,
                        until
                    )
                    CollectionKind.FILE_ROOT -> {
                        if (includeFiles) {
                            pullFileIndex(account, password, collection)
                            dao.enabledMirrors().filter {
                                it.collectionId == collection.id
                            }.forEach { mirrorSyncEngine.synchronize(account, password, collection, it) }
                        }
                    }
                    CollectionKind.SHARE -> Unit
                }
            }
            dao.upsertAccount(account.copy(lastSyncAt = System.currentTimeMillis(), lastSyncError = null))
        } catch (error: Exception) {
            dao.upsertAccount(account.copy(lastSyncError = error.message ?: error::class.simpleName))
            throw error
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun drainOutbox(account: DavAccountEntity, password: CharArray) {
        dao.pendingMutations().filter { it.accountId == account.id }.forEach { pending ->
            if (pending.lastError?.startsWith(CONFLICT_PREFIX) == true) return@forEach
            try {
                when (pending.mutationKind) {
                    MutationKind.CREATE, MutationKind.UPDATE -> {
                        val etag = dav.put(
                            account,
                            password,
                            pending.targetHref,
                            pending.payload.orEmpty().encodeToByteArray(),
                            "text/calendar; charset=utf-8",
                            pending.baseEtag,
                            createOnly = pending.mutationKind == MutationKind.CREATE
                        )
                        when (pending.objectKind) {
                            ObjectKind.EVENT -> dao.event(pending.objectId)?.let {
                                dao.upsertEvent(it.copy(remoteHref = pending.targetHref, etag = etag, status = SyncStatus.CLEAN))
                            }
                            ObjectKind.TASK -> dao.task(pending.objectId)?.let {
                                dao.upsertTask(it.copy(remoteHref = pending.targetHref, etag = etag, status = SyncStatus.CLEAN))
                            }
                            ObjectKind.FILE -> Unit
                        }
                    }
                    MutationKind.DELETE -> {
                        dav.delete(account, password, pending.targetHref, pending.baseEtag)
                        removeDeletedLocalObject(pending)
                    }
                    MutationKind.MKCOL -> dav.makeCollection(account, password, pending.targetHref)
                    MutationKind.MOVE, MutationKind.UPLOAD -> error("File mutation is handled by the transfer worker")
                }
                dao.removeMutation(pending)
            } catch (error: DavHttpException) {
                val conflict = error.code == 409 || error.code == 412
                if (conflict) markConflict(pending)
                dao.updateMutation(
                    pending.copy(
                        attemptCount = pending.attemptCount + 1,
                        lastError = if (conflict) "$CONFLICT_PREFIX${error.message}" else error.message
                    )
                )
                if (error.code == 401 || error.code == 403) throw error
            } catch (error: Exception) {
                dao.updateMutation(pending.copy(attemptCount = pending.attemptCount + 1, lastError = error.message))
                throw error
            }
        }
    }

    private suspend fun removeDeletedLocalObject(pending: PendingMutationEntity) {
        when (pending.objectKind) {
            ObjectKind.EVENT -> dao.event(pending.objectId)?.let { event ->
                dao.deleteOccurrencesBySource(event.id)
                dao.deleteEvent(event)
            }
            ObjectKind.TASK -> dao.task(pending.objectId)?.let { dao.deleteTask(it) }
            ObjectKind.FILE -> Unit
        }
    }

    private suspend fun markConflict(pending: PendingMutationEntity) {
        when (pending.objectKind) {
            ObjectKind.EVENT -> dao.event(pending.objectId)?.let { dao.upsertEvent(it.copy(status = SyncStatus.CONFLICT)) }
            ObjectKind.TASK -> dao.task(pending.objectId)?.let { dao.upsertTask(it.copy(status = SyncStatus.CONFLICT)) }
            ObjectKind.FILE -> dao.file(pending.objectId)?.let { dao.updateFile(it.copy(status = SyncStatus.CONFLICT)) }
        }
    }

    private suspend fun pullCalendar(
        account: DavAccountEntity,
        password: CharArray,
        collection: DavCollectionEntity,
        advertisedSyncToken: String?,
        component: String,
        from: Instant,
        until: Instant
    ) {
        val storedToken = collection.syncToken
        if (storedToken == null) {
            val snapshot = dav.calendarQuery(account, password, collection.href, component, from, until)
            applyCalendarResources(collection, component, from, until, snapshot)
            if (component == "VTODO") reconcileCompleteTaskSnapshot(collection, snapshot)
            advertisedSyncToken?.let { dao.upsertCollection(collection.copy(syncToken = it)) }
            return
        }

        val delta = try {
            dav.syncCollection(account, password, collection.href, storedToken)
        } catch (error: DavHttpException) {
            if (error.code !in setOf(403, 409, 410)) throw error
            // An expired or rejected token is not evidence of deletion. Rebuild the bounded cache,
            // then establish the token advertised during discovery as a new baseline.
            val snapshot = dav.calendarQuery(account, password, collection.href, component, from, until)
            applyCalendarResources(collection, component, from, until, snapshot)
            if (component == "VTODO") reconcileCompleteTaskSnapshot(collection, snapshot)
            advertisedSyncToken?.let { dao.upsertCollection(collection.copy(syncToken = it)) }
            return
        }

        val changedHrefs = delta.resources.filterNot { it.deleted }.map { it.href }.distinct()
        val changedResources = changedHrefs.chunked(MULTIGET_BATCH_SIZE).flatMap { hrefs ->
            dav.calendarMultiget(account, password, collection.href, hrefs)
        }
        applyCalendarResources(collection, component, from, until, changedResources)
        changedResources.filter { it.deleted }.forEach { remote -> applyRemoteTombstone(collection, remote.href) }
        delta.resources.filter { it.deleted }.forEach { remote -> applyRemoteTombstone(collection, remote.href) }
        dao.upsertCollection(collection.copy(syncToken = delta.nextSyncToken, ctag = collection.ctag))
    }

    private suspend fun reconcileCompleteTaskSnapshot(
        collection: DavCollectionEntity,
        resources: List<de.tobisk.inkdav.dav.DavResource>
    ) {
        val remoteHrefs = resources.filterNot { it.deleted }.mapTo(mutableSetOf()) { it.href }
        dao.tasksInCollection(collection.id).filter { it.remoteHref !in remoteHrefs }.forEach { task ->
            if (task.status == SyncStatus.CLEAN && !task.locallyDeleted) {
                dao.deleteTask(task)
            } else {
                dao.upsertTask(task.copy(status = SyncStatus.CONFLICT))
            }
        }
    }

    private suspend fun applyCalendarResources(
        collection: DavCollectionEntity,
        component: String,
        from: Instant,
        until: Instant,
        resources: List<de.tobisk.inkdav.dav.DavResource>
    ) {
        resources.forEach { remote ->
            val raw = remote.calendarData ?: return@forEach
            val parsed = IcalendarCodec.parse(collection.id, remote.href, remote.etag, raw)
            if (component == "VEVENT") {
                val preserveLocalProjection = parsed.events.any { incoming ->
                    dao.event(incoming.id)?.status?.let { it != SyncStatus.CLEAN } ==
                        true
                }
                val accepted = parsed.events.mapNotNull { incoming ->
                    val local = dao.event(incoming.id)
                    when {
                        local == null || local.status == SyncStatus.CLEAN -> incoming
                        local.etag != null && local.etag != incoming.etag -> local.copy(status = SyncStatus.CONFLICT)
                        else -> local
                    }
                }
                dao.upsertEvents(accepted)
                if (!preserveLocalProjection) {
                    val projection = RecurrenceProjector.project(
                        collection.id,
                        remote.href,
                        raw,
                        from.toEpochMilli(),
                        until.toEpochMilli(),
                        ZoneId.systemDefault()
                    )
                    dao.replaceOccurrencesForHref(collection.id, remote.href, projection)
                }
            } else {
                dao.upsertTasks(
                    parsed.tasks.mapNotNull { incoming ->
                        val local = dao.task(incoming.id)
                        when {
                            local == null || local.status == SyncStatus.CLEAN -> incoming
                            local.etag != null && local.etag != incoming.etag -> local.copy(status = SyncStatus.CONFLICT)
                            else -> local
                        }
                    }
                )
            }
        }
    }

    private suspend fun applyRemoteTombstone(collection: DavCollectionEntity, href: String) {
        dao.eventsByHref(collection.id, href).forEach { event ->
            if (event.status == SyncStatus.CLEAN && !event.locallyDeleted) {
                dao.deleteOccurrencesBySource(event.id)
                dao.deleteEvent(event)
            } else {
                dao.upsertEvent(event.copy(status = SyncStatus.CONFLICT))
            }
        }
        dao.tasksByHref(collection.id, href).forEach { task ->
            if (task.status == SyncStatus.CLEAN && !task.locallyDeleted) {
                dao.deleteTask(task)
            } else {
                dao.upsertTask(task.copy(status = SyncStatus.CONFLICT))
            }
        }
    }

    private suspend fun pullFileIndex(account: DavAccountEntity, password: CharArray, collection: DavCollectionEntity) {
        indexFolder(account, password, collection, collection.href, inheritedPinned = false, budget = intArrayOf(10_000))
    }

    private suspend fun indexFolder(
        account: DavAccountEntity,
        password: CharArray,
        collection: DavCollectionEntity,
        parentHref: String,
        inheritedPinned: Boolean,
        budget: IntArray
    ) {
        if (budget[0] <= 0) error("Pinned folder exceeds the 10,000 item safety limit")
        val remote = dav.list(account, password, parentHref)
        val indexed = remote.map { resource ->
            budget[0]--
            val id = stableId(collection.id, resource.href)
            val existing = dao.fileByHref(collection.id, resource.href)
            FileNodeEntity(
                id = id, collectionId = collection.id, parentHref = parentHref, href = resource.href,
                displayName = resource.displayName.ifBlank { resource.href.trimEnd('/').substringAfterLast('/') },
                isDirectory = resource.isCollection, mimeType = resource.contentType, sizeBytes = resource.size,
                modifiedAt = resource.modifiedAt, etag = resource.etag,
                offlinePolicy = if (inheritedPinned) OfflinePolicy.PINNED else existing?.offlinePolicy ?: OfflinePolicy.ONLINE_ONLY,
                localUri = existing?.localUri, localContentHash = existing?.localContentHash,
                lastSyncedEtag = existing?.lastSyncedEtag, status = existing?.status ?: SyncStatus.CLEAN
            )
        }
        dao.upsertFiles(indexed)
        indexed.filter { it.offlinePolicy == OfflinePolicy.PINNED }.forEach { file ->
            if (file.isDirectory) {
                indexFolder(account, password, collection, file.href, inheritedPinned = true, budget)
            } else {
                downloadIfNeeded(account, password, file)
            }
        }
    }

    private suspend fun downloadIfNeeded(account: DavAccountEntity, password: CharArray, file: FileNodeEntity) {
        val target = offlineDirectory.resolve(file.collectionId).resolve(file.id)
        if (file.lastSyncedEtag == file.etag && target.isFile) {
            if (file.status != SyncStatus.CLEAN) dao.updateFile(file.copy(status = SyncStatus.CLEAN, localUri = target.toURI().toString()))
            return
        }
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.part")
        dav.get(account, password, file.href).use { input -> temporary.outputStream().use(input::copyTo) }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        dao.updateFile(file.copy(localUri = target.toURI().toString(), lastSyncedEtag = file.etag, status = SyncStatus.CLEAN))
    }

    private fun discoveredCollection(
        account: DavAccountEntity,
        href: String,
        displayName: String,
        kind: CollectionKind,
        syncToken: String?,
        ctag: String?,
        existingCollections: Map<String, DavCollectionEntity>
    ): DiscoveredCollection {
        val existing = existingCollections["$href|$kind"]
        return DiscoveredCollection(
            local = DavCollectionEntity(
                id = existing?.id ?: stableId(account.id, "$href|$kind"),
                accountId = account.id,
                href = href,
                displayName = displayName.ifBlank { href.trimEnd('/').substringAfterLast('/') },
                kind = kind,
                colorArgb = existing?.colorArgb ?: 0xff243b53,
                readOnly = existing?.readOnly ?: false,
                visible = existing?.visible ?: true,
                // Discovery's current token must never overwrite the last successfully applied token.
                syncToken = existing?.syncToken,
                ctag = ctag
            ),
            advertisedSyncToken = syncToken
        )
    }

    private fun stableId(namespace: String, value: String) = UUID.nameUUIDFromBytes("$namespace|$value".encodeToByteArray()).toString()

    companion object {
        private const val MULTIGET_BATCH_SIZE = 100
        private const val CONFLICT_PREFIX = "CONFLICT: "
    }
}

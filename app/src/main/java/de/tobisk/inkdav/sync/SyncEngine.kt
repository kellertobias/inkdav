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
    suspend fun synchronizeAll(): Boolean = dao.enabledAccounts().map { account ->
        runCatching { synchronize(account) }.isSuccess
    }.all { it }

    suspend fun synchronize(account: DavAccountEntity) {
        val password = credentials.get(account.id) ?: error("Credentials unavailable")
        try {
            drainOutbox(account, password)
            val discovered = dav.discoverCollections(account, password)
            val collections = discovered.flatMap { remote ->
                buildList {
                    if (remote.isCalendar &&
                        remote.supportsEvents
                    ) {
                        add(
                            collection(account, remote.href, remote.displayName, CollectionKind.CALENDAR, remote.syncToken, remote.ctag)
                        )
                    }
                    if (remote.isCalendar &&
                        remote.supportsTasks
                    ) {
                        add(
                            collection(
                                account,
                                remote.href,
                                remote.displayName,
                                CollectionKind.TASK_LIST,
                                remote.syncToken,
                                remote.ctag
                            )
                        )
                    }
                    if (account.kind == AccountKind.NASDRIVE &&
                        remote.isCollection
                    ) {
                        add(
                            collection(
                                account,
                                remote.href,
                                remote.displayName,
                                CollectionKind.FILE_ROOT,
                                remote.syncToken,
                                remote.ctag
                            )
                        )
                    }
                }
            }
            dao.upsertCollections(collections)

            val settings = preferences.settings.first()
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val from = now.minusDays(settings.calendarPastDays.toLong()).toInstant()
            val until = now.plusMonths(settings.calendarFutureMonths.toLong()).toInstant()
            collections.forEach { collection ->
                when (collection.kind) {
                    CollectionKind.CALENDAR -> pullCalendar(account, password, collection, "VEVENT", from, until)
                    CollectionKind.TASK_LIST -> pullCalendar(account, password, collection, "VTODO", from, until)
                    CollectionKind.FILE_ROOT -> {
                        pullFileIndex(account, password, collection)
                        dao.enabledMirrors().filter {
                            it.collectionId == collection.id
                        }.forEach { mirrorSyncEngine.synchronize(account, password, collection, it) }
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
                    MutationKind.DELETE -> dav.delete(account, password, pending.targetHref, pending.baseEtag)
                    MutationKind.MKCOL -> dav.makeCollection(account, password, pending.targetHref)
                    MutationKind.MOVE, MutationKind.UPLOAD -> error("File mutation is handled by the transfer worker")
                }
                dao.removeMutation(pending)
            } catch (error: DavHttpException) {
                if (error.code == 409 || error.code == 412) markConflict(pending)
                dao.updateMutation(pending.copy(attemptCount = pending.attemptCount + 1, lastError = error.message))
                if (error.code == 401 || error.code == 403) throw error
            } catch (error: Exception) {
                dao.updateMutation(pending.copy(attemptCount = pending.attemptCount + 1, lastError = error.message))
                throw error
            }
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
        component: String,
        from: Instant,
        until: Instant
    ) {
        // A successful REPORT is a bounded snapshot, not proof that objects outside this window were deleted.
        // Consequently this path only upserts; RFC 6578 tombstones may delete once implemented.
        dav.calendarQuery(account, password, collection.href, component, from, until).forEach { remote ->
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

    private fun collection(
        account: DavAccountEntity,
        href: String,
        displayName: String,
        kind: CollectionKind,
        syncToken: String?,
        ctag: String?
    ) = DavCollectionEntity(
        id = stableId(account.id, "$href|$kind"),
        accountId = account.id,
        href = href,
        displayName = displayName.ifBlank { href.trimEnd('/').substringAfterLast('/') },
        kind = kind,
        syncToken = syncToken,
        ctag = ctag
    )

    private fun stableId(namespace: String, value: String) = UUID.nameUUIDFromBytes("$namespace|$value".encodeToByteArray()).toString()
}

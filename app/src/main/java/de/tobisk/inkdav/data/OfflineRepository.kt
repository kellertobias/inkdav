package de.tobisk.inkdav.data

import de.tobisk.inkdav.dav.IcalendarCodec
import de.tobisk.inkdav.dav.RecurrenceProjector
import java.time.ZoneId
import java.util.UUID

class OfflineRepository(private val dao: InkDavDao) {
    suspend fun resolveEventConflictWithServer(event: CalendarEventEntity) {
        dao.clearMutationsForObject(event.id)
        dao.deleteOccurrencesBySource(event.id)
        dao.deleteEvent(event)
        dao.collection(event.collectionId)?.let { dao.upsertCollection(it.copy(syncToken = null)) }
    }

    suspend fun keepBothEventConflict(event: CalendarEventEntity) {
        val collection = requireNotNull(dao.collection(event.collectionId))
        val uid = "${UUID.randomUUID()}@inkdav"
        val href = collection.href.trimEnd('/') + "/$uid.ics"
        val copy = event.copy(
            id = IcalendarCodec.stableId(event.collectionId, uid),
            remoteHref = href,
            uid = uid,
            etag = null,
            title = "${event.title} (local copy)",
            recurrenceId = null,
            rawIcal = null,
            status = SyncStatus.PENDING,
            locallyDeleted = false,
            localUpdatedAt = System.currentTimeMillis()
        )
        val payload = IcalendarCodec.encode(copy)
        dao.clearMutationsForObject(event.id)
        dao.deleteOccurrencesBySource(event.id)
        dao.deleteEvent(event)
        dao.createEventOffline(
            copy.copy(rawIcal = payload),
            PendingMutationEntity(
                UUID.randomUUID().toString(),
                collection.accountId,
                ObjectKind.EVENT,
                copy.id,
                MutationKind.CREATE,
                href,
                payload = payload
            )
        )
        dao.upsertCollection(collection.copy(syncToken = null))
    }

    suspend fun resolveTaskConflictWithServer(task: DavTaskEntity) {
        dao.clearMutationsForObject(task.id)
        dao.deleteTask(task)
        dao.collection(task.collectionId)?.let { dao.upsertCollection(it.copy(syncToken = null)) }
    }

    suspend fun keepBothTaskConflict(task: DavTaskEntity) {
        val collection = requireNotNull(dao.collection(task.collectionId))
        val uid = "${UUID.randomUUID()}@inkdav"
        val href = collection.href.trimEnd('/') + "/$uid.ics"
        val copy = task.copy(
            id = IcalendarCodec.stableId(task.collectionId, uid),
            remoteHref = href,
            uid = uid,
            etag = null,
            title = "${task.title} (local copy)",
            rawIcal = null,
            status = SyncStatus.PENDING,
            locallyDeleted = false,
            localUpdatedAt = System.currentTimeMillis()
        )
        val payload = IcalendarCodec.encode(copy)
        dao.clearMutationsForObject(task.id)
        dao.deleteTask(task)
        dao.createTaskOffline(
            copy.copy(rawIcal = payload),
            PendingMutationEntity(
                UUID.randomUUID().toString(),
                collection.accountId,
                ObjectKind.TASK,
                copy.id,
                MutationKind.CREATE,
                href,
                payload = payload
            )
        )
        dao.upsertCollection(collection.copy(syncToken = null))
    }

    suspend fun toggleOffline(file: FileNodeEntity) {
        val next = if (file.offlinePolicy == OfflinePolicy.ONLINE_ONLY) OfflinePolicy.PINNED else OfflinePolicy.ONLINE_ONLY
        dao.updateFile(file.copy(offlinePolicy = next, status = if (next == OfflinePolicy.PINNED) SyncStatus.PENDING else SyncStatus.CLEAN))
    }

    suspend fun createEvent(collectionId: String, title: String, startMillis: Long, endMillis: Long, allDay: Boolean) {
        val collection = requireNotNull(dao.collection(collectionId))
        val uid = "${UUID.randomUUID()}@inkdav"
        val href = collection.href.trimEnd('/') + "/$uid.ics"
        val event = CalendarEventEntity(
            id = IcalendarCodec.stableId(collectionId, uid), collectionId = collectionId,
            remoteHref = href, uid = uid, title = title.trim(), startEpochMillis = startMillis,
            endEpochMillis = endMillis, allDay = allDay, status = SyncStatus.PENDING
        )
        dao.createEventOffline(
            event,
            PendingMutationEntity(
                id = UUID.randomUUID().toString(),
                accountId = collection.accountId,
                objectKind = ObjectKind.EVENT,
                objectId = event.id,
                mutationKind = MutationKind.CREATE,
                targetHref = href,
                payload = IcalendarCodec.encode(event)
            )
        )
        dao.upsertOccurrences(
            RecurrenceProjector.project(
                collectionId,
                href,
                IcalendarCodec.encode(event),
                startMillis - 1,
                endMillis + 1,
                ZoneId.systemDefault(),
                SyncStatus.PENDING
            )
        )
    }

    suspend fun createTask(collectionId: String, title: String, dueMillis: Long?) {
        val collection = requireNotNull(dao.collection(collectionId))
        val uid = "${UUID.randomUUID()}@inkdav"
        val href = collection.href.trimEnd('/') + "/$uid.ics"
        val task = DavTaskEntity(
            id = IcalendarCodec.stableId(collectionId, uid),
            collectionId = collectionId,
            remoteHref = href,
            uid = uid,
            title = title.trim(),
            dueEpochMillis = dueMillis,
            status = SyncStatus.PENDING
        )
        dao.createTaskOffline(
            task,
            PendingMutationEntity(
                id = UUID.randomUUID().toString(),
                accountId = collection.accountId,
                objectKind = ObjectKind.TASK,
                objectId = task.id,
                mutationKind = MutationKind.CREATE,
                targetHref = href,
                payload = IcalendarCodec.encode(task)
            )
        )
    }

    suspend fun toggleTask(task: DavTaskEntity) {
        val collection = requireNotNull(dao.collection(task.collectionId))
        if (task.recurrenceRule != null && task.completedAt == null) {
            val stored = requireNotNull(dao.task(task.id))
            val now = System.currentTimeMillis()
            val payload = IcalendarCodec.completeRecurringTask(
                requireNotNull(stored.rawIcal) { "Recurring task source is unavailable" },
                stored,
                requireNotNull(task.dueEpochMillis) { "Recurring task has no occurrence date" },
                now
            )
            dao.clearMutationsForObject(stored.id)
            dao.createTaskOffline(
                stored.copy(rawIcal = payload, status = SyncStatus.PENDING, localUpdatedAt = now),
                PendingMutationEntity(
                    UUID.randomUUID().toString(),
                    collection.accountId,
                    ObjectKind.TASK,
                    stored.id,
                    MutationKind.UPDATE,
                    requireNotNull(stored.remoteHref),
                    stored.etag,
                    payload
                )
            )
            return
        }
        val changed = task.copy(
            completedAt = if (task.completedAt == null) System.currentTimeMillis() else null,
            status = SyncStatus.PENDING,
            localUpdatedAt = System.currentTimeMillis()
        )
        dao.createTaskOffline(
            changed,
            PendingMutationEntity(
                id = UUID.randomUUID().toString(),
                accountId = collection.accountId,
                objectKind = ObjectKind.TASK,
                objectId = changed.id,
                mutationKind = MutationKind.UPDATE,
                targetHref = requireNotNull(changed.remoteHref),
                baseEtag = task.etag,
                payload = IcalendarCodec.encode(changed)
            )
        )
    }

    suspend fun updateEvent(
        event: CalendarEventEntity,
        title: String,
        description: String,
        location: String,
        start: Long,
        end: Long,
        allDay: Boolean
    ) {
        val collection = requireNotNull(dao.collection(event.collectionId))
        val changed = event.copy(
            title = title.trim(),
            description = description,
            location = location,
            startEpochMillis = start,
            endEpochMillis = end,
            allDay = allDay,
            status = SyncStatus.PENDING,
            localUpdatedAt = System.currentTimeMillis()
        )
        val payload = IcalendarCodec.patchEvent(event.rawIcal ?: IcalendarCodec.encode(event), event.recurrenceId, changed)
        val stored = changed.copy(rawIcal = payload)
        dao.clearMutationsForObject(event.id)
        dao.createEventOffline(
            stored,
            PendingMutationEntity(
                UUID.randomUUID().toString(),
                collection.accountId,
                ObjectKind.EVENT,
                event.id,
                MutationKind.UPDATE,
                requireNotNull(event.remoteHref),
                event.etag,
                payload
            )
        )
        dao.replaceOccurrencesForHref(
            event.collectionId,
            requireNotNull(event.remoteHref),
            RecurrenceProjector.project(
                event.collectionId,
                event.remoteHref,
                payload,
                start - 366L * 86_400_000,
                end + 3660L * 86_400_000,
                ZoneId.systemDefault(),
                SyncStatus.PENDING
            )
        )
    }

    suspend fun updateEventOccurrence(
        master: CalendarEventEntity,
        occurrence: CalendarOccurrenceEntity,
        title: String,
        description: String,
        location: String,
        start: Long,
        end: Long,
        allDay: Boolean
    ) {
        val collection = requireNotNull(dao.collection(master.collectionId))
        val changed = master.copy(
            title = title.trim(),
            description = description,
            location = location,
            startEpochMillis = start,
            endEpochMillis = end,
            allDay = allDay,
            recurrenceRule = null,
            status = SyncStatus.PENDING,
            localUpdatedAt = System.currentTimeMillis()
        )
        val (payload, recurrenceId) = IcalendarCodec.upsertEventException(
            master.rawIcal ?: IcalendarCodec.encode(master),
            master,
            occurrence.originalStartEpochMillis,
            changed
        )
        val storedMaster = master.copy(rawIcal = payload, status = SyncStatus.PENDING, localUpdatedAt = System.currentTimeMillis())
        val exception = changed.copy(
            id = IcalendarCodec.stableId(master.collectionId, master.uid, recurrenceId),
            recurrenceId = recurrenceId,
            rawIcal = payload
        )
        dao.clearMutationsForObject(master.id)
        dao.createEventOffline(
            storedMaster,
            PendingMutationEntity(
                UUID.randomUUID().toString(),
                collection.accountId,
                ObjectKind.EVENT,
                master.id,
                MutationKind.UPDATE,
                requireNotNull(master.remoteHref),
                master.etag,
                payload
            )
        )
        dao.upsertEvent(exception)
        dao.replaceOccurrencesForHref(
            master.collectionId,
            requireNotNull(master.remoteHref),
            RecurrenceProjector.project(
                master.collectionId,
                master.remoteHref,
                payload,
                start - 366L * 86_400_000,
                end + 3660L * 86_400_000,
                ZoneId.systemDefault(),
                SyncStatus.PENDING
            )
        )
    }

    suspend fun deleteEventOccurrence(master: CalendarEventEntity, occurrence: CalendarOccurrenceEntity) {
        val collection = requireNotNull(dao.collection(master.collectionId))
        val placeholder = master.copy(
            startEpochMillis = occurrence.startEpochMillis,
            endEpochMillis = occurrence.endEpochMillis,
            recurrenceRule = null
        )
        val (payload, _) = IcalendarCodec.upsertEventException(
            master.rawIcal ?: IcalendarCodec.encode(master),
            master,
            occurrence.originalStartEpochMillis,
            placeholder,
            cancelled = true
        )
        dao.clearMutationsForObject(master.id)
        dao.createEventOffline(
            master.copy(rawIcal = payload, status = SyncStatus.PENDING, localUpdatedAt = System.currentTimeMillis()),
            PendingMutationEntity(
                UUID.randomUUID().toString(),
                collection.accountId,
                ObjectKind.EVENT,
                master.id,
                MutationKind.UPDATE,
                requireNotNull(master.remoteHref),
                master.etag,
                payload
            )
        )
        dao.replaceOccurrencesForHref(
            master.collectionId,
            requireNotNull(master.remoteHref),
            RecurrenceProjector.project(
                master.collectionId,
                master.remoteHref,
                payload,
                occurrence.originalStartEpochMillis - 366L * 86_400_000,
                occurrence.originalStartEpochMillis + 3660L * 86_400_000,
                ZoneId.systemDefault(),
                SyncStatus.PENDING
            )
        )
    }

    suspend fun deleteEvent(event: CalendarEventEntity) {
        val collection = requireNotNull(dao.collection(event.collectionId))
        dao.clearMutationsForObject(event.id)
        // A recurring resource can contain detached components with different source IDs.
        // Deleting by UID removes the master projection and every materialized exception.
        dao.deleteOccurrencesByUid(event.collectionId, event.uid)
        if (event.etag == null) {
            dao.deleteEvent(event)
        } else {
            dao.upsertEvent(event.copy(locallyDeleted = true, status = SyncStatus.PENDING))
            dao.enqueue(
                PendingMutationEntity(
                    UUID.randomUUID().toString(),
                    collection.accountId,
                    ObjectKind.EVENT,
                    event.id,
                    MutationKind.DELETE,
                    requireNotNull(event.remoteHref),
                    event.etag
                )
            )
        }
    }

    suspend fun updateTask(task: DavTaskEntity, title: String, notes: String, dueMillis: Long?) {
        val collection = requireNotNull(dao.collection(task.collectionId))
        val changed = task.copy(
            title = title.trim(),
            notes = notes,
            dueEpochMillis = dueMillis,
            status = SyncStatus.PENDING,
            localUpdatedAt = System.currentTimeMillis()
        )
        val payload = IcalendarCodec.patchTask(task.rawIcal ?: IcalendarCodec.encode(task), changed)
        dao.clearMutationsForObject(task.id)
        dao.createTaskOffline(
            changed.copy(rawIcal = payload),
            PendingMutationEntity(
                UUID.randomUUID().toString(),
                collection.accountId,
                ObjectKind.TASK,
                task.id,
                MutationKind.UPDATE,
                requireNotNull(task.remoteHref),
                task.etag,
                payload
            )
        )
    }

    suspend fun deleteTask(task: DavTaskEntity) {
        val collection = requireNotNull(dao.collection(task.collectionId))
        dao.clearMutationsForObject(task.id)
        if (task.etag == null) {
            dao.deleteTask(task)
        } else {
            dao.upsertTask(task.copy(locallyDeleted = true, status = SyncStatus.PENDING))
            dao.enqueue(
                PendingMutationEntity(
                    UUID.randomUUID().toString(),
                    collection.accountId,
                    ObjectKind.TASK,
                    task.id,
                    MutationKind.DELETE,
                    requireNotNull(task.remoteHref),
                    task.etag
                )
            )
        }
    }
}

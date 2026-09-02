package de.tobisk.inkdav.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InkDavDao {
    @Query("SELECT * FROM accounts ORDER BY displayName")
    fun observeAccounts(): Flow<List<DavAccountEntity>>

    @Query("SELECT * FROM accounts WHERE enabled = 1")
    suspend fun enabledAccounts(): List<DavAccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun account(id: String): DavAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: DavAccountEntity)

    @Delete
    suspend fun deleteAccount(account: DavAccountEntity)

    @Query("DELETE FROM calendar_occurrences WHERE collectionId IN (SELECT id FROM collections WHERE accountId = :accountId)")
    suspend fun deleteAccountOccurrences(accountId: String)

    @Query("DELETE FROM events WHERE collectionId IN (SELECT id FROM collections WHERE accountId = :accountId)")
    suspend fun deleteAccountEvents(accountId: String)

    @Query("DELETE FROM tasks WHERE collectionId IN (SELECT id FROM collections WHERE accountId = :accountId)")
    suspend fun deleteAccountTasks(accountId: String)

    @Query("DELETE FROM file_nodes WHERE collectionId IN (SELECT id FROM collections WHERE accountId = :accountId)")
    suspend fun deleteAccountFiles(accountId: String)

    @Query("DELETE FROM mirror_entries WHERE bindingId IN (SELECT id FROM mirror_bindings WHERE collectionId IN (SELECT id FROM collections WHERE accountId = :accountId))")
    suspend fun deleteAccountMirrorEntries(accountId: String)

    @Query("DELETE FROM mirror_bindings WHERE collectionId IN (SELECT id FROM collections WHERE accountId = :accountId)")
    suspend fun deleteAccountMirrors(accountId: String)

    @Query("DELETE FROM task_widget_exclusions WHERE collectionId IN (SELECT id FROM collections WHERE accountId = :accountId)")
    suspend fun deleteAccountWidgetExclusions(accountId: String)

    @Query("DELETE FROM pending_mutations WHERE accountId = :accountId")
    suspend fun deleteAccountMutations(accountId: String)

    @Query("DELETE FROM collections WHERE accountId = :accountId")
    suspend fun deleteAccountCollections(accountId: String)

    @Transaction
    suspend fun removeAccountData(account: DavAccountEntity) {
        deleteAccountOccurrences(account.id)
        deleteAccountEvents(account.id)
        deleteAccountTasks(account.id)
        deleteAccountFiles(account.id)
        deleteAccountMirrorEntries(account.id)
        deleteAccountMirrors(account.id)
        deleteAccountWidgetExclusions(account.id)
        deleteAccountMutations(account.id)
        deleteAccountCollections(account.id)
        deleteAccount(account)
    }

    @Query("SELECT * FROM collections ORDER BY kind, displayName")
    fun observeCollections(): Flow<List<DavCollectionEntity>>

    @Query("SELECT * FROM collections WHERE accountId = :accountId")
    suspend fun collections(accountId: String): List<DavCollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun collection(id: String): DavCollectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollections(collections: List<DavCollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollection(collection: DavCollectionEntity)

    @Query(
        "SELECT * FROM events WHERE locallyDeleted = 0 AND endEpochMillis >= :start AND startEpochMillis < :end ORDER BY startEpochMillis"
    )
    fun observeEvents(start: Long, end: Long): Flow<List<CalendarEventEntity>>

    @Query(
        "SELECT * FROM events WHERE locallyDeleted = 0 AND endEpochMillis >= :start AND startEpochMillis < :end ORDER BY startEpochMillis"
    )
    suspend fun events(start: Long, end: Long): List<CalendarEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(events: List<CalendarEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(event: CalendarEventEntity)

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun event(id: String): CalendarEventEntity?

    @Query("SELECT * FROM events WHERE status = 'CONFLICT' AND locallyDeleted = 0 ORDER BY localUpdatedAt DESC")
    fun observeConflictingEvents(): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM events WHERE collectionId = :collectionId AND remoteHref = :href")
    suspend fun eventsByHref(collectionId: String, href: String): List<CalendarEventEntity>

    @Query("SELECT * FROM events WHERE collectionId = :collectionId AND uid = :uid AND recurrenceId IS NULL LIMIT 1")
    suspend fun masterEvent(collectionId: String, uid: String): CalendarEventEntity?

    @Query("SELECT * FROM calendar_occurrences WHERE endEpochMillis >= :start AND startEpochMillis < :end ORDER BY startEpochMillis")
    fun observeOccurrences(start: Long, end: Long): Flow<List<CalendarOccurrenceEntity>>

    @Query("SELECT * FROM calendar_occurrences WHERE endEpochMillis > :now ORDER BY startEpochMillis LIMIT :limit")
    suspend fun upcomingOccurrences(now: Long, limit: Int): List<CalendarOccurrenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOccurrences(occurrences: List<CalendarOccurrenceEntity>)

    @Query("DELETE FROM calendar_occurrences WHERE collectionId = :collectionId AND remoteHref = :href")
    suspend fun deleteOccurrencesByHref(collectionId: String, href: String)

    @Query("DELETE FROM calendar_occurrences WHERE sourceEventId = :sourceEventId")
    suspend fun deleteOccurrencesBySource(sourceEventId: String)

    @Query("DELETE FROM calendar_occurrences WHERE collectionId = :collectionId AND uid = :uid")
    suspend fun deleteOccurrencesByUid(collectionId: String, uid: String)

    @Delete
    suspend fun deleteEvent(event: CalendarEventEntity)

    @Query("SELECT * FROM tasks WHERE locallyDeleted = 0 ORDER BY completedAt IS NOT NULL, dueEpochMillis IS NULL, dueEpochMillis, title")
    fun observeTasks(): Flow<List<DavTaskEntity>>

    @Query("SELECT * FROM tasks WHERE locallyDeleted = 0 AND completedAt IS NULL")
    suspend fun openTasks(): List<DavTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<DavTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: DavTaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: String): DavTaskEntity?

    @Query("SELECT * FROM tasks WHERE status = 'CONFLICT' AND locallyDeleted = 0 ORDER BY localUpdatedAt DESC")
    fun observeConflictingTasks(): Flow<List<DavTaskEntity>>

    @Query("SELECT * FROM tasks WHERE collectionId = :collectionId AND remoteHref = :href")
    suspend fun tasksByHref(collectionId: String, href: String): List<DavTaskEntity>

    @Query("SELECT * FROM tasks WHERE collectionId = :collectionId")
    suspend fun tasksInCollection(collectionId: String): List<DavTaskEntity>

    @Delete
    suspend fun deleteTask(task: DavTaskEntity)

    @Query(
        "SELECT * FROM file_nodes WHERE collectionId = :collectionId AND ((:parentHref IS NULL AND parentHref IS NULL) OR parentHref = :parentHref) ORDER BY isDirectory DESC, displayName COLLATE NOCASE"
    )
    fun observeFiles(collectionId: String, parentHref: String?): Flow<List<FileNodeEntity>>

    @Query("SELECT * FROM file_nodes WHERE id = :id")
    suspend fun file(id: String): FileNodeEntity?

    @Query("SELECT * FROM file_nodes WHERE collectionId = :collectionId AND href = :href")
    suspend fun fileByHref(collectionId: String, href: String): FileNodeEntity?

    @Query(
        "SELECT * FROM file_nodes WHERE collectionId = :collectionId AND parentHref = :parentHref ORDER BY isDirectory DESC, displayName COLLATE NOCASE"
    )
    suspend fun files(collectionId: String, parentHref: String): List<FileNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFiles(files: List<FileNodeEntity>)

    @Update
    suspend fun updateFile(file: FileNodeEntity)

    @Query("SELECT * FROM pending_mutations ORDER BY createdAt")
    suspend fun pendingMutations(): List<PendingMutationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(mutation: PendingMutationEntity)

    @Delete
    suspend fun removeMutation(mutation: PendingMutationEntity)

    @Update
    suspend fun updateMutation(mutation: PendingMutationEntity)

    @Query("DELETE FROM pending_mutations WHERE objectId = :objectId")
    suspend fun clearMutationsForObject(objectId: String)

    @Query("SELECT COUNT(*) FROM pending_mutations")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM mirror_bindings ORDER BY displayName")
    fun observeMirrors(): Flow<List<MirrorBindingEntity>>

    @Query("SELECT * FROM mirror_bindings WHERE enabled = 1")
    suspend fun enabledMirrors(): List<MirrorBindingEntity>

    @Query("SELECT * FROM mirror_bindings WHERE id = :id")
    suspend fun mirror(id: String): MirrorBindingEntity?

    @Query("SELECT * FROM mirror_bindings WHERE collectionId = :collectionId AND remoteRootHref = :remoteRootHref LIMIT 1")
    suspend fun mirrorForRemoteRoot(collectionId: String, remoteRootHref: String): MirrorBindingEntity?

    @Query("SELECT * FROM mirror_bindings WHERE localTreeUri = :localTreeUri LIMIT 1")
    suspend fun mirrorForLocalTree(localTreeUri: String): MirrorBindingEntity?

    @Query("SELECT * FROM mirror_bindings WHERE collectionId = :collectionId")
    suspend fun mirrorsForCollection(collectionId: String): List<MirrorBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMirror(binding: MirrorBindingEntity)

    @Delete
    suspend fun deleteMirror(binding: MirrorBindingEntity)

    @Query("SELECT * FROM mirror_entries WHERE bindingId = :bindingId")
    suspend fun mirrorEntries(bindingId: String): List<MirrorEntryEntity>

    @Query(
        "SELECT * FROM mirror_entries WHERE bindingId = :bindingId AND " +
            "((:parentPath = '' AND instr(relativePath, '/') = 0) OR " +
            "(:parentPath != '' AND relativePath LIKE :parentPath || '/%' AND " +
            "instr(substr(relativePath, length(:parentPath) + 2), '/') = 0)) " +
            "ORDER BY isDirectory DESC, relativePath COLLATE NOCASE"
    )
    fun observeMirrorChildren(bindingId: String, parentPath: String): Flow<List<MirrorEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMirrorEntries(entries: List<MirrorEntryEntity>)

    @Query("DELETE FROM mirror_entries WHERE bindingId = :bindingId AND relativePath = :relativePath")
    suspend fun deleteMirrorEntry(bindingId: String, relativePath: String)

    @Query("DELETE FROM mirror_entries WHERE bindingId = :bindingId")
    suspend fun deleteMirrorEntries(bindingId: String)

    @Transaction
    suspend fun removeMirror(binding: MirrorBindingEntity) {
        deleteMirrorEntries(binding.id)
        deleteMirror(binding)
    }

    @Query("SELECT * FROM task_widget_configs WHERE appWidgetId = :id")
    suspend fun taskWidgetConfig(id: Int): TaskWidgetConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskWidgetConfig(config: TaskWidgetConfigEntity)

    @Query("SELECT * FROM task_widget_exclusions WHERE appWidgetId = :id")
    suspend fun taskWidgetExclusions(id: Int): List<TaskWidgetExcludedListEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskWidgetExclusions(exclusions: List<TaskWidgetExcludedListEntity>)

    @Query("DELETE FROM task_widget_exclusions WHERE appWidgetId = :id")
    suspend fun clearTaskWidgetExclusions(id: Int)

    @Query("DELETE FROM task_widget_configs WHERE appWidgetId = :id")
    suspend fun deleteTaskWidgetConfig(id: Int)

    @Query(
        "SELECT * FROM tasks WHERE locallyDeleted = 0 AND completedAt IS NULL AND collectionId = :collectionId ORDER BY dueEpochMillis IS NULL, dueEpochMillis, title LIMIT :limit"
    )
    suspend fun widgetListTasks(collectionId: String, limit: Int): List<DavTaskEntity>

    @Query(
        "SELECT * FROM tasks WHERE locallyDeleted = 0 AND completedAt IS NULL AND dueEpochMillis IS NOT NULL AND dueEpochMillis < :endExclusive AND collectionId NOT IN (:excluded) ORDER BY dueEpochMillis, title LIMIT :limit"
    )
    suspend fun widgetUpcomingTasks(endExclusive: Long, excluded: List<String>, limit: Int): List<DavTaskEntity>

    @Transaction
    suspend fun createEventOffline(event: CalendarEventEntity, mutation: PendingMutationEntity) {
        upsertEvent(event)
        enqueue(mutation)
    }

    @Transaction
    suspend fun createTaskOffline(task: DavTaskEntity, mutation: PendingMutationEntity) {
        upsertTask(task)
        enqueue(mutation)
    }

    @Transaction
    suspend fun replaceOccurrencesForHref(collectionId: String, href: String, occurrences: List<CalendarOccurrenceEntity>) {
        deleteOccurrencesByHref(collectionId, href)
        upsertOccurrences(occurrences)
    }
}

package de.tobisk.inkdav.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AccountKind { DAV, NASDRIVE, LOCAL }
enum class CollectionKind { CALENDAR, TASK_LIST, FILE_ROOT, SHARE }
enum class MutationKind { CREATE, UPDATE, DELETE, MKCOL, MOVE, UPLOAD }
enum class ObjectKind { EVENT, TASK, FILE }
enum class SyncStatus { CLEAN, PENDING, SYNCING, CONFLICT, ERROR }
enum class OfflinePolicy { ONLINE_ONLY, PINNED, MIRROR }
enum class MirrorState { UNINITIALIZED, BASELINED, ERROR }
enum class MirrorEntryStatus { CLEAN, CONFLICT, PENDING_UPLOAD, PENDING_DOWNLOAD }
enum class TaskWidgetMode { LIST, UPCOMING }

@Entity(tableName = "accounts")
data class DavAccountEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val baseUrl: String,
    val username: String,
    val kind: AccountKind = AccountKind.DAV,
    val enabled: Boolean = true,
    val lastSyncAt: Long? = null,
    val lastSyncError: String? = null
)

@Entity(
    tableName = "collections",
    foreignKeys = [
        ForeignKey(
            entity = DavAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("accountId"), Index(value = ["accountId", "href", "kind"], unique = true)]
)
data class DavCollectionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val href: String,
    val displayName: String,
    val kind: CollectionKind,
    val colorArgb: Long = 0xff243b53,
    val readOnly: Boolean = false,
    val visible: Boolean = true,
    val syncToken: String? = null,
    val ctag: String? = null
)

@Entity(
    tableName = "events",
    indices = [
        Index("collectionId"), Index("startEpochMillis"),
        Index(value = ["collectionId", "remoteHref"]),
        Index(value = ["collectionId", "uid"])
    ]
)
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val collectionId: String,
    val remoteHref: String? = null,
    val uid: String,
    val etag: String? = null,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val allDay: Boolean = false,
    val timezone: String? = null,
    val recurrenceRule: String? = null,
    val recurrenceId: String? = null,
    val rawIcal: String? = null,
    val status: SyncStatus = SyncStatus.CLEAN,
    val locallyDeleted: Boolean = false,
    val localUpdatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "calendar_occurrences",
    indices = [
        Index(
            "collectionId"
        ), Index(
            "sourceEventId"
        ), Index("startEpochMillis"), Index(value = ["collectionId", "uid", "originalStartEpochMillis"], unique = true)
    ]
)
data class CalendarOccurrenceEntity(
    @PrimaryKey val id: String,
    val sourceEventId: String,
    val collectionId: String,
    val remoteHref: String?,
    val uid: String,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val originalStartEpochMillis: Long,
    val allDay: Boolean,
    val isException: Boolean = false,
    val status: SyncStatus = SyncStatus.CLEAN
)

@Entity(
    tableName = "tasks",
    indices = [Index("collectionId"), Index("dueEpochMillis"), Index(value = ["collectionId", "remoteHref"], unique = true)]
)
data class DavTaskEntity(
    @PrimaryKey val id: String,
    val collectionId: String,
    val remoteHref: String? = null,
    val uid: String,
    val etag: String? = null,
    val title: String,
    val notes: String = "",
    val dueEpochMillis: Long? = null,
    val startEpochMillis: Long? = null,
    val completedAt: Long? = null,
    val priority: Int = 0,
    val recurrenceRule: String? = null,
    val rawIcal: String? = null,
    val status: SyncStatus = SyncStatus.CLEAN,
    val locallyDeleted: Boolean = false,
    val localUpdatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "file_nodes",
    indices = [Index("collectionId"), Index("parentHref"), Index(value = ["collectionId", "href"], unique = true)]
)
data class FileNodeEntity(
    @PrimaryKey val id: String,
    val collectionId: String,
    val parentHref: String?,
    val href: String,
    val displayName: String,
    val isDirectory: Boolean,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val modifiedAt: Long? = null,
    val etag: String? = null,
    val offlinePolicy: OfflinePolicy = OfflinePolicy.ONLINE_ONLY,
    val localUri: String? = null,
    val localContentHash: String? = null,
    val lastSyncedEtag: String? = null,
    val status: SyncStatus = SyncStatus.CLEAN
)

@Entity(tableName = "mirror_bindings", indices = [Index("collectionId"), Index(value = ["collectionId", "remoteRootHref"], unique = true)])
data class MirrorBindingEntity(
    @PrimaryKey val id: String,
    val collectionId: String,
    val remoteRootHref: String,
    val localTreeUri: String,
    val displayName: String,
    val enabled: Boolean = true,
    val state: MirrorState = MirrorState.UNINITIALIZED,
    val lastCompleteSyncAt: Long? = null,
    val lastError: String? = null,
    val itemLimit: Int = 10_000
)

@Entity(tableName = "mirror_entries", indices = [Index("bindingId"), Index(value = ["bindingId", "relativePath"], unique = true)])
data class MirrorEntryEntity(
    @PrimaryKey val id: String,
    val bindingId: String,
    val relativePath: String,
    val remoteHref: String,
    val localDocumentUri: String?,
    val isDirectory: Boolean,
    val mimeType: String? = null,
    val baselineRemoteEtag: String? = null,
    val baselineLocalHash: String? = null,
    val currentRemoteEtag: String? = null,
    val currentLocalHash: String? = null,
    val status: MirrorEntryStatus = MirrorEntryStatus.CLEAN,
    val conflictReason: String? = null
)

@Entity(tableName = "task_widget_configs")
data class TaskWidgetConfigEntity(
    @PrimaryKey val appWidgetId: Int,
    val mode: TaskWidgetMode = TaskWidgetMode.UPCOMING,
    val listCollectionId: String? = null,
    val lookAheadDays: Int = 7
)

@Entity(tableName = "task_widget_exclusions", primaryKeys = ["appWidgetId", "collectionId"], indices = [Index("appWidgetId")])
data class TaskWidgetExcludedListEntity(val appWidgetId: Int, val collectionId: String)

@Entity(tableName = "pending_mutations", indices = [Index("accountId"), Index("objectId"), Index("createdAt")])
data class PendingMutationEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val objectKind: ObjectKind,
    val objectId: String,
    val mutationKind: MutationKind,
    val targetHref: String,
    val baseEtag: String? = null,
    val payload: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val lastError: String? = null
)

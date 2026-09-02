package de.tobisk.inkdav.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class EnumConverters {
    @TypeConverter fun accountKind(value: AccountKind) = value.name

    @TypeConverter fun accountKind(value: String) = AccountKind.valueOf(value)

    @TypeConverter fun collectionKind(value: CollectionKind) = value.name

    @TypeConverter fun collectionKind(value: String) = CollectionKind.valueOf(value)

    @TypeConverter fun mutationKind(value: MutationKind) = value.name

    @TypeConverter fun mutationKind(value: String) = MutationKind.valueOf(value)

    @TypeConverter fun objectKind(value: ObjectKind) = value.name

    @TypeConverter fun objectKind(value: String) = ObjectKind.valueOf(value)

    @TypeConverter fun syncStatus(value: SyncStatus) = value.name

    @TypeConverter fun syncStatus(value: String) = SyncStatus.valueOf(value)

    @TypeConverter fun offlinePolicy(value: OfflinePolicy) = value.name

    @TypeConverter fun offlinePolicy(value: String) = OfflinePolicy.valueOf(value)

    @TypeConverter fun mirrorState(value: MirrorState) = value.name

    @TypeConverter fun mirrorState(value: String) = MirrorState.valueOf(value)

    @TypeConverter fun mirrorEntryStatus(value: MirrorEntryStatus) = value.name

    @TypeConverter fun mirrorEntryStatus(value: String) = MirrorEntryStatus.valueOf(value)

    @TypeConverter fun taskWidgetMode(value: TaskWidgetMode) = value.name

    @TypeConverter fun taskWidgetMode(value: String) = TaskWidgetMode.valueOf(value)
}

@Database(
    entities = [
        DavAccountEntity::class, DavCollectionEntity::class, CalendarEventEntity::class,
        DavTaskEntity::class, FileNodeEntity::class, PendingMutationEntity::class,
        CalendarOccurrenceEntity::class, MirrorBindingEntity::class, MirrorEntryEntity::class,
        TaskWidgetConfigEntity::class, TaskWidgetExcludedListEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(EnumConverters::class)
abstract class InkDavDatabase : RoomDatabase() {
    abstract fun dao(): InkDavDao

    companion object {
        @Volatile private var instance: InkDavDatabase? = null

        fun get(context: Context): InkDavDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                InkDavDatabase::class.java,
                "inkdav.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS calendar_occurrences (id TEXT NOT NULL PRIMARY KEY, sourceEventId TEXT NOT NULL, collectionId TEXT NOT NULL, remoteHref TEXT, uid TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, location TEXT NOT NULL, startEpochMillis INTEGER NOT NULL, endEpochMillis INTEGER NOT NULL, originalStartEpochMillis INTEGER NOT NULL, allDay INTEGER NOT NULL, isException INTEGER NOT NULL, status TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_occurrences_collectionId ON calendar_occurrences(collectionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_occurrences_sourceEventId ON calendar_occurrences(sourceEventId)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_calendar_occurrences_startEpochMillis ON calendar_occurrences(startEpochMillis)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_calendar_occurrences_collectionId_uid_originalStartEpochMillis ON calendar_occurrences(collectionId, uid, originalStartEpochMillis)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mirror_bindings (id TEXT NOT NULL PRIMARY KEY, collectionId TEXT NOT NULL, remoteRootHref TEXT NOT NULL, localTreeUri TEXT NOT NULL, displayName TEXT NOT NULL, enabled INTEGER NOT NULL, state TEXT NOT NULL, lastCompleteSyncAt INTEGER, lastError TEXT, itemLimit INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mirror_bindings_collectionId ON mirror_bindings(collectionId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_mirror_bindings_collectionId_remoteRootHref ON mirror_bindings(collectionId, remoteRootHref)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mirror_entries (id TEXT NOT NULL PRIMARY KEY, bindingId TEXT NOT NULL, relativePath TEXT NOT NULL, remoteHref TEXT NOT NULL, localDocumentUri TEXT, isDirectory INTEGER NOT NULL, mimeType TEXT, baselineRemoteEtag TEXT, baselineLocalHash TEXT, currentRemoteEtag TEXT, currentLocalHash TEXT, status TEXT NOT NULL, conflictReason TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mirror_entries_bindingId ON mirror_entries(bindingId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_mirror_entries_bindingId_relativePath ON mirror_entries(bindingId, relativePath)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS task_widget_configs (appWidgetId INTEGER NOT NULL PRIMARY KEY, mode TEXT NOT NULL, listCollectionId TEXT, lookAheadDays INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS task_widget_exclusions (appWidgetId INTEGER NOT NULL, collectionId TEXT NOT NULL, PRIMARY KEY(appWidgetId, collectionId))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_widget_exclusions_appWidgetId ON task_widget_exclusions(appWidgetId)")
                db.execSQL("DROP INDEX IF EXISTS index_events_collectionId_remoteHref")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_collectionId_remoteHref ON events(collectionId, remoteHref)")
            }
        }
    }
}

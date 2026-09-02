package de.tobisk.inkdav.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkDavMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        InkDavDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateOneToTwoPreservesAccountsAndCreatesProjectionTables() {
        helper.createDatabase(NAME, 1).apply {
            execSQL(
                "INSERT INTO accounts (id, displayName, baseUrl, username, kind, enabled, lastSyncAt, lastSyncError) " +
                    "VALUES ('account', 'DAV', 'https://example.test/', 'user', 'DAV', 1, NULL, NULL)"
            )
            close()
        }

        helper.runMigrationsAndValidate(NAME, 2, true, InkDavDatabase.MIGRATION_1_2).use { database ->
            database.query("SELECT displayName FROM accounts WHERE id = 'account'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("DAV", cursor.getString(0))
            }
            database.query("SELECT COUNT(*) FROM calendar_occurrences").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM mirror_bindings").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    companion object {
        private const val NAME = "migration-test"
    }
}

package de.tobisk.inkdav.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountUpsertInstrumentedTest {
    @Test
    fun updatingSyncStatusDoesNotDeleteCalendarCollections() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, InkDavDatabase::class.java).build()
        try {
            val dao = database.dao()
            val account = DavAccountEntity("account", "Personal", "https://example.test/dav/", "user")
            val calendar = DavCollectionEntity(
                id = "calendar",
                accountId = account.id,
                href = "/dav/calendars/user/personal/",
                displayName = "Personal",
                kind = CollectionKind.CALENDAR
            )

            dao.upsertAccount(account)
            dao.upsertCollection(calendar)
            dao.upsertAccount(account.copy(lastSyncAt = 1234L))

            assertEquals(listOf(calendar), dao.collections(account.id))
            assertEquals(1234L, dao.account(account.id)?.lastSyncAt)
        } finally {
            database.close()
        }
    }
}

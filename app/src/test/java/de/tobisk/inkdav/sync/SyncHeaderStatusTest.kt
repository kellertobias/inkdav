package de.tobisk.inkdav.sync

import de.tobisk.inkdav.data.DavAccountEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncHeaderStatusTest {
    private fun account(lastSyncAt: Long? = 1L, lastSyncError: String? = null, enabled: Boolean = true) = DavAccountEntity("id-$lastSyncAt-$lastSyncError-$enabled", "HomeLab", "https://calendar.home.arpa/", "user", enabled = enabled, lastSyncAt = lastSyncAt, lastSyncError = lastSyncError)

    @Test
    fun `account error is not reported as up to date`() {
        val status = syncHeaderStatus(ManualSyncState(), 0, listOf(account(lastSyncAt = null, lastSyncError = "Unable to resolve host")))

        assertEquals(SyncHeaderStatus("Sync error", warning = true), status)
    }

    @Test
    fun `stored error outranks a stale finished sync label`() {
        val status = syncHeaderStatus(ManualSyncState(label = "Sync complete"), 0, listOf(account(), account(lastSyncError = "x"), account(lastSyncError = "y", lastSyncAt = 2L)))

        assertEquals(SyncHeaderStatus("2 sync errors", warning = true), status)
    }

    @Test
    fun `running sync label wins while active`() {
        val status = syncHeaderStatus(ManualSyncState(active = true, label = "Checking accounts…"), 0, listOf(account(lastSyncError = "x")))

        assertEquals(SyncHeaderStatus("Checking accounts…", warning = false), status)
    }

    @Test
    fun `never synchronized account is not up to date`() {
        assertEquals(SyncHeaderStatus("Not synced yet", warning = false), syncHeaderStatus(ManualSyncState(), 0, listOf(account(lastSyncAt = null))))
    }

    @Test
    fun `healthy accounts are up to date and disabled accounts are ignored`() {
        val accounts = listOf(account(), account(lastSyncAt = null, lastSyncError = "x", enabled = false))

        assertEquals(SyncHeaderStatus("Up to date", warning = false), syncHeaderStatus(ManualSyncState(), 0, accounts))
        assertEquals(SyncHeaderStatus("3 waiting", warning = true), syncHeaderStatus(ManualSyncState(), 3, accounts))
        assertNull(syncHeaderStatus(ManualSyncState(), 0, emptyList()))
    }
}

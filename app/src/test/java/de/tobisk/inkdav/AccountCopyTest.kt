package de.tobisk.inkdav

import de.tobisk.inkdav.data.AccountKind
import de.tobisk.inkdav.data.DavAccountEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCopyTest {
    @Test
    fun `copy keeps login and type but starts clean at the new endpoint`() {
        val source = DavAccountEntity(
            id = "source",
            displayName = "Calendar",
            baseUrl = "https://dav.example.test/calendar/",
            username = "tobias",
            kind = AccountKind.DAV,
            enabled = false,
            lastSyncAt = 123L,
            lastSyncError = "old error"
        )

        val copy = source.copyForEndpoint("copy", " Tasks ", "https://dav.example.test/tasks")

        assertEquals("copy", copy.id)
        assertEquals("Tasks", copy.displayName)
        assertEquals("https://dav.example.test/tasks/", copy.baseUrl)
        assertEquals(source.username, copy.username)
        assertEquals(source.kind, copy.kind)
        assertTrue(copy.enabled)
        assertNull(copy.lastSyncAt)
        assertNull(copy.lastSyncError)
    }

    @Test
    fun `copy keeps a trusted certificate only on the same TLS endpoint`() {
        val source = DavAccountEntity("source", "Calendar", "https://dav.example.test/calendar/", "tobias", trustedCertificateSha256 = "AA:BB")

        assertEquals("AA:BB", source.copyForEndpoint("same", "Tasks", "https://dav.example.test/tasks").trustedCertificateSha256)
        assertNull(source.copyForEndpoint("other", "Tasks", "https://other.example.test/tasks").trustedCertificateSha256)
    }
}

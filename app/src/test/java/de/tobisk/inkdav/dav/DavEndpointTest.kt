package de.tobisk.inkdav.dav

import de.tobisk.inkdav.data.AccountKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DavEndpointTest {
    @Test
    fun cardDavAddressBookEndpointBecomesCalendarSibling() {
        assertEquals(
            "https://example.test/dav/calendars/person/",
            normalizeDavBaseUrl("https://example.test/dav/addressbooks/person/", AccountKind.DAV)
        )
    }

    @Test
    fun nasDriveAndNormalCalendarEndpointsRemainUnchanged() {
        assertEquals(
            "https://example.test/dav/addressbooks/person/",
            normalizeDavBaseUrl("https://example.test/dav/addressbooks/person", AccountKind.NASDRIVE)
        )
        assertEquals(
            "https://example.test/dav/calendars/person/",
            normalizeDavBaseUrl("https://example.test/dav/calendars/person", AccountKind.DAV)
        )
    }
}

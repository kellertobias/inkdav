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

    @Test
    fun appendedPrincipalPathIsRemovedFromCalendarCollection() {
        assertEquals(
            "/dav/calendars/person/travel/",
            normalizeCalendarCollectionHref(
                "/dav/calendars/person/travel/dav/principals/person/"
            )
        )
        assertEquals(
            "https://example.test/dav/calendars/person/travel/",
            normalizeCalendarCollectionHref(
                "https://example.test/dav/calendars/person/travel/dav/principals/person/"
            )
        )
    }

    @Test
    fun legitimateCalendarCollectionHrefRemainsUnchanged() {
        assertEquals(
            "/dav/calendars/person/travel/",
            normalizeCalendarCollectionHref("/dav/calendars/person/travel/")
        )
        assertEquals(
            "/remote.php/dav/calendars/person/travel/",
            normalizeCalendarCollectionHref("/remote.php/dav/calendars/person/travel/")
        )
    }
}

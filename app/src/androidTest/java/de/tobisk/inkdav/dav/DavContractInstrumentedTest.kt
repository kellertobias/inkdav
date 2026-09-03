package de.tobisk.inkdav.dav

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.tobisk.inkdav.data.DavAccountEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DavContractInstrumentedTest {
    private lateinit var server: MockWebServer
    private lateinit var account: DavAccountEntity

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        account = DavAccountEntity("account", "Test", server.url("/").toString(), "user")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun syncCollectionParsesChangesAndDirectTombstones() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<d:multistatus xmlns:d="DAV:">
                    <d:response><d:href>/cal/changed.ics</d:href><d:propstat><d:prop><d:getetag>"2"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    <d:response><d:href>/cal/deleted.ics</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>
                    <d:sync-token>token-2</d:sync-token>
                  </d:multistatus>
                """.trimIndent()
            )
        )

        val result = OkHttpDavClient().syncCollection(account, "secret".toCharArray(), "/cal/", "token-1")

        assertEquals("token-2", result.nextSyncToken)
        assertFalse(result.resources.first().deleted)
        assertTrue(result.resources.last().deleted)
        server.takeRequest().let { request ->
            assertEquals("REPORT", request.method)
            assertTrue(request.body.readUtf8().contains("<d:sync-token>token-1</d:sync-token>"))
            assertTrue(request.headers["Authorization"].orEmpty().startsWith("Basic "))
        }
    }

    @Test
    fun calendarQueryAndMoveUseExpectedDavContracts() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))
        server.enqueue(MockResponse().setResponseCode(201))
        val client = OkHttpDavClient()

        client.calendarQuery(
            account,
            "secret".toCharArray(),
            "/cal/",
            "VEVENT",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2027-01-01T00:00:00Z")
        )
        client.move(account, "secret".toCharArray(), "/files/old.txt", "/files/new.txt", "\"etag\"")

        assertTrue(server.takeRequest().body.readUtf8().contains("calendar-query"))
        server.takeRequest().let { request ->
            assertEquals("MOVE", request.method)
            assertEquals(server.url("/files/new.txt").toString(), request.headers["Destination"])
            assertEquals("F", request.headers["Overwrite"])
            assertEquals("\"etag\"", request.headers["If-Match"])
        }
    }

    @Test
    fun recurrenceWithEmbeddedTimezoneParsesOnAndroid() {
        val raw = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//InkDAV Android contract//EN
BEGIN:VTIMEZONE
TZID:Europe/Berlin
BEGIN:STANDARD
DTSTART:20251026T030000
TZOFFSETFROM:+0200
TZOFFSETTO:+0100
RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
END:STANDARD
BEGIN:DAYLIGHT
DTSTART:20250330T020000
TZOFFSETFROM:+0100
TZOFFSETTO:+0200
RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
END:DAYLIGHT
END:VTIMEZONE
BEGIN:VEVENT
UID:android-timezone
DTSTART;TZID=Europe/Berlin:20260903T090000
DTEND;TZID=Europe/Berlin:20260903T100000
RRULE:FREQ=DAILY;COUNT=2
SUMMARY:Android timezone
END:VEVENT
END:VCALENDAR"""

        val occurrences = RecurrenceProjector.project(
            "calendar",
            "/calendar/android-timezone.ics",
            raw,
            Instant.parse("2026-09-03T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-09-06T00:00:00Z").toEpochMilli(),
            ZoneId.of("Europe/Berlin")
        )

        assertEquals(2, occurrences.size)
        assertEquals(Instant.parse("2026-09-03T07:00:00Z").toEpochMilli(), occurrences.first().startEpochMillis)
    }
}

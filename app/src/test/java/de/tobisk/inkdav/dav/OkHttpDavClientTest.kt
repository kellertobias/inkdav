package de.tobisk.inkdav.dav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class OkHttpDavClientTest {
    @Test
    fun `server DAV href without leading slash resolves from origin`() {
        val resolved = OkHttpDavClient().resolve(
            "https://example.test/dav/calendars/user/personal/",
            "dav/principals/user/"
        )

        assertEquals("https://example.test/dav/principals/user/", resolved)
    }

    @Test
    fun `ordinary relative resource remains relative to its collection`() {
        val resolved = OkHttpDavClient().resolve(
            "https://example.test/dav/calendars/user/personal/",
            "event.ics"
        )

        assertEquals("https://example.test/dav/calendars/user/personal/event.ics", resolved)
    }

    @Test
    fun `sync multistatus distinguishes direct tombstones and returns collection token`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/calendar/changed.ics</d:href>
                <d:propstat><d:prop><d:getetag>"changed"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response><d:href>/calendar/deleted.ics</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>
              <d:sync-token>https://example.test/token/2</d:sync-token>
            </d:multistatus>
        """.trimIndent()

        val parsed = OkHttpDavClient().parseMultiStatus(xml.byteInputStream(), KXmlParser())

        assertEquals("https://example.test/token/2", parsed.syncToken)
        assertEquals(listOf("/calendar/changed.ics", "/calendar/deleted.ics"), parsed.resources.map { it.href })
        assertFalse(parsed.resources.first().deleted)
        assertTrue(parsed.resources.last().deleted)
    }

    @Test
    fun `missing property propstat does not masquerade as deleted resource`() {
        val xml = """<d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/calendar/live.ics</d:href>
              <d:propstat><d:prop><d:getetag/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
            </d:response>
          </d:multistatus>"""

        assertFalse(OkHttpDavClient().parseMultiStatus(xml.byteInputStream(), KXmlParser()).resources.single().deleted)
    }

    @Test
    fun `nested principal href does not contaminate response href`() {
        val xml = """<d:multistatus xmlns:d="DAV:">
            <d:response>
              <d:href>/dav/calendars/user/personal/</d:href>
              <d:propstat><d:prop>
                <d:displayname>Personal</d:displayname>
                <d:owner><d:href>dav/principals/user/</d:href></d:owner>
              </d:prop></d:propstat>
            </d:response>
          </d:multistatus>"""

        val resource = OkHttpDavClient().parseMultiStatus(xml.byteInputStream(), KXmlParser()).resources.single()

        assertEquals("/dav/calendars/user/personal/", resource.href)
        assertEquals("Personal", resource.displayName)
    }
}

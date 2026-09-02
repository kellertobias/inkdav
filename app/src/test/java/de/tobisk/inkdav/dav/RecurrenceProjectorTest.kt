package de.tobisk.inkdav.dav

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceProjectorTest {
    @Test fun expandsExdateRdateMovedAndCancelledExceptionsIdempotently() {
        val raw = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//EN
BEGIN:VEVENT
UID:series
DTSTART;TZID=Europe/Berlin:20260902T090000
DTEND;TZID=Europe/Berlin:20260902T100000
RRULE:FREQ=WEEKLY;COUNT=4
EXDATE;TZID=Europe/Berlin:20260909T090000
RDATE;TZID=Europe/Berlin:20260910T090000
SUMMARY:Weekly
END:VEVENT
BEGIN:VEVENT
UID:series
RECURRENCE-ID;TZID=Europe/Berlin:20260916T090000
DTSTART;TZID=Europe/Berlin:20260917T110000
DTEND;TZID=Europe/Berlin:20260917T120000
SUMMARY:Moved
END:VEVENT
BEGIN:VEVENT
UID:series
RECURRENCE-ID;TZID=Europe/Berlin:20260923T090000
DTSTART;TZID=Europe/Berlin:20260923T090000
DTEND;TZID=Europe/Berlin:20260923T100000
STATUS:CANCELLED
SUMMARY:Cancelled
END:VEVENT
END:VCALENDAR"""
        val zone = ZoneId.of("Europe/Berlin")
        val from = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
        val until = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        val first = RecurrenceProjector.project("cal", "/series.ics", raw, from, until, zone)
        val second = RecurrenceProjector.project("cal", "/series.ics", raw, from, until, zone)
        assertEquals(first, second)
        assertEquals(listOf("Weekly", "Weekly", "Moved"), first.map { it.title })
        assertEquals(listOf(2, 10, 17), first.map { Instant.ofEpochMilli(it.startEpochMillis).atZone(zone).dayOfMonth })
        assertTrue(first.last().isException)
    }

    @Test fun weeklyLocalTimeStaysNineAcrossDst() {
        val raw = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//EN
BEGIN:VEVENT
UID:dst
DTSTART;TZID=Europe/Berlin:20261018T090000
DTEND;TZID=Europe/Berlin:20261018T100000
RRULE:FREQ=WEEKLY;COUNT=3
SUMMARY:DST
END:VEVENT
END:VCALENDAR"""
        val zone = ZoneId.of("Europe/Berlin")
        val occurrences = RecurrenceProjector.project(
            "cal",
            "/dst.ics",
            raw,
            Instant.parse("2026-10-17T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-11-10T00:00:00Z").toEpochMilli(),
            zone
        )
        assertEquals(listOf(9, 9, 9), occurrences.map { Instant.ofEpochMilli(it.startEpochMillis).atZone(zone).hour })
        assertFalse(occurrences.map { it.startEpochMillis }.zipWithNext().all { (a, b) -> b - a == 7 * 86_400_000L })
    }
}

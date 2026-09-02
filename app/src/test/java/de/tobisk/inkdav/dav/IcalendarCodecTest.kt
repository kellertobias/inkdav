package de.tobisk.inkdav.dav

import de.tobisk.inkdav.data.CalendarEventEntity
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IcalendarCodecTest {
    @Test fun parsesFoldedTimedEventAndPreservesRawPayload() {
        val raw = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:abc-123
DTSTART:20260902T080000Z
DTEND:20260902T093000Z
SUMMARY:Long calendar
 title
LOCATION:Studio\, Berlin
RRULE:FREQ=WEEKLY;BYDAY=WE
END:VEVENT
END:VCALENDAR"""
        val parsed = IcalendarCodec.parse("calendar", "/abc.ics", "etag", raw)
        assertEquals(1, parsed.events.size)
        with(parsed.events.single()) {
            assertEquals("Long calendartitle", title)
            assertEquals("Studio, Berlin", location)
            assertEquals("FREQ=WEEKLY;BYDAY=WE", recurrenceRule)
            assertEquals(Instant.parse("2026-09-02T08:00:00Z").toEpochMilli(), startEpochMillis)
            assertEquals(raw, rawIcal)
        }
    }

    @Test fun encodesAllDayEventWithExclusiveEndDate() {
        val event = CalendarEventEntity(
            id = "id",
            collectionId = "calendar",
            uid = "uid",
            title = "Away, all day",
            startEpochMillis = Instant.parse("2026-09-02T00:00:00Z").toEpochMilli(),
            endEpochMillis = Instant.parse("2026-09-03T00:00:00Z").toEpochMilli(),
            allDay = true
        )
        val encoded = IcalendarCodec.encode(event)
        assertTrue(encoded.contains("DTSTART;VALUE=DATE:20260902\r\n"))
        assertTrue(encoded.contains("DTEND;VALUE=DATE:20260903\r\n"))
        assertTrue(encoded.contains("SUMMARY:Away\\, all day\r\n"))
    }

    @Test fun parsesCompletedUndatedTask() {
        val raw = """BEGIN:VCALENDAR
BEGIN:VTODO
UID:task-1
SUMMARY:Archive invoice
STATUS:COMPLETED
COMPLETED:20260902T120000Z
END:VTODO
END:VCALENDAR"""
        val task = IcalendarCodec.parse("tasks", "/task.ics", "tag", raw).tasks.single()
        assertEquals("Archive invoice", task.title)
        assertEquals(Instant.parse("2026-09-02T12:00:00Z").toEpochMilli(), task.completedAt)
        assertEquals(null, task.dueEpochMillis)
    }

    @Test fun offlinePatchPreservesAlarmAndUnknownProperties() {
        val raw = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:keep
DTSTART:20260902T080000Z
DTEND:20260902T090000Z
SUMMARY:Old
X-CUSTOM:keep-me
BEGIN:VALARM
TRIGGER:-PT10M
ACTION:DISPLAY
END:VALARM
END:VEVENT
END:VCALENDAR"""
        val event = IcalendarCodec.parse("cal", "/keep.ics", "tag", raw).events.single().copy(title = "New")
        val patched = IcalendarCodec.patchEvent(raw, null, event)
        assertTrue(patched.contains("SUMMARY:New"))
        assertTrue(patched.contains("X-CUSTOM:keep-me"))
        assertTrue(patched.contains("BEGIN:VALARM"))
    }

    @Test fun offlinePatchPreservesTzidAndLocalWallClock() {
        val raw = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:berlin
DTSTART;TZID=Europe/Berlin:20261020T090000
DTEND;TZID=Europe/Berlin:20261020T100000
SUMMARY:Old
END:VEVENT
END:VCALENDAR"""
        val original = IcalendarCodec.parse("cal", "/berlin.ics", "tag", raw).events.single()
        val movedOneHour = original.copy(
            startEpochMillis = original.startEpochMillis + 3_600_000,
            endEpochMillis = original.endEpochMillis + 3_600_000
        )
        val patched = IcalendarCodec.patchEvent(raw, null, movedOneHour)
        assertTrue(patched.contains("DTSTART;TZID=Europe/Berlin:20261020T100000"))
        assertTrue(patched.contains("DTEND;TZID=Europe/Berlin:20261020T110000"))
    }

    @Test fun upsertsAndCancelsOneRecurringOccurrence() {
        val raw = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:series
DTSTART;TZID=Europe/Berlin:20260902T090000
DTEND;TZID=Europe/Berlin:20260902T100000
RRULE:FREQ=WEEKLY;COUNT=2
SUMMARY:Series
END:VEVENT
END:VCALENDAR"""
        val zone = ZoneId.of("Europe/Berlin")
        val master = IcalendarCodec.parse("cal", "/series.ics", "tag", raw).events.single()
        val original = java.time.ZonedDateTime.of(2026, 9, 9, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val changed = master.copy(
            title = "Only this one",
            startEpochMillis = original + 3_600_000,
            endEpochMillis = original + 7_200_000,
            recurrenceRule = null
        )
        val (edited, _) = IcalendarCodec.upsertEventException(raw, master, original, changed)
        val editedProjection = RecurrenceProjector.project(
            "cal",
            "/series.ics",
            edited,
            Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-09-20T00:00:00Z").toEpochMilli(),
            zone
        )
        assertEquals(listOf("Series", "Only this one"), editedProjection.map { it.title })

        val (cancelled, _) = IcalendarCodec.upsertEventException(edited, master.copy(rawIcal = edited), original, changed, true)
        val cancelledProjection = RecurrenceProjector.project(
            "cal",
            "/series.ics",
            cancelled,
            Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-09-20T00:00:00Z").toEpochMilli(),
            zone
        )
        assertEquals(1, cancelledProjection.size)
    }
}

package de.tobisk.inkdav.tasks

import de.tobisk.inkdav.dav.IcalendarCodec
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecurringTaskProjectorTest {
    private val raw = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//EN
BEGIN:VTODO
UID:repeat
DTSTART;TZID=Europe/Berlin:20260902T090000
DUE;TZID=Europe/Berlin:20260902T090000
RRULE:FREQ=DAILY;COUNT=2
SUMMARY:Water plants
STATUS:NEEDS-ACTION
END:VTODO
END:VCALENDAR"""

    @Test fun completionProjectsTheNextIncompleteOccurrence() {
        val zone = ZoneId.of("Europe/Berlin")
        val task = IcalendarCodec.parse("tasks", "/repeat.ics", "e1", raw).tasks.single()
        val first = requireNotNull(RecurringTaskProjector.next(task, zone))
        val completed = IcalendarCodec.completeRecurringTask(raw, task, requireNotNull(first.dueEpochMillis), Instant.parse("2026-09-02T08:30:00Z").toEpochMilli())
        val reparsed = IcalendarCodec.parse("tasks", "/repeat.ics", "e2", completed).tasks.single()
        val next = requireNotNull(RecurringTaskProjector.next(reparsed, zone))

        assertEquals(3, Instant.ofEpochMilli(requireNotNull(next.dueEpochMillis)).atZone(zone).dayOfMonth)
    }

    @Test fun completedFiniteSeriesDisappears() {
        val zone = ZoneId.of("Europe/Berlin")
        val task = IcalendarCodec.parse("tasks", "/repeat.ics", "e1", raw).tasks.single()
        val first = requireNotNull(RecurringTaskProjector.next(task, zone))
        val once = IcalendarCodec.completeRecurringTask(raw, task, requireNotNull(first.dueEpochMillis), first.dueEpochMillis!!)
        val afterFirst = IcalendarCodec.parse("tasks", "/repeat.ics", "e2", once).tasks.single()
        val second = requireNotNull(RecurringTaskProjector.next(afterFirst, zone))
        val twice = IcalendarCodec.completeRecurringTask(once, task, requireNotNull(second.dueEpochMillis), second.dueEpochMillis!!)
        val afterSecond = IcalendarCodec.parse("tasks", "/repeat.ics", "e3", twice).tasks.single()

        assertNull(RecurringTaskProjector.next(afterSecond, zone))
    }
}

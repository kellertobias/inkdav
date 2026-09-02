package de.tobisk.inkdav.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarTimeMathTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `all-day end stays at local midnight across spring DST`() {
        val start = LocalDate.of(2026, 3, 29).atStartOfDay(berlin).toInstant().toEpochMilli()
        val end = CalendarTimeMath.nextLocalDay(start, berlin)

        assertEquals(LocalDate.of(2026, 3, 30), java.time.Instant.ofEpochMilli(end).atZone(berlin).toLocalDate())
        assertEquals(LocalTime.MIDNIGHT, java.time.Instant.ofEpochMilli(end).atZone(berlin).toLocalTime())
        assertEquals(23L, (end - start) / 3_600_000)
    }

    @Test
    fun `day shift preserves wall clock across autumn DST`() {
        val start = LocalDate.of(2026, 10, 24).atTime(9, 30).atZone(berlin).toInstant().toEpochMilli()
        val shifted = java.time.Instant.ofEpochMilli(CalendarTimeMath.shiftDays(start, 1, berlin)).atZone(berlin)

        assertEquals(LocalDate.of(2026, 10, 25), shifted.toLocalDate())
        assertEquals(LocalTime.of(9, 30), shifted.toLocalTime())
    }
}

package de.tobisk.inkdav.ui

import de.tobisk.inkdav.CalendarMode
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarLayoutTest {
    private val thursday = LocalDate.of(2026, 9, 3)

    @Test
    fun portraitWeekShowsSelectedDayAndNextTwoDays() {
        assertEquals(
            listOf(thursday, thursday.plusDays(1), thursday.plusDays(2)),
            displayedWeekDays(thursday, isPortrait = true)
        )
    }

    @Test
    fun landscapeWeekShowsMondayThroughSunday() {
        assertEquals(
            (0L..6L).map { LocalDate.of(2026, 8, 31).plusDays(it) },
            displayedWeekDays(thursday, isPortrait = false)
        )
    }

    @Test
    fun weekNavigationMatchesVisibleWindow() {
        assertEquals(thursday.plusDays(1), stepDate(thursday, CalendarMode.WEEK, 1, isPortrait = true))
        assertEquals(thursday.minusWeeks(1), stepDate(thursday, CalendarMode.WEEK, -1, isPortrait = false))
    }

    @Test
    fun september2026UsesOnlyFiveCalendarRows() {
        assertEquals(5, monthWeekCount(LocalDate.of(2026, 9, 1)))
    }

    @Test
    fun monthGridStillSupportsFourAndSixWeekMonths() {
        assertEquals(4, monthWeekCount(LocalDate.of(2021, 2, 1)))
        assertEquals(6, monthWeekCount(LocalDate.of(2026, 8, 1)))
    }
}

package de.tobisk.inkdav.ui

import androidx.compose.ui.graphics.Color
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
    fun weekHeaderHasAnExplicitBoundedHeight() {
        assertEquals(62f, weekAllDayHeaderHeightDp(0))
        assertEquals(102f, weekAllDayHeaderHeightDp(2))
        assertEquals(182f, weekAllDayHeaderHeightDp(20))
    }

    @Test
    fun portraitWeekAlwaysShowsTheFullDay() {
        assertEquals(0..23, visibleWeekHours(isPortrait = true, landscapeStartHour = 8, landscapeEndHour = 24))
    }

    @Test
    fun landscapeWeekUsesConfiguredHours() {
        assertEquals(8..23, visibleWeekHours(isPortrait = false, landscapeStartHour = 8, landscapeEndHour = 24))
        assertEquals(6..21, visibleWeekHours(isPortrait = false, landscapeStartHour = 6, landscapeEndHour = 22))
    }

    @Test
    fun overlappingWeekEventsUseSeparateLanes() {
        assertEquals(
            listOf(
                WeekEventLane("a", lane = 0, laneCount = 2),
                WeekEventLane("b", lane = 1, laneCount = 2),
                WeekEventLane("c", lane = 0, laneCount = 1)
            ),
            weekEventLanes(
                listOf(
                    WeekEventInterval("a", start = 100, end = 300),
                    WeekEventInterval("b", start = 200, end = 250),
                    WeekEventInterval("c", start = 300, end = 400)
                )
            )
        )
    }

    @Test
    fun weekEventTextUsesTheHigherContrastBlackOrWhite() {
        assertEquals(Color.White, contrastingTextColor(Color(0xff243b53)))
        assertEquals(Color.Black, contrastingTextColor(Color(0xffffd54f)))
        assertEquals(Color.Black, contrastingTextColor(Color(0x20336699)))
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

    @Test
    fun monthEventsUseAllAvailableCellHeight() {
        assertEquals(MonthEventAllocation(8, 0), monthEventAllocation(8, 200f))
        assertEquals(MonthEventAllocation(1, 4), monthEventAllocation(5, 80f))
    }
}

package de.tobisk.inkdav.data

import java.time.Instant
import java.time.ZoneId

object CalendarTimeMath {
    fun shiftDays(epochMillis: Long, days: Long, zone: ZoneId = ZoneId.systemDefault()): Long = Instant.ofEpochMilli(epochMillis).atZone(zone).plusDays(days).toInstant().toEpochMilli()

    fun nextLocalDay(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long = shiftDays(epochMillis, 1, zone)
}

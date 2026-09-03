package de.tobisk.inkdav.dav

import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules
import java.util.concurrent.ConcurrentHashMap
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.TimeZone as IcalTimeZone
import net.fortuna.ical4j.model.TimeZoneRegistry

/**
 * Uses Android's built-in zone database instead of iCal4j's custom ZoneRulesProvider.
 *
 * Android exposes ZoneRulesProvider but does not expose its constructor to subclasses. iCal4j's
 * default registry therefore fails as soon as a downloaded calendar contains VTIMEZONE. DAV
 * servers normally use IANA TZIDs, which can be resolved directly by java.time on Android.
 */
internal class PlatformTimeZoneRegistry : TimeZoneRegistry {
    private val timeZones = ConcurrentHashMap<String, IcalTimeZone>()
    private val zoneIds = ConcurrentHashMap<String, ZoneId>()

    override fun register(timeZone: IcalTimeZone) = register(timeZone, false)

    override fun register(timeZone: IcalTimeZone, update: Boolean) {
        val id = timeZone.id
        if (update) {
            timeZones[id] = timeZone
        } else {
            timeZones.putIfAbsent(id, timeZone)
        }
        zoneIds.putIfAbsent(id, resolve(id, timeZone.rawOffset))
    }

    override fun clear() {
        timeZones.clear()
        zoneIds.clear()
    }

    override fun getTimeZone(id: String): IcalTimeZone? = timeZones[id]

    override fun getZoneRules(): Map<String, ZoneRules> = zoneIds.mapValues { it.value.rules }

    override fun getZoneId(id: String): ZoneId = zoneIds[id] ?: resolve(id).also { zoneIds[id] = it }

    override fun getTzId(id: String): String? = zoneIds.entries.firstOrNull { it.value.id == id }?.key

    private fun resolve(id: String, rawOffsetMillis: Int? = null): ZoneId {
        val candidates = listOf(id, id.removePrefix("/"))
        candidates.forEach { candidate ->
            try {
                return ZoneId.of(candidate, ZoneId.SHORT_IDS)
            } catch (_: DateTimeException) {
                // Try the next normalized form before using the embedded fixed offset.
            }
        }
        return rawOffsetMillis?.let { ZoneOffset.ofTotalSeconds(it / 1_000) } ?: ZoneOffset.UTC
    }
}

internal fun platformCalendarBuilder(): CalendarBuilder = CalendarBuilder(PlatformTimeZoneRegistry())

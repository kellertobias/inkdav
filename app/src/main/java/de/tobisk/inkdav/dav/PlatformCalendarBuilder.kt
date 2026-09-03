package de.tobisk.inkdav.dav

import java.io.StringReader
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

internal fun parsePlatformCalendar(raw: String): net.fortuna.ical4j.model.Calendar = platformCalendarBuilder().build(
    StringReader(sanitizeForIcal4j(raw))
)

/** Drops invalid bare property parameters while leaving the lossless stored source untouched. */
internal fun sanitizeForIcal4j(raw: String): String {
    val unfolded = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').fold(mutableListOf<String>()) { lines, line ->
        if ((line.startsWith(' ') || line.startsWith('\t')) && lines.isNotEmpty()) {
            lines[lines.lastIndex] += line.drop(1)
        } else {
            lines += line
        }
        lines
    }
    return unfolded.joinToString("\r\n", postfix = "\r\n", transform = ::sanitizePropertyLine)
}

private fun sanitizePropertyLine(line: String): String {
    val colon = delimiterOutsideQuotes(line, ':') ?: return line
    val head = line.substring(0, colon)
    val segments = splitOutsideQuotes(head, ';')
    if (segments.size < 2) return line
    val validHead = buildList {
        add(segments.first())
        addAll(
            segments.drop(1).filter { parameter ->
                val equals = delimiterOutsideQuotes(parameter, '=')
                equals != null && equals > 0
            }
        )
    }.joinToString(";")
    return validHead + line.substring(colon)
}

private fun splitOutsideQuotes(value: String, delimiter: Char): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    while (true) {
        val index = delimiterOutsideQuotes(value, delimiter, start) ?: break
        result += value.substring(start, index)
        start = index + 1
    }
    result += value.substring(start)
    return result
}

private fun delimiterOutsideQuotes(value: String, delimiter: Char, start: Int = 0): Int? {
    var quoted = false
    var escaped = false
    for (index in start until value.length) {
        val character = value[index]
        when {
            escaped -> escaped = false
            character == '\\' -> escaped = true
            character == '"' -> quoted = !quoted
            character == delimiter && !quoted -> return index
        }
    }
    return null
}

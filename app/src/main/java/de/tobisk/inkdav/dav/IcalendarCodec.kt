package de.tobisk.inkdav.dav

import de.tobisk.inkdav.data.CalendarEventEntity
import de.tobisk.inkdav.data.DavTaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Loss-preserving projection codec. Unknown properties remain in rawIcal; writes emit a conservative
 * RFC 5545 VCALENDAR. Recurrence expansion is intentionally a separate concern.
 */
object IcalendarCodec {
    data class Parsed(val events: List<CalendarEventEntity>, val tasks: List<DavTaskEntity>)

    fun parse(collectionId: String, href: String?, etag: String?, input: String): Parsed {
        val lines = unfold(input)
        val events = components(lines, "VEVENT").mapNotNull { fields ->
            val uid = fields.value("UID") ?: return@mapNotNull null
            val start = fields.date("DTSTART") ?: return@mapNotNull null
            val end = fields.date("DTEND") ?: start.copy(epochMillis = start.epochMillis + if (start.allDay) 86_400_000 else 3_600_000)
            CalendarEventEntity(
                id = stableId(collectionId, uid, fields.value("RECURRENCE-ID")),
                collectionId = collectionId, remoteHref = href, uid = uid, etag = etag,
                title = unescape(fields.value("SUMMARY").orEmpty()),
                description = unescape(fields.value("DESCRIPTION").orEmpty()),
                location = unescape(fields.value("LOCATION").orEmpty()),
                startEpochMillis = start.epochMillis, endEpochMillis = end.epochMillis,
                allDay = start.allDay, timezone = start.timezone,
                recurrenceRule = fields.value("RRULE"), recurrenceId = fields.value("RECURRENCE-ID"),
                rawIcal = input
            )
        }
        val tasks = components(lines, "VTODO").mapNotNull { fields ->
            val uid = fields.value("UID") ?: return@mapNotNull null
            DavTaskEntity(
                id = stableId(collectionId, uid, null), collectionId = collectionId,
                remoteHref = href, uid = uid, etag = etag,
                title = unescape(fields.value("SUMMARY").orEmpty()),
                notes = unescape(fields.value("DESCRIPTION").orEmpty()),
                dueEpochMillis = fields.date("DUE")?.epochMillis,
                startEpochMillis = fields.date("DTSTART")?.epochMillis,
                completedAt = fields.date("COMPLETED")?.epochMillis,
                priority = fields.value("PRIORITY")?.toIntOrNull() ?: 0,
                recurrenceRule = fields.value("RRULE"), rawIcal = input
            )
        }
        return Parsed(events, tasks)
    }

    fun encode(event: CalendarEventEntity): String = buildString {
        appendHeader()
        append("BEGIN:VEVENT\r\nUID:").append(escape(event.uid)).append("\r\n")
        append("DTSTAMP:").append(utc(System.currentTimeMillis())).append("\r\n")
        if (event.allDay) {
            append("DTSTART;VALUE=DATE:").append(day(event.startEpochMillis)).append("\r\n")
            append("DTEND;VALUE=DATE:").append(day(event.endEpochMillis)).append("\r\n")
        } else {
            append("DTSTART:").append(utc(event.startEpochMillis)).append("\r\n")
            append("DTEND:").append(utc(event.endEpochMillis)).append("\r\n")
        }
        append("SUMMARY:").append(escape(event.title)).append("\r\n")
        optional("DESCRIPTION", event.description)
        optional("LOCATION", event.location)
        event.recurrenceRule?.let { append("RRULE:").append(it).append("\r\n") }
        append("END:VEVENT\r\nEND:VCALENDAR\r\n")
    }

    fun encode(task: DavTaskEntity): String = buildString {
        appendHeader()
        append("BEGIN:VTODO\r\nUID:").append(escape(task.uid)).append("\r\n")
        append("DTSTAMP:").append(utc(System.currentTimeMillis())).append("\r\n")
        append("SUMMARY:").append(escape(task.title)).append("\r\n")
        optional("DESCRIPTION", task.notes)
        task.dueEpochMillis?.let { append("DUE:").append(utc(it)).append("\r\n") }
        if (task.completedAt != null) {
            append("STATUS:COMPLETED\r\nCOMPLETED:").append(utc(task.completedAt)).append("\r\n")
        } else {
            append("STATUS:NEEDS-ACTION\r\n")
        }
        task.recurrenceRule?.let { append("RRULE:").append(it).append("\r\n") }
        append("END:VTODO\r\nEND:VCALENDAR\r\n")
    }

    fun patchEvent(raw: String, recurrenceId: String?, event: CalendarEventEntity): String {
        val replacements = linkedMapOf<String, String?>(
            "DTSTART" to dateLine("DTSTART", event.startEpochMillis, event.allDay),
            "DTEND" to dateLine("DTEND", event.endEpochMillis, event.allDay),
            "SUMMARY" to "SUMMARY:${escape(event.title)}",
            "DESCRIPTION" to event.description.takeIf(String::isNotBlank)?.let { "DESCRIPTION:${escape(it)}" },
            "LOCATION" to event.location.takeIf(String::isNotBlank)?.let { "LOCATION:${escape(it)}" }
        )
        return patchComponent(
            raw,
            "VEVENT",
            recurrenceId,
            replacements,
            mapOf(
                "DTSTART" to DatePatch(event.startEpochMillis, event.allDay, event.timezone),
                "DTEND" to DatePatch(event.endEpochMillis, event.allDay, event.timezone)
            )
        )
    }

    fun patchTask(raw: String, task: DavTaskEntity): String = patchComponent(
        raw,
        "VTODO",
        null,
        linkedMapOf(
            "SUMMARY" to "SUMMARY:${escape(task.title)}",
            "DESCRIPTION" to task.notes.takeIf(String::isNotBlank)?.let { "DESCRIPTION:${escape(it)}" },
            "DUE" to task.dueEpochMillis?.let { "DUE:${utc(it)}" },
            "STATUS" to if (task.completedAt == null) "STATUS:NEEDS-ACTION" else "STATUS:COMPLETED",
            "COMPLETED" to task.completedAt?.let { "COMPLETED:${utc(it)}" }
        )
    )

    private data class DatePatch(val epochMillis: Long, val allDay: Boolean, val timezone: String?)

    private fun patchComponent(
        raw: String,
        component: String,
        recurrenceId: String?,
        replacements: Map<String, String?>,
        datePatches: Map<String, DatePatch> = emptyMap()
    ): String {
        val lines = unfold(raw).toMutableList()
        var start = -1
        var end = -1
        var candidate = -1
        lines.forEachIndexed { index, line ->
            if (line == "BEGIN:$component") candidate = index
            if (candidate >= 0 && line == "END:$component") {
                val block = lines.subList(candidate, index + 1)
                val blockRecurrence = block.firstOrNull {
                    it.substringBefore(';').substringBefore(':').equals("RECURRENCE-ID", true)
                }?.substringAfter(':')
                if ((recurrenceId == null && blockRecurrence == null) || recurrenceId == blockRecurrence) {
                    start = candidate
                    end = index
                }
                candidate = -1
            }
        }
        if (start < 0) return raw
        replacements.forEach { (name, replacement) ->
            val index = (start + 1 until end).firstOrNull { lines[it].substringBefore(';').substringBefore(':').equals(name, true) }
            val effectiveReplacement = datePatches[name]?.let { dateLine(name, it, index?.let(lines::get)) } ?: replacement
            if (index != null) {
                if (effectiveReplacement == null) {
                    lines.removeAt(index)
                    end--
                } else {
                    lines[index] = effectiveReplacement
                }
            } else if (effectiveReplacement != null) {
                lines.add(end, effectiveReplacement)
                end++
            }
        }
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun dateLine(name: String, epoch: Long, allDay: Boolean) = if (allDay) "$name;VALUE=DATE:${day(epoch)}" else "$name:${utc(epoch)}"

    private fun dateLine(name: String, patch: DatePatch, original: String?): String {
        val zone = runCatching { patch.timezone?.let(ZoneId::of) }.getOrNull() ?: ZoneId.systemDefault()
        if (patch.allDay) {
            val value = Instant.ofEpochMilli(patch.epochMillis).atZone(zone).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
            return "$name;VALUE=DATE:$value"
        }
        val originalHead = original?.substringBefore(':')
        val originalValue = original?.substringAfter(':', "")
        val originalTimezone = originalHead?.let { Regex("(?:^|;)TZID=([^;:]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        if (originalTimezone != null) {
            val originalZone = runCatching { ZoneId.of(originalTimezone) }.getOrDefault(zone)
            val value = DateTimeFormatter.ofPattern(
                "yyyyMMdd'T'HHmmss"
            ).format(Instant.ofEpochMilli(patch.epochMillis).atZone(originalZone))
            return "$originalHead:$value"
        }
        if (originalValue?.endsWith("Z") == false && originalHead != null) {
            val value = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").format(Instant.ofEpochMilli(patch.epochMillis).atZone(zone))
            return "$originalHead:$value"
        }
        return "$name:${utc(patch.epochMillis)}"
    }

    private data class DateValue(val epochMillis: Long, val allDay: Boolean, val timezone: String?)
    private data class Field(val key: String, val parameters: String, val content: String)

    private fun components(lines: List<String>, name: String): List<List<Field>> {
        val result = mutableListOf<List<Field>>()
        var current: MutableList<Field>? = null
        lines.forEach { line ->
            if (line == "BEGIN:$name") {
                current = mutableListOf()
            } else if (line == "END:$name") {
                current?.let(result::add).also { current = null }
            } else {
                current?.let { target -> parseField(line)?.let(target::add) }
            }
        }
        return result
    }

    private fun unfold(input: String): List<String> = input.replace("\r\n", "\n").split('\n').fold(mutableListOf()) { out, line ->
        if ((line.startsWith(' ') || line.startsWith('\t')) && out.isNotEmpty()) {
            out[out.lastIndex] += line.drop(1)
        } else {
            out += line.trimEnd('\r')
        }
        out
    }

    private fun parseField(line: String): Field? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val semicolon = head.indexOf(';')
        return Field(
            key = (if (semicolon < 0) head else head.substring(0, semicolon)).uppercase(),
            parameters = if (semicolon < 0) "" else head.substring(semicolon + 1),
            content = line.substring(colon + 1)
        )
    }

    private fun List<Field>.value(key: String) = firstOrNull { it.key == key }?.content
    private fun List<Field>.date(key: String): DateValue? = firstOrNull { it.key == key }?.let { field ->
        runCatching {
            val isDate = field.parameters.contains("VALUE=DATE", true) || field.content.length == 8
            val timezone = Regex("(?:^|;)TZID=([^;:]+)", RegexOption.IGNORE_CASE).find(field.parameters)?.groupValues?.get(1)
            val instant = when {
                isDate -> LocalDate.parse(
                    field.content.take(8),
                    DateTimeFormatter.BASIC_ISO_DATE
                ).atStartOfDay(ZoneId.systemDefault()).toInstant()
                field.content.endsWith(
                    "Z"
                ) -> Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX").withZone(ZoneOffset.UTC).parse(field.content))
                else -> LocalDateTime.parse(field.content, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(
                    timezone?.let(ZoneId::of) ?: ZoneId.systemDefault()
                ).toInstant()
            }
            DateValue(instant.toEpochMilli(), isDate, timezone)
        }.getOrNull()
    }

    fun stableId(collectionId: String, uid: String, recurrenceId: String? = null): String = UUID.nameUUIDFromBytes("$collectionId|$uid|$recurrenceId".encodeToByteArray()).toString()
    private fun StringBuilder.appendHeader() {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//InkDAV//Android//EN\r\n")
    }
    private fun StringBuilder.optional(name: String, value: String) {
        if (value.isNotBlank()) append(name).append(':').append(escape(value)).append("\r\n")
    }
    private fun utc(epoch: Long) = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(epoch))
    private fun day(epoch: Long) = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(epoch))
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    private fun unescape(value: String) = value.replace("\\n", "\n", true).replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}

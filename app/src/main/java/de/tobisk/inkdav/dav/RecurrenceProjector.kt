package de.tobisk.inkdav.dav

import de.tobisk.inkdav.data.CalendarOccurrenceEntity
import de.tobisk.inkdav.data.SyncStatus
import java.io.StringReader
import java.time.*
import java.time.temporal.Temporal
import java.util.UUID
import net.fortuna.ical4j.model.Period
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Uid

/** Builds a bounded, deterministic display projection while retaining the original VCALENDAR. */
object RecurrenceProjector {
    private const val MAX_OCCURRENCES = 10_000

    fun project(
        collectionId: String,
        href: String?,
        raw: String,
        windowStartMillis: Long,
        windowEndMillis: Long,
        displayZone: ZoneId,
        syncStatus: SyncStatus = SyncStatus.CLEAN
    ): List<CalendarOccurrenceEntity> {
        val calendar = platformCalendarBuilder().build(StringReader(raw))
        val components = calendar.getComponents<VEvent>()
        val result = linkedMapOf<String, CalendarOccurrenceEntity>()
        val masters = components.filter { it.getProperty<RecurrenceId<*>>(Property.RECURRENCE_ID).isEmpty }
        val exceptions = components.filter { it.getProperty<RecurrenceId<*>>(Property.RECURRENCE_ID).isPresent }

        masters.forEach { event ->
            if (cancelled(event)) return@forEach
            consumedPeriods(event, windowStartMillis, windowEndMillis, displayZone).forEach { occurrencePeriod ->
                if (result.size >= MAX_OCCURRENCES) error("Recurrence expansion exceeds $MAX_OCCURRENCES instances")
                val original = occurrencePeriod.first
                if (occurrencePeriod.second > windowStartMillis && occurrencePeriod.first < windowEndMillis) {
                    result[key(event, original)] =
                        occurrence(event, collectionId, href, original, occurrencePeriod.first, occurrencePeriod.second, displayZone, false, syncStatus)
                }
            }
        }

        exceptions.filter(::thisAndFuture).sortedBy {
            epoch(it.getProperty<RecurrenceId<*>>(Property.RECURRENCE_ID).orElseThrow().date, displayZone)
        }.forEach { event ->
            applyThisAndFuture(result, event, collectionId, href, displayZone, syncStatus)
        }

        exceptions.forEach { event ->
            val recurrenceTemporal = event.getProperty<RecurrenceId<*>>(Property.RECURRENCE_ID).orElseThrow().date
            val original = epoch(recurrenceTemporal, displayZone)
            val key = key(event, original)
            if (cancelled(event)) {
                result.remove(key)
            } else {
                singlePeriod(event, displayZone)?.let { period ->
                    if (period.second > windowStartMillis && period.first < windowEndMillis) {
                        result[key] =
                            occurrence(event, collectionId, href, original, period.first, period.second, displayZone, true, syncStatus)
                    }
                }
            }
        }
        return result.values.sortedBy(CalendarOccurrenceEntity::startEpochMillis)
    }

    private fun applyThisAndFuture(
        result: MutableMap<String, CalendarOccurrenceEntity>,
        event: VEvent,
        collectionId: String,
        href: String?,
        zone: ZoneId,
        status: SyncStatus
    ) {
        val recurrence = event.getProperty<RecurrenceId<*>>(Property.RECURRENCE_ID).orElseThrow()
        val boundary = epoch(recurrence.date, zone)
        val uid = event.getProperty<Uid>(Property.UID).orElseThrow().value
        val affected = result.values.filter { it.uid == uid && it.originalStartEpochMillis >= boundary }
        if (cancelled(event)) {
            affected.forEach { result.remove(key(event, it.originalStartEpochMillis)) }
            return
        }
        val changedPeriod = singlePeriod(event, zone) ?: return
        val offset = changedPeriod.first - boundary
        val duration = (changedPeriod.second - changedPeriod.first).coerceAtLeast(0)
        affected.forEach { existing ->
            val shiftedStart = existing.startEpochMillis + offset
            result[key(event, existing.originalStartEpochMillis)] = occurrence(
                event,
                collectionId,
                href,
                existing.originalStartEpochMillis,
                shiftedStart,
                shiftedStart + duration,
                zone,
                true,
                status
            )
        }
    }

    private fun consumedPeriods(event: VEvent, start: Long, end: Long, zone: ZoneId): List<Pair<Long, Long>> {
        val temporal = event.getStartDate<Temporal>().orElseThrow().date
        val periods = when (temporal) {
            is LocalDate -> event.getConsumedTime(
                Period(
                    Instant.ofEpochMilli(start).atZone(zone).toLocalDate(),
                    Instant.ofEpochMilli(end).atZone(zone).toLocalDate().plusDays(1)
                ),
                true
            )
            is LocalDateTime -> event.getConsumedTime(
                Period(
                    Instant.ofEpochMilli(start).atZone(zone).toLocalDateTime(),
                    Instant.ofEpochMilli(end).atZone(zone).toLocalDateTime()
                ),
                true
            )
            is ZonedDateTime -> event.getConsumedTime(
                Period(Instant.ofEpochMilli(start).atZone(temporal.zone), Instant.ofEpochMilli(end).atZone(temporal.zone)),
                true
            )
            is OffsetDateTime -> event.getConsumedTime(
                Period(Instant.ofEpochMilli(start).atOffset(temporal.offset), Instant.ofEpochMilli(end).atOffset(temporal.offset)),
                true
            )
            is Instant -> event.getConsumedTime(Period(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end)), true)
            else -> error("Unsupported DTSTART temporal ${temporal::class.java.simpleName}")
        }
        return periods.map { epoch(it.start, zone) to epoch(it.end, zone) }
    }

    private fun singlePeriod(event: VEvent, zone: ZoneId): Pair<Long, Long>? {
        val start = event.getStartDate<Temporal>().orElse(null)?.date ?: return null
        val startEpoch = epoch(start, zone)
        val end = event.getEndDate<Temporal>().orElse(null)?.date
        return startEpoch to (end?.let { epoch(it, zone) } ?: (startEpoch + if (start is LocalDate) 86_400_000L else 3_600_000L))
    }

    private fun occurrence(
        event: VEvent,
        collectionId: String,
        href: String?,
        original: Long,
        start: Long,
        end: Long,
        zone: ZoneId,
        exception: Boolean,
        status: SyncStatus
    ): CalendarOccurrenceEntity {
        val uid = event.getProperty<Uid>(Property.UID).orElseThrow().value
        val sourceId = IcalendarCodec.stableId(
            collectionId,
            uid,
            event.getProperty<RecurrenceId<*>>(Property.RECURRENCE_ID).map {
                it.value
            }.orElse(null)
        )
        return CalendarOccurrenceEntity(
            id = UUID.nameUUIDFromBytes("$collectionId|$uid|$original".encodeToByteArray()).toString(),
            sourceEventId = sourceId, collectionId = collectionId, remoteHref = href, uid = uid,
            title = event.summary?.value.orEmpty(), description = event.description?.value.orEmpty(), location = event.location?.value.orEmpty(),
            startEpochMillis = start, endEpochMillis = end, originalStartEpochMillis = original,
            allDay = event.getStartDate<Temporal>().orElseThrow().date is LocalDate, isException = exception, status = status
        )
    }

    private fun key(event: VEvent, original: Long): String = event.getProperty<Uid>(Property.UID).orElseThrow().value + "|" + original
    private fun thisAndFuture(event: VEvent): Boolean = event.getProperty<RecurrenceId<*>>(Property.RECURRENCE_ID)
        .flatMap { it.getParameter<net.fortuna.ical4j.model.Parameter>("RANGE") }
        .map { it.value.equals("THISANDFUTURE", true) }
        .orElse(false)
    private fun cancelled(event: VEvent) = event.getProperty<Property>(Property.STATUS).map { it.value.equals("CANCELLED", true) }.orElse(false)
    private fun epoch(temporal: Temporal, zone: ZoneId): Long = when (temporal) {
        is Instant -> temporal.toEpochMilli()
        is ZonedDateTime -> temporal.toInstant().toEpochMilli()
        is OffsetDateTime -> temporal.toInstant().toEpochMilli()
        is LocalDateTime -> temporal.atZone(zone).toInstant().toEpochMilli()
        is LocalDate -> temporal.atStartOfDay(zone).toInstant().toEpochMilli()
        else -> error("Unsupported temporal ${temporal::class.java.simpleName}")
    }
}

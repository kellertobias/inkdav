package de.tobisk.inkdav.tasks

import de.tobisk.inkdav.data.DavTaskEntity
import de.tobisk.inkdav.dav.platformCalendarBuilder
import java.io.StringReader
import java.time.*
import java.time.temporal.Temporal
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.component.VToDo
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId

/** Projects a recurring VTODO to its next incomplete instance, matching reminder-list semantics. */
object RecurringTaskProjector {
    private const val MAX_INSTANCES = 10_000

    fun next(task: DavTaskEntity, displayZone: ZoneId = ZoneId.systemDefault()): DavTaskEntity? {
        if (task.recurrenceRule == null || task.rawIcal == null) return task
        val calendar = platformCalendarBuilder().build(StringReader(task.rawIcal))
        val todos = calendar.componentList.getComponents<VToDo>("VTODO")
        val master = todos.firstOrNull { it.getProperty<RecurrenceId<*>>(Property.RECURRENCE_ID).isEmpty } ?: return task
        val seed = master.getDue<Temporal>().map { it.date }.orElseGet {
            master.getStartDate<Temporal>().map { it.date }.orElse(null)
        } ?: return task
        val rule = master.getProperty<RRule<*>>(Property.RRULE).orElse(null)?.recur ?: return task
        val completed = todos.mapNotNull { todo ->
            if (!todo.getProperty<Property>(Property.STATUS).map { it.value.equals("COMPLETED", true) }.orElse(false)) return@mapNotNull null
            todo.getProperty<RecurrenceId<*>>(Property.RECURRENCE_ID).map { epoch(it.date, displayZone) }.orElse(null)
        }.toSet()
        val next = dates(seed, rule).firstOrNull { epoch(it, displayZone) !in completed } ?: return null
        return task.copy(dueEpochMillis = epoch(next, displayZone), completedAt = null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun dates(seed: Temporal, rule: Recur<*>): List<Temporal> = when (seed) {
        is LocalDate -> (rule as Recur<LocalDate>).getDates(seed, seed.minusDays(1), seed.plusYears(100), MAX_INSTANCES)
        is LocalDateTime -> (rule as Recur<LocalDateTime>).getDates(seed, seed.minusNanos(1), seed.plusYears(100), MAX_INSTANCES)
        is ZonedDateTime -> (rule as Recur<ZonedDateTime>).getDates(seed, seed.minusNanos(1), seed.plusYears(100), MAX_INSTANCES)
        is OffsetDateTime -> (rule as Recur<OffsetDateTime>).getDates(seed, seed.minusNanos(1), seed.plusYears(100), MAX_INSTANCES)
        is Instant -> (rule as Recur<Instant>).getDates(seed, seed.minusNanos(1), seed.plus(Duration.ofDays(36_525)), MAX_INSTANCES)
        else -> error("Unsupported recurring task temporal ${seed::class.java.simpleName}")
    }

    private fun epoch(temporal: Temporal, zone: ZoneId): Long = when (temporal) {
        is Instant -> temporal.toEpochMilli()
        is ZonedDateTime -> temporal.toInstant().toEpochMilli()
        is OffsetDateTime -> temporal.toInstant().toEpochMilli()
        is LocalDateTime -> temporal.atZone(zone).toInstant().toEpochMilli()
        is LocalDate -> temporal.atStartOfDay(zone).toInstant().toEpochMilli()
        else -> error("Unsupported temporal ${temporal::class.java.simpleName}")
    }
}

package de.tobisk.inkdav.tasks

import de.tobisk.inkdav.data.DavTaskEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class TaskBucket(val key: String, val title: String, val tasks: List<DavTaskEntity>)

object ScheduleBucketer {
    fun bucket(tasks: List<DavTaskEntity>, today: LocalDate, zone: ZoneId): List<TaskBucket> {
        val dated = tasks.filter { it.completedAt == null }
        val weekStart = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val buckets = linkedMapOf<String, Pair<String, MutableList<DavTaskEntity>>>()
        fun add(key: String, title: String, task: DavTaskEntity) = buckets.getOrPut(key) { title to mutableListOf() }.second.add(task)

        dated.forEach { task ->
            val due = task.dueEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            when {
                due == null -> add("none", "No date", task)
                due.isBefore(today) -> add("overdue", "Overdue", task)
                due == today -> add("today", "Today", task)
                due == today.plusDays(1) -> add("tomorrow", "Tomorrow", task)
                due.isBefore(today.plusDays(7)) -> add("day:$due", due.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase), task)
                !due.isBefore(weekStart) && due.isBefore(weekStart.plusWeeks(1)) -> add("next-week", "Next week", task)
                !due.isBefore(weekStart.plusWeeks(1)) && due.isBefore(weekStart.plusWeeks(2)) -> add("two-weeks", "In 2 weeks", task)
                !due.isBefore(weekStart.plusWeeks(2)) && due.isBefore(weekStart.plusWeeks(3)) -> add("three-weeks", "In 3 weeks", task)
                due.year == today.plusMonths(1).year && due.month == today.plusMonths(1).month -> add("next-month", "Next month", task)
                else -> add("later", "Later", task)
            }
        }
        val order = listOf("overdue", "today", "tomorrow") +
            (2L..6L).map { "day:${today.plusDays(it)}" } +
            listOf("next-week", "two-weeks", "three-weeks", "next-month", "later", "none")
        return order.mapNotNull { key ->
            buckets[key]?.let { TaskBucket(key, it.first, it.second.sortedBy(DavTaskEntity::dueEpochMillis)) }
        }
    }
}

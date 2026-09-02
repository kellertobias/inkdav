package de.tobisk.inkdav.tasks

import de.tobisk.inkdav.data.DavTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ScheduleBucketerTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 9, 2)

    @Test fun createsExplicitAppleRemindersStyleScheduleBuckets() {
        val tasks = listOf(
            task("overdue", today.minusDays(1)), task("today", today), task("tomorrow", today.plusDays(1)),
            task("friday", today.plusDays(2)), task("next-week", today.plusDays(7)),
            task("two-weeks", today.plusDays(14)), task("later", today.plusMonths(3)), task("no-date", null),
        )
        val buckets = ScheduleBucketer.bucket(tasks, today, zone)
        assertEquals(listOf("overdue", "today", "tomorrow", "day:2026-09-04", "next-week", "two-weeks", "later", "none"), buckets.map(TaskBucket::key))
    }

    @Test fun completedTasksDoNotAppearInSchedule() {
        val completed = task("done", today).copy(completedAt = System.currentTimeMillis())
        assertEquals(emptyList<TaskBucket>(), ScheduleBucketer.bucket(listOf(completed), today, zone))
    }

    private fun task(id: String, due: LocalDate?) = DavTaskEntity(
        id = id, collectionId = "list", uid = id, title = id,
        dueEpochMillis = due?.atTime(9, 0)?.atZone(zone)?.toInstant()?.toEpochMilli(),
    )
}


package de.tobisk.inkdav.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import de.tobisk.inkdav.InkDavApplication
import de.tobisk.inkdav.MainActivity
import de.tobisk.inkdav.R
import de.tobisk.inkdav.data.*
import kotlinx.coroutines.runBlocking
import java.time.*
import java.time.format.DateTimeFormatter

object WidgetUpdater {
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        TaskWidgetProvider.update(context, manager, manager.getAppWidgetIds(ComponentName(context, TaskWidgetProvider::class.java)))
        CalendarWidgetProvider.update(context, manager, manager.getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java)))
    }
}

class TaskWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context, manager, intArrayOf(id))
    override fun onDeleted(context: Context, ids: IntArray) { val dao = (context.applicationContext as InkDavApplication).container.database.dao(); runBlocking { ids.forEach { dao.clearTaskWidgetExclusions(it); dao.deleteTaskWidgetConfig(it) } } }

    companion object {
        fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val dao = (context.applicationContext as InkDavApplication).container.database.dao()
            ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val rows = rowCount(options)
                val config = runBlocking { dao.taskWidgetConfig(id) } ?: TaskWidgetConfigEntity(id)
                val exclusions = runBlocking { dao.taskWidgetExclusions(id) }.map { it.collectionId }.ifEmpty { listOf("") }
                val tasks = runBlocking {
                    if (config.mode == TaskWidgetMode.LIST && config.listCollectionId != null) dao.widgetListTasks(config.listCollectionId, rows)
                    else {
                        val end = LocalDate.now().plusDays(config.lookAheadDays.toLong()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        dao.widgetUpcomingTasks(end, exclusions, rows)
                    }
                }
                val root = frame(context, if (config.mode == TaskWidgetMode.LIST) "Tasks · list" else "Tasks · ${config.lookAheadDays} days")
                if (tasks.isEmpty()) root.addView(R.id.widget_rows, row(context, "□", "No matching tasks", ""))
                tasks.take(rows).forEach { task -> root.addView(R.id.widget_rows, row(context, "□", task.title, task.dueEpochMillis?.let(::formatTime).orEmpty())) }
                manager.updateAppWidget(id, root)
            }
        }
    }
}

class CalendarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context, manager, intArrayOf(id))

    companion object {
        fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val dao = (context.applicationContext as InkDavApplication).container.database.dao()
            ids.forEach { id ->
                val rows = rowCount(manager.getAppWidgetOptions(id))
                val events = runBlocking { dao.upcomingOccurrences(System.currentTimeMillis(), rows) }
                val root = frame(context, "Next events")
                if (events.isEmpty()) root.addView(R.id.widget_rows, row(context, "□", "No upcoming events", ""))
                events.forEach { event -> root.addView(R.id.widget_rows, row(context, "▌", event.title, formatTime(event.startEpochMillis))) }
                manager.updateAppWidget(id, root)
            }
        }
    }
}

private fun frame(context: Context, title: String) = RemoteViews(context.packageName, R.layout.widget_frame).apply {
    setTextViewText(R.id.widget_title, title); removeAllViews(R.id.widget_rows)
    val intent = Intent(context, MainActivity::class.java)
    setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, title.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
}
private fun row(context: Context, mark: String, primary: String, secondary: String) = RemoteViews(context.packageName, R.layout.widget_row).apply {
    setTextViewText(R.id.widget_mark, mark); setTextViewText(R.id.widget_primary, primary); setTextViewText(R.id.widget_secondary, secondary); setViewVisibility(R.id.widget_secondary, if (secondary.isBlank()) View.GONE else View.VISIBLE)
}
private fun rowCount(options: Bundle): Int = (((options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180) - 44) / 42)).coerceIn(1, 12)
private fun formatTime(epoch: Long) = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm"))


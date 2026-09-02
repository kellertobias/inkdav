package de.tobisk.inkdav.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import de.tobisk.inkdav.InkDavApplication
import de.tobisk.inkdav.data.*
import kotlinx.coroutines.launch

class TaskWidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val dao = (application as InkDavApplication).container.database.dao()
        setContent {
            val allCollections by dao.observeCollections().collectAsStateWithLifecycle(emptyList())
            val lists = allCollections.filter { it.kind == CollectionKind.TASK_LIST }
            var mode by remember { mutableStateOf(TaskWidgetMode.UPCOMING) }
            var selectedList by remember { mutableStateOf<String?>(null) }
            var days by remember { mutableIntStateOf(7) }
            var excluded by remember { mutableStateOf(setOf<String>()) }
            var loaded by remember { mutableStateOf(false) }
            LaunchedEffect(widgetId) {
                dao.taskWidgetConfig(widgetId)?.let { config ->
                    mode = config.mode
                    selectedList = config.listCollectionId
                    days = config.lookAheadDays
                }
                excluded = dao.taskWidgetExclusions(widgetId).mapTo(mutableSetOf()) { it.collectionId }
                loaded = true
            }
            MaterialTheme(colorScheme = lightColorScheme(background = Color(0xfffaf9f4), surface = Color(0xfffaf9f4))) {
                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Configure task widget", style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ mode = TaskWidgetMode.LIST }) { Text("One list") }
                        OutlinedButton({ mode = TaskWidgetMode.UPCOMING }) { Text("Upcoming") }
                    }
                    if (mode == TaskWidgetMode.LIST) {
                        Text("Task list", style = MaterialTheme.typography.titleMedium)
                        lists.forEach { list -> ConfigRow(list.displayName, selectedList == list.id) { selectedList = list.id } }
                    } else {
                        Text("Upcoming for $days days", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1, 3, 7, 14, 30).forEach { value -> OutlinedButton({ days = value }) { Text(value.toString()) } } }
                        Text("Exclude lists", style = MaterialTheme.typography.titleMedium)
                        lists.forEach { list -> ConfigRow(list.displayName, list.id in excluded) { excluded = if (list.id in excluded) excluded - list.id else excluded + list.id } }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        lifecycleScope.launch {
                            dao.upsertTaskWidgetConfig(TaskWidgetConfigEntity(widgetId, mode, selectedList, days))
                            dao.clearTaskWidgetExclusions(widgetId)
                            dao.insertTaskWidgetExclusions(excluded.map { TaskWidgetExcludedListEntity(widgetId, it) })
                            TaskWidgetProvider.update(this@TaskWidgetConfigActivity, AppWidgetManager.getInstance(this@TaskWidgetConfigActivity), intArrayOf(widgetId))
                            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)); finish()
                        }
                    }, enabled = loaded && (mode == TaskWidgetMode.UPCOMING || selectedList != null), modifier = Modifier.fillMaxWidth()) { Text(if (loaded) "Save widget" else "Loading…") }
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(label: String, selected: Boolean, click: () -> Unit) {
    OutlinedButton(click, Modifier.fillMaxWidth().border(if (selected) 2.dp else 0.dp, Color.Black)) { Text((if (selected) "✓  " else "□  ") + label) }
}

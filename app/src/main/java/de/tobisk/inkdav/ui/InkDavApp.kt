package de.tobisk.inkdav.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.tobisk.inkdav.*
import de.tobisk.inkdav.R
import de.tobisk.inkdav.data.*
import de.tobisk.inkdav.files.LocalFileEntry
import de.tobisk.inkdav.settings.InkDavSettings
import de.tobisk.inkdav.tasks.ScheduleBucketer
import de.tobisk.inkdav.update.InstallUpdateResult
import de.tobisk.inkdav.update.UpdateInstaller
import de.tobisk.inkdav.update.UpdateState
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Paper = Color(0xfffaf9f4)
private val Ink = Color(0xff111111)
private val MutedInk = Color(0xff4b5563)
private val Rule = Color(0xff59636e)
private val Accent = Color(0xff294c60)
private val Warning = Color(0xff7a351b)
private val CurrentTime = Color(0xffb3261e)
private const val TASKS_ALL = "__all_tasks__"
private const val TASKS_FINISHED = "__finished_tasks__"
private data class RepeatChoice(val label: String, val rule: String?)
private val RepeatChoices = listOf(
    RepeatChoice("Does not repeat", null),
    RepeatChoice("Every day", "FREQ=DAILY"),
    RepeatChoice("Every week", "FREQ=WEEKLY"),
    RepeatChoice("Every month", "FREQ=MONTHLY"),
    RepeatChoice("Every year", "FREQ=YEARLY")
)

@Composable
fun InkDavApp(model: MainViewModel) {
    val destination by model.destination.collectAsStateWithLifecycle()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val collections by model.collections.collectAsStateWithLifecycle()
    val events by model.events.collectAsStateWithLifecycle()
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val scheduledTasks by model.scheduledTasks.collectAsStateWithLifecycle()
    val files by model.files.collectAsStateWithLifecycle()
    val mirrorFiles by model.mirrorFiles.collectAsStateWithLifecycle()
    val pending by model.pendingCount.collectAsStateWithLifecycle()
    val conflictingEvents by model.conflictingEvents.collectAsStateWithLifecycle()
    val conflictingTasks by model.conflictingTasks.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val selectedDate by model.selectedDate.collectAsStateWithLifecycle()
    val calendarMode by model.calendarMode.collectAsStateWithLifecycle()
    val editingEvent by model.editingEvent.collectAsStateWithLifecycle()
    val editingOccurrence by model.editingOccurrence.collectAsStateWithLifecycle()
    val editingTask by model.editingTask.collectAsStateWithLifecycle()

    MaterialTheme(
        colorScheme = lightColorScheme(primary = Accent, onPrimary = Paper, background = Paper, surface = Paper, onSurface = Ink),
        typography = Typography(
            bodyLarge = androidx.compose.ui.text.TextStyle(
                fontSize = 17.sp,
                fontWeight = if (settings.boldText) FontWeight.Medium else FontWeight.Normal
            )
        )
    ) {
        Column(Modifier.fillMaxSize().background(Paper).safeDrawingPadding()) {
            TopNavigation(destination) { model.destination.value = it }
            if (destination == Destination.CALENDAR) {
                CalendarHeader(model, selectedDate, calendarMode, collections, settings.hiddenCalendarIds, pending, accounts)
            } else {
                AppHeader(destination.label, pending, accounts, model::sync)
            }
            Box(Modifier.fillMaxWidth().weight(1f, fill = true)) {
                when (destination) {
                    Destination.CALENDAR -> CalendarScreen(
                        model,
                        selectedDate,
                        calendarMode,
                        events.filterNot {
                            it.collectionId in
                                settings.hiddenCalendarIds
                        },
                        collections
                    )
                    Destination.TASKS -> TasksScreen(model, scheduledTasks, tasks, collections)
                    Destination.FILES -> FilesScreen(model, files, mirrorFiles, collections, settings)
                    Destination.SYNC -> SyncScreen(
                        accounts,
                        pending,
                        conflictingEvents,
                        conflictingTasks,
                        model::sync,
                        model::resolveEventConflict,
                        model::resolveTaskConflict
                    )
                    Destination.SETTINGS -> SettingsScreen(model, accounts, settings)
                }
            }
        }
    }
    editingEvent?.let { event ->
        EditEventDialog(
            event,
            editingOccurrence,
            {
                model.editingEvent.value = null
                model.editingOccurrence.value = null
            },
            model::updateEvent,
            model::deleteEvent
        )
    }
    editingTask?.let { EditTaskDialog(it, { model.editingTask.value = null }, model::updateTask, model::deleteTask) }
}

@Composable
private fun TopNavigation(selected: Destination, select: (Destination) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(76.dp).border(width = 1.dp, color = Ink)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
    ) {
        Destination.entries.forEach { item ->
            Box(
                Modifier.fillMaxHeight().widthIn(min = 132.dp)
                    .background(if (selected == item) Color(0xffe4e1d7) else Paper)
                    .clickable(remember { MutableInteractionSource() }, null) { select(item) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${item.mark}  ${item.label}",
                    maxLines = 1,
                    fontSize = 17.sp,
                    fontWeight = if (selected ==
                        item
                    ) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Medium
                    }
                )
                if (selected == item) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Ink))
            }
        }
    }
}

@Composable
private fun AppHeader(title: String, pending: Int, accounts: List<DavAccountEntity>, sync: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).border(1.dp, Rule).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            if (accounts.isEmpty()) {
                "No account"
            } else if (pending >
                0
            ) {
                "$pending change${if (pending == 1) "" else "s"} waiting"
            } else {
                "Up to date"
            },
            color = if (pending > 0) Warning else MutedInk
        )
        Spacer(Modifier.width(12.dp))
        InkButton("↻ Sync") { sync() }
    }
}

@Composable
private fun CalendarHeader(
    model: MainViewModel,
    date: LocalDate,
    mode: CalendarMode,
    collections: List<DavCollectionEntity>,
    hiddenCalendarIds: Set<String>,
    pending: Int,
    accounts: List<DavAccountEntity>
) {
    var showViewMenu by remember { mutableStateOf(false) }
    var showCalendars by remember { mutableStateOf(false) }
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    Row(
        Modifier.fillMaxWidth().heightIn(min = 66.dp).border(1.dp, Rule).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Column(Modifier.widthIn(min = 122.dp)) {
            Text(date.format(DateTimeFormatter.ofPattern("MMMM")), fontSize = 26.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
            Text(date.year.toString(), fontSize = 15.sp, lineHeight = 16.sp, color = MutedInk, fontWeight = FontWeight.Medium)
        }
        if (pending > 0) {
            Text("$pending waiting", color = Warning, fontSize = 13.sp)
        } else if (accounts.isNotEmpty()) {
            Text("Up to date", color = MutedInk, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        Box {
            CalendarModeButton(mode) { showViewMenu = true }
            DropdownMenu(expanded = showViewMenu, onDismissRequest = {
                showViewMenu = false
            }, containerColor = Paper, border = BorderStroke(2.dp, Ink)) {
                CalendarMode.entries.forEach { view ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                view.name.lowercase().replaceFirstChar(Char::uppercase),
                                fontWeight = if (view ==
                                    mode
                                ) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        },
                        onClick = {
                            model.calendarMode.value = view
                            showViewMenu = false
                        }
                    )
                }
            }
        }
        InkButton("Today") { model.selectedDate.value = LocalDate.now() }
        InkButton("‹", modifier = Modifier.width(88.dp)) { model.selectedDate.value = stepDate(date, mode, -1, isPortrait) }
        InkButton("›", modifier = Modifier.width(88.dp)) { model.selectedDate.value = stepDate(date, mode, 1, isPortrait) }
        HeaderIconButton("▦", "Choose shown calendars") { showCalendars = true }
    }
    if (showCalendars) {
        CalendarVisibilityDialog(
            calendars = collections.filter { it.kind == CollectionKind.CALENDAR },
            hiddenCalendarIds = hiddenCalendarIds,
            close = { showCalendars = false },
            setVisible = model::setCalendarVisible
        )
    }
}

@Composable
private fun CalendarModeButton(mode: CalendarMode, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.width(122.dp).heightIn(min = 48.dp).border(2.dp, Ink).background(Color(0xffe4e1d7))
            .clickable(interactionSource = interaction, indication = null, onClick = action)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            mode.name.lowercase().replaceFirstChar(Char::uppercase),
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Bold
        )
        Text("⌄", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeaderIconButton(symbol: String, description: String, action: () -> Unit) {
    Box(
        Modifier.size(48.dp).border(1.dp, Rule).semantics { contentDescription = description }
            .clickable(remember { MutableInteractionSource() }, null, onClick = action),
        contentAlignment = Alignment.Center
    ) { Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun DayAddButton(description: String, action: () -> Unit) {
    Box(
        Modifier.size(30.dp).semantics { contentDescription = description }
            .clickable(remember { MutableInteractionSource() }, null, onClick = action),
        contentAlignment = Alignment.Center
    ) { Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun InkAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.border(3.dp, Ink, RectangleShape),
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = RectangleShape,
        containerColor = Paper,
        tonalElevation = 0.dp
    )
}

@Composable
private fun CalendarVisibilityDialog(
    calendars: List<DavCollectionEntity>,
    hiddenCalendarIds: Set<String>,
    close: () -> Unit,
    setVisible: (String, Boolean) -> Unit
) {
    InkAlertDialog(
        onDismissRequest = close,
        title = { Text("Shown calendars") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (calendars.isEmpty()) Text("No calendars have been synchronized yet.")
                calendars.forEach { calendar ->
                    val visible = calendar.id !in hiddenCalendarIds
                    Row(
                        Modifier.fillMaxWidth().border(1.dp, Rule).clickable {
                            setVisible(calendar.id, !visible)
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(visible, { setVisible(calendar.id, it) })
                        Text(calendar.displayName, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = { InkButton("Done", action = close) }
    )
}

@Composable
private fun InkButton(label: String, selected: Boolean = false, modifier: Modifier = Modifier, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 48.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) Ink else Rule)
            .background(if (selected) Color(0xffe4e1d7) else Paper)
            .clickable(interactionSource = interaction, indication = null, onClick = action)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1) }
}

@Composable
private fun CalendarScreen(
    model: MainViewModel,
    date: LocalDate,
    mode: CalendarMode,
    events: List<CalendarOccurrenceEntity>,
    collections: List<DavCollectionEntity>
) {
    var addDate by remember { mutableStateOf<LocalDate?>(null) }
    Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
        when (mode) {
            CalendarMode.MONTH -> MonthView(date, events, collections, model) { addDate = it }
            CalendarMode.YEAR -> YearView(date, events, model)
            CalendarMode.WEEK -> WeekView(date, events, collections, model) { addDate = it }
            CalendarMode.DAY -> DayView(date, events, collections, model) { addDate = it }
        }
    }
    addDate?.let { targetDate ->
        EventEditor(
            targetDate,
            collections.filter {
                it.kind == CollectionKind.CALENDAR && !it.readOnly
            },
            { addDate = null }
        ) { collection, title, start, end, allDay, recurrenceRule ->
            model.createEvent(collection, title, start, end, allDay, recurrenceRule)
            addDate = null
        }
    }
}

@Composable
private fun MonthView(
    date: LocalDate,
    events: List<CalendarOccurrenceEntity>,
    collections: List<DavCollectionEntity>,
    model: MainViewModel,
    addEvent: (LocalDate) -> Unit
) {
    val month = date.withDayOfMonth(1)
    val first = month.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekCount = monthWeekCount(month)
    val collectionMap = collections.associateBy(DavCollectionEntity::id)
    Column(Modifier.fillMaxSize().border(1.dp, Rule)) {
        Row(Modifier.fillMaxWidth().height(36.dp)) {
            DayOfWeek.entries.forEach { day ->
                Box(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Rule), contentAlignment = Alignment.Center) {
                    Text(day.name.take(3), fontWeight = FontWeight.Bold)
                }
            }
        }
        repeat(weekCount) { week ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                repeat(7) { offset ->
                    val day = first.plusDays((week * 7 + offset).toLong())
                    val dayEvents = events.filter { eventDate(it.startEpochMillis) == day }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().border(
                            if (day ==
                                LocalDate.now()
                            ) {
                                2.dp
                            } else {
                                0.5.dp
                            },
                            if (day == LocalDate.now()) Ink else Rule
                        )
                            .noRippleClick {
                                model.selectedDate.value = day
                                model.calendarMode.value = CalendarMode.DAY
                            }
                            .padding(5.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                day.dayOfMonth.toString(),
                                color = if (day.month ==
                                    month.month
                                ) {
                                    Ink
                                } else {
                                    MutedInk
                                },
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.weight(1f))
                            DayAddButton("Add event on ${day.format(DateTimeFormatter.ofPattern("d MMMM"))}") { addEvent(day) }
                        }
                        dayEvents.take(4).forEach { event ->
                            val collection = collectionMap[event.collectionId]
                            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(2.dp, 12.dp).background(collection?.colorArgb?.let(::Color) ?: Accent))
                                Spacer(Modifier.width(3.dp))
                                Text(event.title.ifBlank { "(Untitled)" }, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (dayEvents.size > 4) Text("+${dayEvents.size - 4} more", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun YearView(date: LocalDate, events: List<CalendarOccurrenceEntity>, model: MainViewModel) {
    val eventCounts = remember(events) { events.groupingBy { eventDate(it.startEpochMillis) }.eachCount() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { column ->
                    val month = LocalDate.of(date.year, row * 3 + column + 1, 1)
                    YearMonth(
                        month = month,
                        eventCounts = eventCounts,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) {
                        model.selectedDate.value = month
                        model.calendarMode.value = CalendarMode.MONTH
                    }
                }
            }
        }
    }
}

@Composable
private fun YearMonth(
    month: LocalDate,
    eventCounts: Map<LocalDate, Int>,
    modifier: Modifier = Modifier,
    open: () -> Unit
) {
    val first = month.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Column(modifier.border(1.dp, Rule).noRippleClick(open).padding(6.dp)) {
        Text(month.month.name.lowercase().replaceFirstChar(Char::uppercase), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(2.dp))
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(7) { offset ->
                    val day = first.plusDays((week * 7 + offset).toLong())
                    val count = eventCounts[day] ?: 0
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (day.month == month.month) day.dayOfMonth.toString() else "", fontSize = 10.sp, lineHeight = 11.sp)
                        Text(if (count == 0) "" else "•".repeat(count.coerceAtMost(3)), fontSize = 7.sp, lineHeight = 7.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekView(
    date: LocalDate,
    events: List<CalendarOccurrenceEntity>,
    collections: List<DavCollectionEntity>,
    model: MainViewModel,
    addEvent: (LocalDate) -> Unit
) {
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val days = remember(date, isPortrait) { displayedWeekDays(date, isPortrait) }
    val collectionMap = remember(collections) { collections.associateBy(DavCollectionEntity::id) }
    val allDayByDate = remember(events) {
        events.filter(CalendarOccurrenceEntity::allDay).groupBy { eventDate(it.startEpochMillis) }
    }
    val timedBySlot = remember(events) {
        events.filterNot(CalendarOccurrenceEntity::allDay).groupBy { occurrence ->
            val start = Instant.ofEpochMilli(occurrence.startEpochMillis).atZone(ZoneId.systemDefault())
            start.toLocalDate() to start.hour
        }
    }
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    val hourListState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (now.toLocalDate() in days) (now.hour - 1).coerceAtLeast(0) else 7
    )
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = ZonedDateTime.now()
        }
    }
    Column(Modifier.fillMaxSize().border(2.dp, Ink)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 62.dp)) {
            Box(Modifier.width(62.dp).fillMaxHeight().border(1.dp, Ink), contentAlignment = Alignment.BottomCenter) {
                Text("TIME", fontSize = 10.sp, color = MutedInk, modifier = Modifier.padding(bottom = 5.dp))
            }
            days.forEach { day ->
                val allDayEvents = allDayByDate[day].orEmpty()
                Column(
                    Modifier.weight(1f).fillMaxHeight().border(if (day == now.toLocalDate()) 2.dp else 1.dp, Ink)
                        .padding(horizontal = 5.dp, vertical = 3.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(day.format(DateTimeFormatter.ofPattern("EEE d")), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        DayAddButton("Add event on ${day.format(DateTimeFormatter.ofPattern("d MMMM"))}") { addEvent(day) }
                    }
                    Text(
                        allDayEvents.joinToString(" · ") { it.title.ifBlank { "(Untitled)" } },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 10.sp
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = hourListState) {
            items((0..23).toList()) { hour ->
                Row(Modifier.fillMaxWidth().height(72.dp).border(1.dp, Ink)) {
                    Box(Modifier.width(62.dp).fillMaxHeight().border(1.dp, Ink), contentAlignment = Alignment.TopCenter) {
                        Text("%02d:00".format(hour), fontSize = 11.sp, color = MutedInk, modifier = Modifier.padding(top = 3.dp))
                    }
                    days.forEach { day ->
                        val timedEvents = timedBySlot[day to hour].orEmpty()
                        Box(Modifier.weight(1f).fillMaxHeight().border(1.dp, Ink)) {
                            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(1.dp).background(Rule))
                            Column(Modifier.fillMaxSize().padding(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                timedEvents.forEach { event ->
                                    val start = Instant.ofEpochMilli(event.startEpochMillis).atZone(ZoneId.systemDefault())
                                    Text(
                                        "${start.format(DateTimeFormatter.ofPattern("HH:mm"))} ${event.title.ifBlank { "(Untitled)" }}",
                                        modifier = Modifier.fillMaxWidth().border(
                                            1.dp,
                                            collectionMap[event.collectionId]?.colorArgb?.let(::Color) ?: Rule
                                        ).noRippleClick { model.openOccurrence(event) }.padding(horizontal = 3.dp, vertical = 2.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 11.sp,
                                        lineHeight = 12.sp
                                    )
                                }
                            }
                            if (day == now.toLocalDate() && hour == now.hour) {
                                Box(
                                    Modifier.align(Alignment.TopStart).offset(y = (72f * now.minute / 60f).dp)
                                        .fillMaxWidth().height(2.dp).background(CurrentTime)
                                        .semantics { contentDescription = "Current time ${now.format(DateTimeFormatter.ofPattern("HH:mm"))}" }
                                )
                                Box(
                                    Modifier.align(Alignment.TopStart).offset(y = (72f * now.minute / 60f - 3f).dp)
                                        .size(8.dp).background(CurrentTime)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayView(
    date: LocalDate,
    events: List<CalendarOccurrenceEntity>,
    collections: List<DavCollectionEntity>,
    model: MainViewModel,
    addEvent: (LocalDate) -> Unit
) {
    val matching = events.filter { eventDate(it.startEpochMillis) == date }
    Column(Modifier.fillMaxSize().border(1.dp, Rule)) {
        Row(Modifier.fillMaxWidth().border(0.5.dp, Rule).padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            DayAddButton("Add event on ${date.format(DateTimeFormatter.ofPattern("d MMMM"))}") { addEvent(date) }
        }
        if (matching.isEmpty()) {
            EmptyState("No events", "This day is available offline and has no cached events.", Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(matching, key = CalendarOccurrenceEntity::id) { event ->
                    val start = Instant.ofEpochMilli(event.startEpochMillis).atZone(ZoneId.systemDefault())
                    val end = Instant.ofEpochMilli(event.endEpochMillis).atZone(ZoneId.systemDefault())
                    val time = if (event.allDay) {
                        "ALL DAY"
                    } else {
                        "${start.format(
                            DateTimeFormatter.ofPattern("HH:mm")
                        )}–${end.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                    }
                    Row(
                        Modifier.fillMaxWidth().border(0.5.dp, Rule).noRippleClick {
                            model.openOccurrence(event)
                        }.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(time, modifier = Modifier.width(96.dp), fontWeight = FontWeight.Bold)
                        Column(Modifier.weight(1f)) {
                            Text(event.title.ifBlank { "(Untitled)" }, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            if (event.location.isNotBlank()) Text(event.location, color = MutedInk)
                            Text(
                                collections.firstOrNull {
                                    it.id == event.collectionId
                                }?.displayName.orEmpty(),
                                fontSize = 13.sp,
                                color = MutedInk
                            )
                        }
                        SyncMark(event.status)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TasksScreen(
    model: MainViewModel,
    scheduledTasks: List<DavTaskEntity>,
    tasks: List<DavTaskEntity>,
    collections: List<DavCollectionEntity>
) {
    val lists = collections.filter { it.kind == CollectionKind.TASK_LIST }
    val writableLists = lists.filterNot(DavCollectionEntity::readOnly)
    var selectedView by rememberSaveable { mutableStateOf(TASKS_ALL) }
    LaunchedEffect(lists) {
        if (selectedView !in listOf(TASKS_ALL, TASKS_FINISHED) && lists.none { it.id == selectedView }) {
            selectedView = TASKS_ALL
        }
    }
    if (lists.isEmpty()) {
        EmptyState("No task lists", "Add a CalDAV account with VTODO support in Settings.")
        return
    }

    val now = System.currentTimeMillis()
    val recentCompletionCutoff = now - 24 * 60 * 60 * 1000L
    val selectedList = lists.firstOrNull { it.id == selectedView }
    val visibleTasks = when (selectedView) {
        TASKS_FINISHED -> tasks.filter { it.completedAt != null }.sortedByDescending(DavTaskEntity::completedAt)
        TASKS_ALL -> emptyList()
        else -> scheduledTasks.filter { it.collectionId == selectedView && it.completedAt == null } +
            tasks.filter {
                it.collectionId == selectedView && it.completedAt != null && requireNotNull(it.completedAt) >= recentCompletionCutoff
            }.sortedByDescending(DavTaskEntity::completedAt)
    }

    Row(Modifier.fillMaxSize().padding(10.dp)) {
        LazyColumn(Modifier.width(220.dp).fillMaxHeight().border(2.dp, Ink)) {
            item {
                TaskNavigationRow("All tasks", scheduledTasks.count { it.completedAt == null }, selectedView == TASKS_ALL) {
                    selectedView = TASKS_ALL
                }
            }
            item {
                TaskNavigationRow("Finished", tasks.count { it.completedAt != null }, selectedView == TASKS_FINISHED) {
                    selectedView = TASKS_FINISHED
                }
            }
            items(lists, key = DavCollectionEntity::id) { list ->
                TaskNavigationRow(
                    list.displayName,
                    scheduledTasks.count { it.collectionId == list.id && it.completedAt == null },
                    selectedView == list.id
                ) { selectedView = list.id }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).fillMaxHeight().border(2.dp, Ink)) {
            SectionHeader(
                when (selectedView) {
                    TASKS_ALL -> "All tasks · Schedule"
                    TASKS_FINISHED -> "Finished tasks"
                    else -> selectedList?.displayName.orEmpty()
                },
                if (selectedView == TASKS_ALL) scheduledTasks.count { it.completedAt == null } else visibleTasks.size
            )
            if (selectedList?.readOnly == true) {
                Text("This task list is read-only.", modifier = Modifier.fillMaxWidth().border(1.dp, Ink).padding(12.dp))
            } else if (writableLists.isNotEmpty()) {
                InlineTaskCreator(writableLists, selectedList) { collectionId, title ->
                    model.createTask(collectionId, title, null)
                }
            }
            if (selectedView == TASKS_ALL) {
                val buckets = ScheduleBucketer.bucket(scheduledTasks, LocalDate.now(), ZoneId.systemDefault())
                if (buckets.isEmpty()) {
                    EmptyState("No unfinished tasks", "Add a task above.", Modifier.weight(1f))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        buckets.forEach { bucket ->
                            stickyHeader(bucket.key) { SectionHeader(bucket.title, bucket.tasks.size) }
                            items(bucket.tasks, key = DavTaskEntity::id) {
                                TaskRow(it, collections, model::toggleTask, model::openTask)
                            }
                        }
                    }
                }
            } else if (visibleTasks.isEmpty()) {
                EmptyState(
                    if (selectedView == TASKS_FINISHED) "No finished tasks" else "No tasks in this list",
                    "Add a task above.",
                    Modifier.weight(1f)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(visibleTasks, key = DavTaskEntity::id) {
                        TaskRow(it, collections, model::toggleTask, model::openTask)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskNavigationRow(label: String, count: Int, selected: Boolean, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).background(if (selected) Color(0xffe4e1d7) else Paper)
            .border(if (selected) 2.dp else 1.dp, Ink).noRippleClick(action).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        Text(count.toString(), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InlineTaskCreator(
    lists: List<DavCollectionEntity>,
    selectedList: DavCollectionEntity?,
    create: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var targetListId by remember(lists) { mutableStateOf(lists.first().id) }
    var showLists by remember { mutableStateOf(false) }
    LaunchedEffect(selectedList?.id) { selectedList?.let { targetListId = it.id } }
    val targetList = selectedList ?: lists.firstOrNull { it.id == targetListId } ?: lists.first()
    Row(
        Modifier.fillMaxWidth().border(1.dp, Ink).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            title,
            { title = it },
            modifier = Modifier.weight(1f),
            label = { Text("Add a task") },
            singleLine = true
        )
        if (selectedList == null) {
            Box {
                InkButton(targetList.displayName, modifier = Modifier.widthIn(min = 150.dp)) { showLists = true }
                DropdownMenu(
                    expanded = showLists,
                    onDismissRequest = { showLists = false },
                    containerColor = Paper,
                    border = BorderStroke(2.dp, Ink)
                ) {
                    lists.forEach { list ->
                        DropdownMenuItem(text = { Text(list.displayName) }, onClick = {
                            targetListId = list.id
                            showLists = false
                        })
                    }
                }
            }
        }
        InkButton("+ Add", modifier = Modifier.width(96.dp)) {
            if (title.isNotBlank()) {
                create(targetList.id, title)
                title = ""
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: DavTaskEntity,
    collections: List<DavCollectionEntity>,
    toggle: (DavTaskEntity) -> Unit,
    edit: (DavTaskEntity) -> Unit
) {
    Row(Modifier.fillMaxWidth().border(0.5.dp, Rule).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        InkButton(if (task.completedAt == null) "□" else "✓", task.completedAt != null, Modifier.width(52.dp)) { toggle(task) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                task.dueEpochMillis?.let {
                    Text(
                        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, d MMM HH:mm")),
                        color = MutedInk,
                        fontSize = 13.sp
                    )
                }
                Text(collections.firstOrNull { it.id == task.collectionId }?.displayName.orEmpty(), color = MutedInk, fontSize = 13.sp)
                if (task.priority in 1..9) {
                    Text("Priority: ${priorityLabel(task.priority)}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        SyncMark(task.status)
        Spacer(Modifier.width(8.dp))
        HeaderIconButton("✎", "Edit ${task.title}") { edit(task) }
    }
}

private fun priorityLabel(priority: Int): String = when (priority) {
    in 1..3 -> "High"
    in 4..6 -> "Medium"
    in 7..9 -> "Low"
    else -> "None"
}

@Composable
private fun FilesScreen(
    model: MainViewModel,
    files: List<FileNodeEntity>,
    mirrorFiles: List<MirrorEntryEntity>,
    collections: List<DavCollectionEntity>,
    settings: InkDavSettings
) {
    val roots = collections.filter { it.kind == CollectionKind.FILE_ROOT || it.kind == CollectionKind.SHARE }
    val selectedCollection by model.selectedFileCollection.collectAsStateWithLifecycle()
    val selectedParent by model.selectedFileParent.collectAsStateWithLifecycle()
    val selectedMirror by model.selectedMirror.collectAsStateWithLifecycle()
    val selectedMirrorParent by model.selectedMirrorParent.collectAsStateWithLifecycle()
    val mirrors by model.mirrors.collectAsStateWithLifecycle()
    val mirrorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri !=
            null
        ) {
            model.addMirror(uri)
        }
    }
    Row(Modifier.fillMaxSize().padding(10.dp)) {
        Column(Modifier.width(230.dp).fillMaxHeight().border(1.dp, Rule)) {
            SectionHeader("Sources", roots.size + mirrors.size + 1)
            Text("Local folders", modifier = Modifier.fillMaxWidth().border(0.5.dp, Rule).padding(14.dp), fontWeight = FontWeight.Bold)
            Box(
                Modifier.fillMaxWidth().border(if (selectedMirror == null && selectedCollection == null) 2.dp else 1.dp, Ink)
                    .background(if (selectedMirror == null && selectedCollection == null) Color(0xffe4e1d7) else Paper)
                    .noRippleClick(model::selectLocalFiles).padding(12.dp)
            ) {
                Text("▰  Local files", fontWeight = FontWeight.Bold)
            }
            mirrors.forEach { mirror ->
                Box(
                    Modifier.fillMaxWidth().border(0.5.dp, Rule).noRippleClick { model.selectMirror(mirror.id) }.padding(12.dp)
                ) {
                    Text("▤  ${mirror.displayName}", fontWeight = if (selectedMirror == mirror.id) FontWeight.Bold else FontWeight.Medium)
                }
            }
            Text("Online sources", modifier = Modifier.fillMaxWidth().border(0.5.dp, Rule).padding(14.dp), fontWeight = FontWeight.Bold)
            roots.forEach { root ->
                Box(
                    Modifier.fillMaxWidth().border(0.5.dp, Rule).noRippleClick {
                        model.selectFileCollection(root.id, root.href)
                    }.padding(14.dp)
                ) {
                    Text("▤  ${root.displayName}", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Offline folders", modifier = Modifier.fillMaxWidth().border(0.5.dp, Rule).padding(14.dp))
            Text("Transfers & conflicts", modifier = Modifier.fillMaxWidth().border(0.5.dp, Rule).padding(14.dp))
            if (selectedCollection != null &&
                selectedParent != null
            ) {
                Box(Modifier.padding(8.dp)) { InkButton("Mirror this folder") { mirrorPicker.launch(null) } }
            }
            mirrors.forEach { mirror ->
                Column(Modifier.fillMaxWidth().border(0.5.dp, Rule).padding(10.dp)) {
                    Text("↔ ${mirror.displayName}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(mirror.state.name.lowercase(), fontSize = 12.sp, color = MutedInk)
                    mirror.lastError?.let { Text(it, fontSize = 12.sp, color = Warning) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InkButton(if (mirror.enabled) "Pause" else "Resume") {
                            model.setMirrorEnabled(mirror, !mirror.enabled)
                        }
                        InkButton("Remove") { model.removeMirror(mirror) }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (selectedMirror != null) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight().border(1.dp, Rule)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("Physical mirror", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        if (selectedMirrorParent.isNotEmpty()) InkButton("↑ Up") { model.upMirrorFolder() }
                        Spacer(Modifier.width(8.dp))
                        Text("Local", color = MutedInk)
                    }
                }
                items(mirrorFiles, key = MirrorEntryEntity::id) { entry ->
                    Row(
                        Modifier.fillMaxWidth().border(0.5.dp, Rule).noRippleClick {
                            if (entry.isDirectory) {
                                model.openMirrorFolder(entry.relativePath)
                            } else {
                                entry.localDocumentUri?.let { model.openLocalFile(it, entry.mimeType) }
                            }
                        }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (entry.isDirectory) "□" else "▧", fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(entry.relativePath.substringAfterLast('/'), Modifier.weight(1f), fontSize = 17.sp)
                        if (entry.status != MirrorEntryStatus.CLEAN) Text(entry.status.name.lowercase(), color = Warning)
                    }
                }
            }
        } else if (selectedCollection != null) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight().border(1.dp, Rule)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Name", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text("Availability", Modifier.width(120.dp), fontWeight = FontWeight.Bold)
                        Text("Size", Modifier.width(90.dp), fontWeight = FontWeight.Bold)
                    }
                }
                items(files, key = FileNodeEntity::id) { file ->
                    Row(
                        Modifier.fillMaxWidth().border(0.5.dp, Rule).noRippleClick {
                            if (file.isDirectory) model.openFolder(file.href) else model.openFile(file)
                        }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (file.isDirectory) "□" else "▧", fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            file.displayName,
                            Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (file.isDirectory) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            when (file.offlinePolicy) {
                                OfflinePolicy.ONLINE_ONLY -> "Online"
                                OfflinePolicy.PINNED -> "Offline"
                                OfflinePolicy.MIRROR -> "Mirrored"
                            },
                            Modifier.width(120.dp)
                        )
                        Text(file.sizeBytes?.let(::formatBytes).orEmpty(), Modifier.width(90.dp))
                        SyncMark(file.status)
                        Spacer(Modifier.width(8.dp))
                        InkButton(
                            if (file.offlinePolicy ==
                                OfflinePolicy.ONLINE_ONLY
                            ) {
                                "Keep offline"
                            } else {
                                "Free copy"
                            }
                        ) { model.toggleOffline(file) }
                    }
                }
            }
        } else {
            LocalFilesPane(model, settings, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun LocalFilesPane(model: MainViewModel, settings: InkDavSettings, modifier: Modifier = Modifier) {
    val entries by model.localFiles.collectAsStateWithLifecycle()
    val stack by model.localFolderStack.collectAsStateWithLifecycle()
    val error by model.localFilesError.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<LocalFileEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::setLocalFilesRoot)
    }
    Column(modifier.border(2.dp, Ink)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).border(1.dp, Ink).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (stack.isEmpty()) "Local files" else stack.joinToString(" / ") { it.name },
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (stack.size > 1) InkButton("↑ Up", modifier = Modifier.width(88.dp), action = model::upLocalFolder)
            Spacer(Modifier.width(8.dp))
            InkButton(if (settings.localFilesRootUri == null) "Choose root" else "Change root") {
                picker.launch(settings.localFilesRootUri?.let(Uri::parse))
            }
            Spacer(Modifier.width(8.dp))
            DeviceStorageRootButton(model)
        }
        error?.let { Text(it, modifier = Modifier.fillMaxWidth().border(2.dp, Warning).padding(10.dp), color = Warning) }
        if (settings.localFilesRootUri == null) {
            EmptyState(
                "Choose a local root",
                "Android requires you to authorize the folder InkDAV may browse. Files outside DAV mirrors will then appear here.",
                Modifier.weight(1f)
            )
        } else if (entries.isEmpty()) {
            EmptyState("This folder is empty", "There are no visible files in this folder.", Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(entries, key = LocalFileEntry::uri) { entry ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 66.dp).border(1.dp, Ink).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painterResource(localFileIcon(entry)),
                                contentDescription = localFileTypeLabel(entry),
                                modifier = Modifier.size(34.dp),
                                tint = Ink
                            )
                            Text(localFileTypeLabel(entry), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(
                            Modifier.weight(1f).noRippleClick {
                                if (entry.isDirectory) model.openLocalFolder(entry) else preview = entry
                            }
                        ) {
                            Text(
                                entry.name,
                                fontSize = 17.sp,
                                fontWeight = if (entry.isDirectory) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                entry.sizeBytes?.let { Text(formatBytes(it), color = MutedInk, fontSize = 12.sp) }
                                entry.mimeType?.let { Text(it, color = MutedInk, fontSize = 12.sp) }
                            }
                        }
                        if (entry.isDirectory) {
                            InkButton("Open", modifier = Modifier.width(88.dp)) { model.openLocalFolder(entry) }
                        } else {
                            if (canPreview(entry)) {
                                InkButton("Preview", modifier = Modifier.width(104.dp)) { preview = entry }
                                Spacer(Modifier.width(6.dp))
                            }
                            InkButton("External", modifier = Modifier.width(108.dp)) {
                                model.openLocalFile(entry.uri, entry.mimeType)
                            }
                        }
                    }
                }
            }
        }
    }
    preview?.let { entry ->
        LocalFilePreviewDialog(
            entry,
            close = { preview = null },
            openExternal = { model.openLocalFile(entry.uri, entry.mimeType) }
        )
    }
}

private fun localFileIcon(entry: LocalFileEntry): Int = when {
    entry.isDirectory -> R.drawable.ic_file_folder
    entry.mimeType?.startsWith("image/") == true -> R.drawable.ic_file_image
    entry.mimeType?.startsWith("audio/") == true || entry.name.endsWith(".mp3", true) || entry.name.endsWith(".wav", true) -> {
        R.drawable.ic_file_audio
    }
    else -> R.drawable.ic_file_document
}

private fun localFileTypeLabel(entry: LocalFileEntry): String = when {
    entry.isDirectory -> "FOLDER"
    entry.mimeType?.startsWith("image/") == true -> "IMAGE"
    entry.mimeType == "application/pdf" || entry.name.endsWith(".pdf", true) -> "PDF"
    entry.mimeType?.startsWith("audio/") == true || entry.name.endsWith(".mp3", true) || entry.name.endsWith(".wav", true) -> "AUDIO"
    entry.name.endsWith(".md", true) || entry.name.endsWith(".markdown", true) -> "MARKDOWN"
    else -> "FILE"
}

private fun canPreview(entry: LocalFileEntry): Boolean = entry.mimeType?.startsWith("image/") == true ||
    entry.mimeType?.startsWith("audio/") == true ||
    entry.mimeType == "application/pdf" ||
    entry.name.endsWith(".pdf", true) ||
    entry.name.endsWith(".md", true) ||
    entry.name.endsWith(".markdown", true) ||
    entry.name.endsWith(".mp3", true) ||
    entry.name.endsWith(".wav", true)

private sealed interface FilePreview {
    data object Loading : FilePreview
    data class Text(val value: String) : FilePreview
    data class Picture(val bitmap: Bitmap) : FilePreview
    data object Audio : FilePreview
    data class Error(val message: String) : FilePreview
}

@Composable
private fun LocalFilePreviewDialog(entry: LocalFileEntry, close: () -> Unit, openExternal: () -> Unit) {
    val context = LocalContext.current
    val preview by produceState<FilePreview>(FilePreview.Loading, entry.uri) {
        value = withContext(Dispatchers.IO) { loadPreview(context, entry) }
    }
    InkAlertDialog(
        onDismissRequest = close,
        title = { Text(entry.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 560.dp), contentAlignment = Alignment.Center) {
                when (val content = preview) {
                    FilePreview.Loading -> Text("Loading preview…")
                    is FilePreview.Text -> Text(
                        content.value,
                        modifier = Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        fontSize = 15.sp
                    )
                    is FilePreview.Picture -> Image(
                        content.bitmap.asImageBitmap(),
                        contentDescription = "Preview of ${entry.name}",
                        modifier = Modifier.fillMaxSize().border(1.dp, Ink),
                        contentScale = ContentScale.Fit
                    )
                    FilePreview.Audio -> AudioPreview(entry)
                    is FilePreview.Error -> Text(content.message, color = Warning, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = { InkButton("Open externally", action = openExternal) },
        dismissButton = { InkButton("Close", action = close) }
    )
}

@Composable
private fun AudioPreview(entry: LocalFileEntry) {
    val context = LocalContext.current
    var ready by remember(entry.uri) { mutableStateOf(false) }
    var playing by remember(entry.uri) { mutableStateOf(false) }
    var error by remember(entry.uri) { mutableStateOf<String?>(null) }
    var player by remember(entry.uri) { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(entry.uri) {
        val created = runCatching {
            MediaPlayer().apply {
                setDataSource(context, Uri.parse(entry.uri))
                setOnPreparedListener { ready = true }
                setOnCompletionListener { playing = false }
                prepareAsync()
            }
        }.onFailure { error = it.message ?: "The audio file could not be prepared." }.getOrNull()
        player = created
        onDispose { created?.release() }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Audio preview", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(error ?: if (ready) "Ready" else "Preparing audio…", color = if (error == null) MutedInk else Warning)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(if (playing) "Pause" else "Play") {
                player?.let { current ->
                    if (ready) {
                        if (playing) current.pause() else current.start()
                        playing = !playing
                    }
                }
            }
            InkButton("Restart") {
                player?.let { current ->
                    if (ready) {
                        current.seekTo(0)
                        current.start()
                        playing = true
                    }
                }
            }
        }
    }
}

private fun loadPreview(context: android.content.Context, entry: LocalFileEntry): FilePreview = runCatching {
    val uri = Uri.parse(entry.uri)
    when {
        entry.mimeType?.startsWith("audio/") == true || entry.name.endsWith(".mp3", true) || entry.name.endsWith(".wav", true) -> {
            FilePreview.Audio
        }
        entry.name.endsWith(".md", true) || entry.name.endsWith(".markdown", true) -> {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val buffer = CharArray(32_768)
                val count = reader.read(buffer)
                if (count <= 0) "(Empty file)" else String(buffer, 0, count)
            } ?: error("The Markdown file could not be opened.")
            FilePreview.Text(text)
        }
        entry.mimeType == "application/pdf" || entry.name.endsWith(".pdf", true) -> {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("The PDF could not be opened.")
            descriptor.use {
                PdfRenderer(it).use { renderer ->
                    require(renderer.pageCount > 0) { "The PDF has no pages." }
                    renderer.openPage(0).use { page ->
                        val scale = (1400f / page.width).coerceAtMost(2f)
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FilePreview.Picture(bitmap)
                    }
                }
            }
        }
        entry.mimeType?.startsWith("image/") == true -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: error("The image could not be decoded.")
            FilePreview.Picture(bitmap)
        }
        else -> FilePreview.Error("No built-in preview is available for this file type.")
    }
}.getOrElse { FilePreview.Error(it.message ?: "The preview could not be loaded.") }

@Composable
private fun SyncScreen(
    accounts: List<DavAccountEntity>,
    pending: Int,
    conflictingEvents: List<CalendarEventEntity>,
    conflictingTasks: List<DavTaskEntity>,
    sync: () -> Unit,
    resolveEvent: (CalendarEventEntity, Boolean) -> Unit,
    resolveTask: (DavTaskEntity, Boolean) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Synchronization", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Text("$pending local change${if (pending == 1) "" else "s"} waiting for upload. Reads always come from the local database.")
        }
        item { InkButton("Sync now") { sync() } }
        if (conflictingEvents.isNotEmpty() || conflictingTasks.isNotEmpty()) {
            item {
                Text("Conflicts", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Warning)
                Text("Choose the server version, or keep both by uploading your offline edit as a separate copy.")
            }
            items(conflictingEvents, key = { "event-${it.id}" }) { event ->
                ConflictRow(event.title, { resolveEvent(event, false) }, { resolveEvent(event, true) })
            }
            items(conflictingTasks, key = { "task-${it.id}" }) { task ->
                ConflictRow(task.title, { resolveTask(task, false) }, { resolveTask(task, true) })
            }
        }
        items(accounts, key = { it.id }) { account ->
            Column(Modifier.fillMaxWidth().border(1.dp, Rule).padding(14.dp)) {
                Text(account.displayName, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(account.baseUrl, color = MutedInk)
                Text(
                    account.lastSyncAt?.let {
                        "Last sync: ${Instant.ofEpochMilli(
                            it
                        ).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))}"
                    }
                        ?: "Never synchronized"
                )
                account.lastSyncError?.let { Text("Error: $it", color = Warning, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ConflictRow(title: String, useServer: () -> Unit, keepBoth: () -> Unit) {
    Column(Modifier.fillMaxWidth().border(2.dp, Warning).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("Use server") { useServer() }
            InkButton("Keep both") { keepBoth() }
        }
    }
}

@Composable
private fun SettingsScreen(model: MainViewModel, accounts: List<DavAccountEntity>, settings: InkDavSettings) {
    var showAccount by remember { mutableStateOf(false) }
    var credentialAccount by remember { mutableStateOf<DavAccountEntity?>(null) }
    var removalAccount by remember { mutableStateOf<DavAccountEntity?>(null) }
    val updateState by model.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val installedVersion = remember { model.currentAppVersion() }
    val localRootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::setLocalFilesRoot)
    }
    var installMessage by remember { mutableStateOf<String?>(null) }
    val unknownSourcesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ready = model.updateState.value as? UpdateState.Ready ?: return@rememberLauncherForActivityResult
        installMessage = when (val result = UpdateInstaller.install(context, ready.apkPath)) {
            InstallUpdateResult.InstallerOpened -> "Android's installer is ready. Confirm the update there."
            InstallUpdateResult.PermissionRequired -> "Installation permission was not enabled. You can retry below."
            is InstallUpdateResult.Rejected -> result.reason
        }
    }

    fun install(ready: UpdateState.Ready) {
        installMessage = when (val result = UpdateInstaller.install(context, ready.apkPath)) {
            InstallUpdateResult.InstallerOpened -> "Android's installer is ready. Confirm the update there."
            InstallUpdateResult.PermissionRequired -> {
                unknownSourcesLauncher.launch(UpdateInstaller.unknownSourcesIntent(context))
                "Allow InkDAV to install updates, then return to continue."
            }
            is InstallUpdateResult.Rejected -> result.reason
        }
    }

    LaunchedEffect(updateState) {
        val ready = updateState as? UpdateState.Ready
        if (ready != null && !ready.installPrompted) {
            model.markUpdateInstallPrompted(ready.apkPath)
            install(ready)
        }
    }
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Accounts", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            InkButton("+ Account") {
                showAccount =
                    true
            }
        }
        accounts.forEach { account ->
            Column(Modifier.fillMaxWidth().border(1.dp, Rule).padding(12.dp)) {
                Text(account.displayName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("${account.kind.name} · ${account.username}", color = MutedInk)
                Text(account.baseUrl, color = MutedInk)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("Change secret") { credentialAccount = account }
                    InkButton("Remove account") { removalAccount = account }
                }
            }
        }
        SettingPanel("Calendar cache") {
            Text(
                "Download ${settings.calendarPastDays} days in the past and ${settings.calendarFutureMonths} months ahead. Recurrence masters are retained."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("− past") { model.setCalendarWindow(settings.calendarPastDays - 30, settings.calendarFutureMonths) }
                InkButton("+ past") { model.setCalendarWindow(settings.calendarPastDays + 30, settings.calendarFutureMonths) }
                InkButton("− future") { model.setCalendarWindow(settings.calendarPastDays, settings.calendarFutureMonths - 6) }
                InkButton("+ future") { model.setCalendarWindow(settings.calendarPastDays, settings.calendarFutureMonths + 6) }
            }
        }
        SettingPanel("Application updates") {
            Text("Installed version $installedVersion")
            when (val state = updateState) {
                UpdateState.Idle -> Text("Updates are downloaded from the official InkDAV GitHub release.", color = MutedInk)
                UpdateState.Checking -> Text("Checking GitHub for the latest release…")
                is UpdateState.Downloading -> {
                    val progress = if (state.totalBytes > 0) {
                        "${state.bytesRead * 100 / state.totalBytes}%"
                    } else {
                        "${state.bytesRead / 1024} KiB"
                    }
                    Text("Downloading and verifying v${state.version}: $progress")
                }
                is UpdateState.Ready -> {
                    Text("Version ${state.version} is downloaded and verified.")
                    InkButton("Install v${state.version}") { install(state) }
                }
                is UpdateState.UpToDate -> Text("Version ${state.version} is the latest release.")
                is UpdateState.Error -> Text("Update failed: ${state.message}", color = Warning)
            }
            if (updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading) {
                InkButton("Check for updates") {
                    installMessage = null
                    model.checkForUpdates()
                }
            }
            installMessage?.let { Text(it, color = MutedInk) }
            Text(
                "InkDAV verifies the release checksum, application ID, version code, and release signature before Android opens the installer.",
                color = MutedInk
            )
        }
        SettingPanel("Color e-ink") {
            Text(
                "Opaque paper background, outlined controls, no animated transitions, and text labels in addition to color are always enabled."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Bold text", settings.boldText) { model.setEink(!settings.boldText, settings.pageNavigation) }
                InkButton("Page navigation", settings.pageNavigation) { model.setEink(settings.boldText, !settings.pageNavigation) }
            }
            Text(
                "BOOX: use HD or Regal for calendar and tasks. Use Speed for long file lists. Enable full refresh after page switching if ghosting remains.",
                color = MutedInk
            )
        }
        SettingPanel("Files and other apps") {
            Text(
                "InkDAV appears in Android's system file picker. Offline mirrors use a folder you explicitly choose; Android does not permit a transparent raw filesystem mount for every legacy app."
            )
            Text(
                "NASDrive v1: HTTPS Basic with revocable device credentials at /webdav/. Folder scans are bounded to avoid waking idle NAS disks continuously.",
                color = MutedInk
            )
            Text(
                if (settings.localFilesRootUri == null) {
                    "Local file root: not selected"
                } else {
                    "Local file root: available"
                },
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton(if (settings.localFilesRootUri == null) "Choose local root" else "Change local root") {
                    localRootPicker.launch(settings.localFilesRootUri?.let(Uri::parse))
                }
                DeviceStorageRootButton(model)
                if (settings.localFilesRootUri != null) InkButton("Remove local root") { model.clearLocalFilesRoot() }
            }
            Text(
                "Device storage root requires Android's all-files access. InkDAV uses it only for the local file browser and grants external apps access one selected file at a time.",
                color = MutedInk
            )
        }
    }
    if (showAccount) AccountEditor({ showAccount = false }, model::addAccount)
    credentialAccount?.let { account ->
        PasswordEditor(
            account,
            { credentialAccount = null }
        ) { password ->
            model.updateCredentials(account, password)
            credentialAccount = null
        }
    }
    removalAccount?.let { account ->
        InkAlertDialog(
            onDismissRequest = { removalAccount = null },
            title = { Text("Remove ${account.displayName}?") },
            text = { Text("Cached calendars, tasks, files, mirror state, pending edits, and the stored credential for this account will be removed from this device.") },
            confirmButton = {
                InkButton("Remove") {
                    model.removeAccount(account)
                    removalAccount = null
                }
            },
            dismissButton = { InkButton("Cancel") { removalAccount = null } }
        )
    }
}

@Composable
private fun PasswordEditor(account: DavAccountEntity, close: () -> Unit, save: (CharArray) -> Unit) {
    var password by remember(account.id) { mutableStateOf("") }
    InkAlertDialog(
        onDismissRequest = close,
        title = { Text("Change secret") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(account.displayName, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("New password or device secret") },
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = { InkButton("Save") { if (password.isNotEmpty()) save(password.toCharArray()) } },
        dismissButton = { InkButton("Cancel", action = close) }
    )
}

@Composable
private fun SettingPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, Rule).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun AccountEditor(close: () -> Unit, save: (String, String, String, CharArray, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nas by remember { mutableStateOf(false) }
    InkAlertDialog(
        onDismissRequest = close,
        title = { Text("Add DAV account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                OutlinedTextField(url, { url = it }, label = { Text(if (nas) "NASDrive URL (ending /webdav/)" else "CalDAV/WebDAV URL") })
                OutlinedTextField(user, { user = it }, label = { Text(if (nas) "Device access key" else "Username") })
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text(if (nas) "Device secret" else "Password") },
                    visualTransformation = PasswordVisualTransformation()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(nas, { nas = it })
                    Text("NASDrive account")
                }
                if (nas) Text("Use HTTPS device credentials, not the interactive OIDC password.", color = MutedInk)
            }
        },
        confirmButton = {
            InkButton("Save") {
                if (name.isNotBlank() &&
                    url.startsWith("https://") &&
                    user.isNotBlank() &&
                    password.isNotEmpty()
                ) {
                    save(name, url, user, password.toCharArray(), nas)
                    close()
                }
            }
        },
        dismissButton = { InkButton("Cancel", action = close) }
    )
}

@Composable
private fun DeviceStorageRootButton(model: MainViewModel) {
    val context = LocalContext.current
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            model.useDeviceStorageRoot()
        }
    }
    val legacyReadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.useDeviceStorageRoot()
    }
    InkButton("Use device root") {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() -> {
                model.useDeviceStorageRoot()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                allFilesLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED -> {
                model.useDeviceStorageRoot()
            }
            else -> legacyReadLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

@Composable
private fun EventEditor(
    date: LocalDate,
    calendars: List<DavCollectionEntity>,
    close: () -> Unit,
    save: (String, String, Long, Long, Boolean, String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(calendars.firstOrNull()?.id.orEmpty()) }
    var allDay by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf(date) }
    var endDate by remember { mutableStateOf(date) }
    var startHour by remember { mutableIntStateOf(9) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(10) }
    var endMinute by remember { mutableIntStateOf(0) }
    var recurrenceRule by remember { mutableStateOf<String?>(null) }
    var showCalendars by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val selectedCalendar = calendars.firstOrNull { it.id == selected }
    InkAlertDialog(
        onDismissRequest = close,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "New event · ${date.format(DateTimeFormatter.ofPattern("d MMM"))}",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold
                )
                Box {
                    InkButton(selectedCalendar?.displayName ?: "Calendar") { showCalendars = true }
                    DropdownMenu(
                        expanded = showCalendars,
                        onDismissRequest = { showCalendars = false },
                        containerColor = Paper,
                        border = BorderStroke(2.dp, Ink)
                    ) {
                        calendars.forEach { calendar ->
                            DropdownMenuItem(
                                text = { Text(calendar.displayName, fontWeight = FontWeight.Medium) },
                                onClick = {
                                    selected = calendar.id
                                    showCalendars = false
                                }
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    title,
                    {
                        title = it
                        validationError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allDay, { allDay = it })
                    Text("All day")
                }
                DateSelector("Start date", startDate) { changed ->
                    startDate = changed
                    if (endDate.isBefore(changed)) endDate = changed
                }
                if (!allDay) {
                    TimeSelector("Start time", startHour, startMinute, { startHour = it }, { startMinute = it })
                }
                DateSelector("End date", endDate) { changed -> endDate = changed }
                if (!allDay) TimeSelector("End time", endHour, endMinute, { endHour = it }, { endMinute = it })
                ChoiceSelector(
                    "Repeat",
                    RepeatChoices.firstOrNull { it.rule == recurrenceRule }?.label ?: "Custom",
                    RepeatChoices.map { it.label }
                ) { label -> recurrenceRule = RepeatChoices.first { it.label == label }.rule }
                validationError?.let { Text(it, color = Warning, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = {
            InkButton("Create offline") {
                val zone = ZoneId.systemDefault()
                val start = if (allDay) {
                    startDate.atStartOfDay(zone).toInstant().toEpochMilli()
                } else {
                    startDate.atTime(startHour, startMinute).atZone(zone).toInstant().toEpochMilli()
                }
                val end = if (allDay) {
                    endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                } else {
                    endDate.atTime(endHour, endMinute).atZone(zone).toInstant().toEpochMilli()
                }
                validationError = when {
                    title.isBlank() -> "Enter a title."
                    selected.isBlank() -> "Choose a calendar."
                    end <= start -> "The end must be after the start."
                    else -> null
                }
                if (validationError == null) save(selected, title, start, end, allDay, recurrenceRule)
            }
        },
        dismissButton = { InkButton("Cancel", action = close) }
    )
}

@Composable
private fun DateSelector(label: String, value: LocalDate, changed: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MutedInk, fontSize = 13.sp)
            Text(value.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")), fontWeight = FontWeight.Bold)
        }
        InkButton("− day", modifier = Modifier.width(88.dp)) { changed(value.minusDays(1)) }
        Spacer(Modifier.width(6.dp))
        InkButton("+ day", modifier = Modifier.width(88.dp)) { changed(value.plusDays(1)) }
    }
}

@Composable
private fun TimeSelector(
    label: String,
    hour: Int,
    minute: Int,
    hourChanged: (Int) -> Unit,
    minuteChanged: (Int) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        NumberSelector("%02d".format(hour), (0..23).toList().map { "%02d".format(it) }) {
            hourChanged(it.toInt())
        }
        Text(":", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        NumberSelector("%02d".format(minute), (0..55 step 5).toList().map { "%02d".format(it) }) {
            minuteChanged(it.toInt())
        }
    }
}

@Composable
private fun NumberSelector(value: String, values: List<String>, changed: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        InkButton(value, modifier = Modifier.width(72.dp)) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Paper,
            border = BorderStroke(2.dp, Ink)
        ) {
            values.forEach { option ->
                DropdownMenuItem(text = { Text(option, fontWeight = FontWeight.Bold) }, onClick = {
                    changed(option)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun ChoiceSelector(label: String, value: String, values: List<String>, changed: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Box {
            InkButton("$value  ⌄", modifier = Modifier.widthIn(min = 180.dp)) { expanded = true }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = Paper,
                border = BorderStroke(2.dp, Ink)
            ) {
                values.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = {
                        changed(option)
                        expanded = false
                    })
                }
            }
        }
    }
}

@Composable
private fun TaskEditor(lists: List<DavCollectionEntity>, close: () -> Unit, save: (String, String, LocalDate?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(lists.firstOrNull()?.id.orEmpty()) }
    var due by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    InkAlertDialog(onDismissRequest = close, title = { Text("New task") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Task") })
            lists.forEach { InkButton(it.displayName, selected == it.id, Modifier.fillMaxWidth()) { selected = it.id } }
            Text("Due", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(
                    "Today" to LocalDate.now(),
                    "Tomorrow" to LocalDate.now().plusDays(1),
                    "No date" to null
                ).forEach { (label, value) ->
                    InkButton(label, due == value) {
                        due =
                            value
                    }
                }
            }
        }
    }, confirmButton = {
        InkButton("Create offline") {
            if (title.isNotBlank() &&
                selected.isNotBlank()
            ) {
                save(selected, title, due)
            }
        }
    }, dismissButton = { InkButton("Cancel", action = close) })
}

@Composable
private fun EditEventDialog(
    event: CalendarEventEntity,
    occurrence: CalendarOccurrenceEntity?,
    close: () -> Unit,
    save: (CalendarEventEntity, String, String, String, Long, Long, Boolean, String?, Boolean) -> Unit,
    delete: (CalendarEventEntity, Boolean) -> Unit
) {
    var title by remember(event.id) { mutableStateOf(event.title) }
    var description by remember(event.id) { mutableStateOf(event.description) }
    var location by remember(event.id) { mutableStateOf(event.location) }
    var start by remember(event.id, occurrence?.id) { mutableLongStateOf(occurrence?.startEpochMillis ?: event.startEpochMillis) }
    var end by remember(event.id, occurrence?.id) { mutableLongStateOf(occurrence?.endEpochMillis ?: event.endEpochMillis) }
    var allDay by remember(event.id, occurrence?.id) { mutableStateOf(occurrence?.allDay ?: event.allDay) }
    var recurrenceRule by remember(event.id) { mutableStateOf(event.recurrenceRule) }
    var entireSeries by remember(event.id, occurrence?.id) { mutableStateOf(false) }
    InkAlertDialog(onDismissRequest = close, title = {
        Text(
            if (event.recurrenceRule == null) {
                "Edit event offline"
            } else {
                "Edit recurring event offline"
            }
        )
    }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (event.recurrenceRule != null && occurrence != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(entireSeries, { entireSeries = it })
                    Text("Apply to entire series")
                }
                Text(
                    if (entireSeries) "The series master and all generated occurrences will change." else "Only this occurrence will change; the series remains intact.",
                    color = MutedInk
                )
            }
            OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Title") })
            OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Description") })
            OutlinedTextField(location, {
                location =
                    it
            }, modifier = Modifier.fillMaxWidth(), label = { Text("Location") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(allDay, { allDay = it })
                Text("All day")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InkButton("− day") {
                    start = CalendarTimeMath.shiftDays(start, -1)
                    end = CalendarTimeMath.shiftDays(end, -1)
                }
                InkButton("+ day") {
                    start = CalendarTimeMath.shiftDays(start, 1)
                    end = CalendarTimeMath.shiftDays(end, 1)
                }
                if (!allDay) {
                    InkButton("− hour") {
                        start -= 3_600_000
                        end -= 3_600_000
                    }
                    InkButton("+ hour") {
                        start += 3_600_000
                        end += 3_600_000
                    }
                }
            }
            if (occurrence == null || entireSeries) {
                ChoiceSelector(
                    "Repeat",
                    RepeatChoices.firstOrNull { it.rule == recurrenceRule }?.label ?: "Custom",
                    RepeatChoices.map { it.label }
                ) { label -> recurrenceRule = RepeatChoices.first { it.label == label }.rule }
            }
            Text(Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm")))
        }
    }, confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InkButton(if (event.recurrenceRule != null && !entireSeries) "Delete occurrence" else "Delete") {
                delete(event, entireSeries)
            }
            InkButton("Save offline") {
                if (title.isNotBlank()) {
                    save(
                        event,
                        title,
                        description,
                        location,
                        start,
                        end,
                        allDay,
                        recurrenceRule,
                        entireSeries
                    )
                }
            }
        }
    }, dismissButton = { InkButton("Cancel", action = close) })
}

@Composable
private fun EditTaskDialog(
    task: DavTaskEntity,
    close: () -> Unit,
    save: (DavTaskEntity, String, String, Long?, Int) -> Unit,
    delete: (DavTaskEntity) -> Unit
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var notes by remember(task.id) { mutableStateOf(task.notes) }
    var due by remember(task.id) { mutableStateOf(task.dueEpochMillis) }
    var priority by remember(task.id) { mutableIntStateOf(task.priority) }
    InkAlertDialog(onDismissRequest = close, title = { Text("Edit task offline") }, text = {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Task") })
            OutlinedTextField(notes, { notes = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Notes") })
            Text("Date", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InkButton("No date") { due = null }
                InkButton("Today") {
                    due =
                        LocalDate.now().atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
                InkButton("Tomorrow") {
                    due = LocalDate.now().plusDays(1).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            }
            due?.let { value ->
                DateSelector(
                    "Due date",
                    Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate()
                ) { changed ->
                    due = changed.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            }
            ChoiceSelector(
                "Priority",
                priorityLabel(priority),
                listOf("None", "High", "Medium", "Low")
            ) { selected ->
                priority = when (selected) {
                    "High" -> 1
                    "Medium" -> 5
                    "Low" -> 9
                    else -> 0
                }
            }
        }
    }, confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InkButton("Delete") { delete(task) }
            InkButton("Save offline") { if (title.isNotBlank()) save(task, title, notes, due, priority) }
        }
    }, dismissButton = { InkButton("Cancel", action = close) })
}

@Composable private fun SectionHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().background(Color(0xffe4e1d7)).border(1.dp, Rule).padding(10.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.weight(1f))
        Text(count.toString())
    }
}

@Composable private fun SyncMark(status: SyncStatus) {
    Text(
        when (status) {
            SyncStatus.CLEAN -> ""
            SyncStatus.PENDING -> "↑"
            SyncStatus.SYNCING -> "↻"
            SyncStatus.CONFLICT -> "! conflict"
            SyncStatus.ERROR -> "!"
        },
        color = if (status ==
            SyncStatus.CONFLICT ||
            status == SyncStatus.ERROR
        ) {
            Warning
        } else {
            MutedInk
        },
        fontWeight = FontWeight.Bold
    )
}

@Composable private fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().border(1.dp, Rule),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = MutedInk, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

private fun Modifier.noRippleClick(action: () -> Unit) = composed {
    clickable(remember { MutableInteractionSource() }, null, onClick = action)
}
internal fun displayedWeekDays(date: LocalDate, isPortrait: Boolean): List<LocalDate> {
    val first = if (isPortrait) date else date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return List(if (isPortrait) 3 else 7) { first.plusDays(it.toLong()) }
}

internal fun monthWeekCount(date: LocalDate): Int {
    val month = date.withDayOfMonth(1)
    val first = month.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val last = month.with(TemporalAdjusters.lastDayOfMonth())
    return ChronoUnit.WEEKS.between(first, last.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).toInt() + 1
}

internal fun stepDate(date: LocalDate, mode: CalendarMode, direction: Long, isPortrait: Boolean) = when (mode) {
    CalendarMode.YEAR -> date.plusYears(direction)
    CalendarMode.MONTH -> date.plusMonths(direction)
    CalendarMode.WEEK -> if (isPortrait) date.plusDays(direction) else date.plusWeeks(direction)
    CalendarMode.DAY -> date.plusDays(direction)
}
private fun calendarTitle(date: LocalDate, mode: CalendarMode) = when (mode) {
    CalendarMode.YEAR -> date.year.toString()
    CalendarMode.MONTH -> date.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    CalendarMode.WEEK -> "Week of ${date.with(
        TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
    ).format(DateTimeFormatter.ofPattern("d MMM"))}"
    CalendarMode.DAY -> date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))
}
private fun eventDate(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
private fun formatBytes(value: Long): String = when {
    value >= 1_000_000_000 -> "%.1f GB".format(value / 1_000_000_000.0)
    value >=
        1_000_000 -> "%.1f MB".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1f kB".format(value / 1_000.0)
    else -> "$value B"
}

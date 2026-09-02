package de.tobisk.inkdav.ui

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.tobisk.inkdav.*
import de.tobisk.inkdav.data.*
import de.tobisk.inkdav.settings.InkDavSettings
import de.tobisk.inkdav.tasks.ScheduleBucketer
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.delay

private val Paper = Color(0xfffaf9f4)
private val Ink = Color(0xff111111)
private val MutedInk = Color(0xff4b5563)
private val Rule = Color(0xff59636e)
private val Accent = Color(0xff294c60)
private val Warning = Color(0xff7a351b)
private val CurrentTime = Color(0xffb3261e)

@Composable
fun InkDavApp(model: MainViewModel) {
    val destination by model.destination.collectAsStateWithLifecycle()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val collections by model.collections.collectAsStateWithLifecycle()
    val events by model.events.collectAsStateWithLifecycle()
    val scheduledTasks by model.scheduledTasks.collectAsStateWithLifecycle()
    val files by model.files.collectAsStateWithLifecycle()
    val mirrorFiles by model.mirrorFiles.collectAsStateWithLifecycle()
    val pending by model.pendingCount.collectAsStateWithLifecycle()
    val conflictingEvents by model.conflictingEvents.collectAsStateWithLifecycle()
    val conflictingTasks by model.conflictingTasks.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val selectedDate by model.selectedDate.collectAsStateWithLifecycle()
    val calendarMode by model.calendarMode.collectAsStateWithLifecycle()
    val taskMode by model.taskMode.collectAsStateWithLifecycle()
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
                    Destination.TASKS -> TasksScreen(model, taskMode, scheduledTasks, collections)
                    Destination.FILES -> FilesScreen(model, files, mirrorFiles, collections)
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
        Modifier.fillMaxWidth().height(38.dp).border(width = 1.dp, color = Rule)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
    ) {
        Destination.entries.forEach { item ->
            Box(
                Modifier.height(38.dp).widthIn(min = 112.dp)
                    .background(if (selected == item) Color(0xffe4e1d7) else Paper)
                    .clickable(remember { MutableInteractionSource() }, null) { select(item) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${item.mark}  ${item.label}",
                    maxLines = 1,
                    fontSize = 14.sp,
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
            InkButton("${mode.name.lowercase().replaceFirstChar(Char::uppercase)}⌄", selected = true) { showViewMenu = true }
            DropdownMenu(expanded = showViewMenu, onDismissRequest = {
                showViewMenu = false
            }, containerColor = Paper, border = BorderStroke(1.dp, Rule)) {
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
        InkButton("‹") { model.selectedDate.value = stepDate(date, mode, -1, isPortrait) }
        InkButton("›") { model.selectedDate.value = stepDate(date, mode, 1, isPortrait) }
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
private fun CalendarVisibilityDialog(
    calendars: List<DavCollectionEntity>,
    hiddenCalendarIds: Set<String>,
    close: () -> Unit,
    setVisible: (String, Boolean) -> Unit
) {
    AlertDialog(
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
                it.kind == CollectionKind.CALENDAR
            },
            { addDate = null }
        ) { collection, title, allDay, hour ->
            model.createEvent(collection, title, targetDate, hour, allDay)
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
    val collectionMap = collections.associateBy(DavCollectionEntity::id)
    Column(Modifier.fillMaxSize().border(1.dp, Rule)) {
        Row(Modifier.fillMaxWidth().height(36.dp)) {
            DayOfWeek.entries.forEach { day ->
                Box(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Rule), contentAlignment = Alignment.Center) {
                    Text(day.name.take(3), fontWeight = FontWeight.Bold)
                }
            }
        }
        repeat(6) { week ->
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
    Column(Modifier.fillMaxSize().border(1.dp, Rule)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 62.dp)) {
            Box(Modifier.width(50.dp).fillMaxHeight().border(0.5.dp, Rule), contentAlignment = Alignment.BottomCenter) {
                Text("TIME", fontSize = 10.sp, color = MutedInk, modifier = Modifier.padding(bottom = 5.dp))
            }
            days.forEach { day ->
                val allDayEvents = allDayByDate[day].orEmpty()
                Column(
                    Modifier.weight(1f).fillMaxHeight().border(if (day == now.toLocalDate()) 2.dp else 0.5.dp, Rule)
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
                Row(Modifier.fillMaxWidth().height(68.dp)) {
                    Box(Modifier.width(50.dp).fillMaxHeight().border(0.5.dp, Rule), contentAlignment = Alignment.TopCenter) {
                        Text("%02d:00".format(hour), fontSize = 11.sp, color = MutedInk, modifier = Modifier.padding(top = 3.dp))
                    }
                    days.forEach { day ->
                        val timedEvents = timedBySlot[day to hour].orEmpty()
                        Box(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Rule)) {
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
                                    Modifier.align(Alignment.TopStart).offset(y = (68f * now.minute / 60f).dp)
                                        .fillMaxWidth().height(2.dp).background(CurrentTime)
                                        .semantics { contentDescription = "Current time ${now.format(DateTimeFormatter.ofPattern("HH:mm"))}" }
                                )
                                Box(
                                    Modifier.align(Alignment.TopStart).offset(y = (68f * now.minute / 60f - 3f).dp)
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
    mode: TaskMode,
    scheduledTasks: List<DavTaskEntity>,
    collections: List<DavCollectionEntity>
) {
    var showAdd by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            InkButton("Lists", mode == TaskMode.LISTS) { model.taskMode.value = TaskMode.LISTS }
            InkButton("Schedule", mode == TaskMode.SCHEDULE) { model.taskMode.value = TaskMode.SCHEDULE }
            Spacer(Modifier.weight(1f))
            InkButton("+ Task") { showAdd = true }
        }
        Spacer(Modifier.height(8.dp))
        if (collections.none { it.kind == CollectionKind.TASK_LIST }) {
            EmptyState("No task lists", "Add a CalDAV account with VTODO support in Settings.")
        } else if (mode == TaskMode.SCHEDULE) {
            val buckets = ScheduleBucketer.bucket(scheduledTasks, LocalDate.now(), ZoneId.systemDefault())
            LazyColumn(Modifier.fillMaxSize().border(1.dp, Rule)) {
                buckets.forEach { bucket ->
                    stickyHeader(bucket.key) { SectionHeader(bucket.title, bucket.tasks.size) }
                    items(bucket.tasks, key = DavTaskEntity::id) { TaskRow(it, collections, model::toggleTask, model::openTask) }
                }
            }
        } else {
            val lists = collections.filter { it.kind == CollectionKind.TASK_LIST }
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(220.dp).fillMaxHeight().border(1.dp, Rule)) {
                    lists.forEach { list ->
                        Text(
                            "${list.displayName}  ${scheduledTasks.count {
                                it.collectionId == list.id && it.completedAt == null
                            }}",
                            modifier = Modifier.fillMaxWidth().border(0.5.dp, Rule).padding(14.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight().border(1.dp, Rule)) {
                    items(scheduledTasks, key = DavTaskEntity::id) { TaskRow(it, collections, model::toggleTask, model::openTask) }
                }
            }
        }
    }
    if (showAdd) {
        TaskEditor(collections.filter { it.kind == CollectionKind.TASK_LIST }, { showAdd = false }) { collection, title, due ->
            model.createTask(collection, title, due)
            showAdd = false
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
            }
        }
        SyncMark(task.status)
        Spacer(Modifier.width(8.dp))
        InkButton("Edit") { edit(task) }
    }
}

@Composable
private fun FilesScreen(
    model: MainViewModel,
    files: List<FileNodeEntity>,
    mirrorFiles: List<MirrorEntryEntity>,
    collections: List<DavCollectionEntity>
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
            SectionHeader("Sources", roots.size + mirrors.size)
            Text("Local folders", modifier = Modifier.fillMaxWidth().border(0.5.dp, Rule).padding(14.dp), fontWeight = FontWeight.Bold)
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
        if (roots.isEmpty() && mirrors.isEmpty()) {
            EmptyState(
                "No file source",
                "Add NASDrive or another WebDAV account. NASDrive uses /webdav/ with device credentials.",
                Modifier.weight(1f)
            )
        } else if (selectedMirror != null) {
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
        } else {
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
        }
    }
}

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
        AlertDialog(
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
    AlertDialog(
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
    AlertDialog(
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
                }
            }
        },
        dismissButton = { InkButton("Cancel", action = close) }
    )
}

@Composable
private fun EventEditor(
    date: LocalDate,
    calendars: List<DavCollectionEntity>,
    close: () -> Unit,
    save: (String, String, Boolean, Int) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(calendars.firstOrNull()?.id.orEmpty()) }
    var allDay by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(9) }
    AlertDialog(
        onDismissRequest = close,
        modifier = Modifier.border(2.dp, Ink, RectangleShape),
        shape = RectangleShape,
        containerColor = Paper,
        tonalElevation = 0.dp,
        title = { Text("New event · ${date.format(DateTimeFormatter.ofPattern("d MMM"))}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") })
                Text("Calendar", fontWeight = FontWeight.Bold)
                calendars.forEach { InkButton(it.displayName, selected == it.id, Modifier.fillMaxWidth()) { selected = it.id } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allDay, { allDay = it })
                    Text("All day")
                }
                if (!allDay) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Start $hour:00")
                        Spacer(Modifier.weight(1f))
                        InkButton("−") {
                            hour =
                                (hour + 23) % 24
                        }
                        InkButton("+") { hour = (hour + 1) % 24 }
                    }
                }
            }
        }, confirmButton = {
            InkButton("Create offline") {
                if (title.isNotBlank() &&
                    selected.isNotBlank()
                ) {
                    save(selected, title, allDay, hour)
                }
            }
        },
        dismissButton = { InkButton("Cancel", action = close) }
    )
}

@Composable
private fun TaskEditor(lists: List<DavCollectionEntity>, close: () -> Unit, save: (String, String, LocalDate?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(lists.firstOrNull()?.id.orEmpty()) }
    var due by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    AlertDialog(onDismissRequest = close, title = { Text("New task") }, text = {
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
    save: (CalendarEventEntity, String, String, String, Long, Long, Boolean, Boolean) -> Unit,
    delete: (CalendarEventEntity, Boolean) -> Unit
) {
    var title by remember(event.id) { mutableStateOf(event.title) }
    var description by remember(event.id) { mutableStateOf(event.description) }
    var location by remember(event.id) { mutableStateOf(event.location) }
    var start by remember(event.id, occurrence?.id) { mutableLongStateOf(occurrence?.startEpochMillis ?: event.startEpochMillis) }
    var end by remember(event.id, occurrence?.id) { mutableLongStateOf(occurrence?.endEpochMillis ?: event.endEpochMillis) }
    var allDay by remember(event.id, occurrence?.id) { mutableStateOf(occurrence?.allDay ?: event.allDay) }
    var entireSeries by remember(event.id, occurrence?.id) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = {
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
            OutlinedTextField(title, { title = it }, label = { Text("Title") })
            OutlinedTextField(description, { description = it }, label = { Text("Description") })
            OutlinedTextField(location, {
                location =
                    it
            }, label = { Text("Location") })
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
    save: (DavTaskEntity, String, String, Long?) -> Unit,
    delete: (DavTaskEntity) -> Unit
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var notes by remember(task.id) { mutableStateOf(task.notes) }
    var due by remember(task.id) { mutableStateOf(task.dueEpochMillis) }
    AlertDialog(onDismissRequest = close, title = { Text("Edit task offline") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Task") })
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InkButton("No date") { due = null }
                InkButton("Today") {
                    due =
                        LocalDate.now().atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
                InkButton("+ day") {
                    due = CalendarTimeMath.shiftDays(due ?: System.currentTimeMillis(), 1)
                }
            }
        }
    }, confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InkButton("Delete") { delete(task) }
            InkButton("Save offline") { if (title.isNotBlank()) save(task, title, notes, due) }
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

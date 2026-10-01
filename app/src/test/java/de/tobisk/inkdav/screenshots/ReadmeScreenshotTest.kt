package de.tobisk.inkdav.screenshots

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.captureRoboImage
import de.tobisk.inkdav.AppFeature
import de.tobisk.inkdav.Destination
import de.tobisk.inkdav.InkDavApplication
import de.tobisk.inkdav.MainActivity
import de.tobisk.inkdav.MainViewModel
import de.tobisk.inkdav.data.AccountKind
import de.tobisk.inkdav.data.CalendarOccurrenceEntity
import de.tobisk.inkdav.data.CollectionKind
import de.tobisk.inkdav.data.DavAccountEntity
import de.tobisk.inkdav.data.DavCollectionEntity
import de.tobisk.inkdav.data.DavTaskEntity
import de.tobisk.inkdav.data.FileNodeEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders README screenshots of the real app with demo data.
 * Record with `./gradlew :app:recordRoborazzi{Calendar,Todos,Files}Debug`; ordinary test runs only smoke-test the screens.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1024dp-h768dp-land-hdpi", application = ScreenshotApplication::class)
class ReadmeScreenshotTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(SeedDemoData()).around(compose)

    private val model: MainViewModel get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    @Test
    fun home() {
        when (AppFeature.current) {
            AppFeature.CALENDAR -> model.selectedDate.value = DEMO_DATE
            AppFeature.TODOS -> Unit
            AppFeature.FILES -> model.selectFileCollection(FILES_ROOT_ID, "/webdav/")
        }
        capture(AppFeature.current.name.lowercase())
    }

    @Test
    fun sync() {
        model.destination.value = Destination.SYNC
        capture("${AppFeature.current.name.lowercase()}-sync")
    }

    @Test
    fun serverSetup() {
        model.destination.value = Destination.SERVER_SETUP
        capture("${AppFeature.current.name.lowercase()}-servers")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("../docs/screenshots/$name.png")
    }
}

/** Robolectric skips androidx.startup, so WorkManager must exist before the app schedules periodic sync. */
class ScreenshotApplication : InkDavApplication() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(this)
        super.onCreate()
    }
}

private val DEMO_DATE: LocalDate = LocalDate.of(2026, 10, 12)
private const val DAV_ACCOUNT_ID = "demo-dav"
private const val NAS_ACCOUNT_ID = "demo-nas"
private const val FILES_ROOT_ID = "demo-files"

private class SeedDemoData : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val application = ApplicationProvider.getApplicationContext<InkDavApplication>()
            runBlocking { seed(application, withFailingAccount = description.methodName != "home") }
            base.evaluate()
        }
    }

    private suspend fun seed(application: InkDavApplication, withFailingAccount: Boolean) {
        // The database singleton outlives each Robolectric application, so every test starts from empty tables.
        withContext(Dispatchers.IO) { application.container.database.clearAllTables() }
        val dao = application.container.database.dao()
        val synced = DEMO_DATE.atTime(8, 15).millis()
        dao.upsertAccount(
            DavAccountEntity(
                DAV_ACCOUNT_ID,
                "HomeLab",
                "https://calendar.home.arpa/dav/",
                "alex",
                lastSyncAt = synced,
                trustedCertificateSha256 = (0 until 32).joinToString(":") { "%02X".format((it * 37 + 11) % 256) }
            )
        )
        if (withFailingAccount) {
            dao.upsertAccount(
                DavAccountEntity(
                    "demo-work",
                    "Work",
                    "https://dav.example.org/calendars/alex/",
                    "alex@example.org",
                    lastSyncError = "The server certificate is not trusted. If it is self-signed, trust it in the server settings."
                )
            )
        }
        dao.upsertAccount(
            DavAccountEntity(NAS_ACCOUNT_ID, "NASDrive", "https://nas.home.arpa/webdav/", "tablet", AccountKind.NASDRIVE, lastSyncAt = synced)
        )
        val personal = DavCollectionEntity("demo-personal", DAV_ACCOUNT_ID, "/dav/calendars/alex/personal/", "Personal", CollectionKind.CALENDAR, 0xff243b53)
        val family = DavCollectionEntity("demo-family", DAV_ACCOUNT_ID, "/dav/calendars/alex/family/", "Family", CollectionKind.CALENDAR, 0xff8a4b2a)
        val inbox = DavCollectionEntity("demo-inbox", DAV_ACCOUNT_ID, "/dav/calendars/alex/inbox/", "Inbox", CollectionKind.TASK_LIST, 0xff243b53)
        val home = DavCollectionEntity("demo-home", DAV_ACCOUNT_ID, "/dav/calendars/alex/home/", "Home", CollectionKind.TASK_LIST, 0xff4d6b3c)
        val files = DavCollectionEntity(FILES_ROOT_ID, NAS_ACCOUNT_ID, "/webdav/", "NASDrive", CollectionKind.FILE_ROOT)
        dao.upsertCollections(listOf(personal, family, inbox, home, files))

        val month = DEMO_DATE.withDayOfMonth(1)
        val events = listOf(
            Triple(1, 9 to 10, "Team planning" to personal),
            Triple(2, 18 to 20, "Choir rehearsal" to family),
            Triple(5, 7 to 8, "Morning run" to personal),
            Triple(6, 12 to 13, "Lunch with Sam" to personal),
            Triple(8, 15 to 16, "Dentist" to family),
            Triple(11, 10 to 12, "Farmers market" to family),
            Triple(11, 9 to 10, "Design review" to personal),
            Triple(11, 14 to 15, "1:1 with Jordan" to personal),
            Triple(11, 17 to 18, "Pick up kids" to family),
            Triple(13, 19 to 21, "Book club" to family),
            Triple(15, 8 to 9, "Quarterly report due" to personal),
            Triple(18, 11 to 12, "Car service" to family),
            Triple(21, 16 to 17, "Sprint demo" to personal),
            Triple(24, 10 to 18, "Hiking trip" to family),
            Triple(27, 9 to 10, "Release v1.5" to personal),
            Triple(29, 18 to 19, "Parents evening" to family)
        )
        dao.upsertOccurrences(
            events.mapIndexed { index, (day, hours, titled) ->
                val (title, calendar) = titled
                val start = month.withDayOfMonth(day).atTime(hours.first, 0).millis()
                CalendarOccurrenceEntity(
                    id = "occurrence-$index",
                    sourceEventId = "event-$index",
                    collectionId = calendar.id,
                    remoteHref = null,
                    uid = "event-$index",
                    title = title,
                    startEpochMillis = start,
                    endEpochMillis = month.withDayOfMonth(day).atTime(hours.second, 0).millis(),
                    originalStartEpochMillis = start,
                    allDay = false
                )
            }
        )

        val today = LocalDate.now()
        fun due(days: Long, hour: Int) = today.plusDays(days).atTime(hour, 0).millis()
        dao.upsertTasks(
            listOf(
                DavTaskEntity("task-1", inbox.id, uid = "task-1", title = "Renew TLS certificate on calendar server", dueEpochMillis = due(0, 9), priority = 1),
                DavTaskEntity("task-2", inbox.id, uid = "task-2", title = "Reply to Jordan about the roadmap", dueEpochMillis = due(1, 12)),
                DavTaskEntity("task-3", inbox.id, uid = "task-3", title = "Book train tickets", dueEpochMillis = due(3, 18), priority = 5),
                DavTaskEntity("task-4", home.id, uid = "task-4", title = "Water the plants", dueEpochMillis = due(0, 8), recurrenceRule = "FREQ=WEEKLY"),
                DavTaskEntity("task-5", home.id, uid = "task-5", title = "Replace bike chain", dueEpochMillis = due(6, 10)),
                DavTaskEntity("task-6", home.id, uid = "task-6", title = "Sort winter clothes"),
                DavTaskEntity("task-7", inbox.id, uid = "task-7", title = "Send invoice", completedAt = due(-1, 16))
            )
        )

        dao.upsertFiles(
            listOf(
                folder("Documents"),
                folder("Notes"),
                folder("Photos"),
                file("Reading list.md", "text/markdown", 4_812),
                file("Tax return 2025.pdf", "application/pdf", 1_482_113),
                file("Floor plan.png", "image/png", 845_302),
                file("Voice memo.m4a", "audio/mp4", 3_220_441)
            )
        )
    }

    private fun folder(name: String) = FileNodeEntity(
        "file-$name",
        FILES_ROOT_ID,
        "/webdav/",
        "/webdav/$name/",
        name,
        isDirectory = true,
        modifiedAt = DEMO_DATE.atTime(9, 0).millis()
    )

    private fun file(name: String, mimeType: String, size: Long) = FileNodeEntity(
        "file-$name",
        FILES_ROOT_ID,
        "/webdav/",
        "/webdav/$name",
        name,
        isDirectory = false,
        mimeType = mimeType,
        sizeBytes = size,
        modifiedAt = DEMO_DATE.atTime(9, 0).millis()
    )
}

private fun LocalDateTime.millis() = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

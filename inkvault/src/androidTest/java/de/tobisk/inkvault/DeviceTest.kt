package de.tobisk.inkvault

import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import de.tobisk.inkvault.ui.InkCanvas
import de.tobisk.inkvault.ui.PageRenderer
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DeviceTest {
    @Test fun syncProgressIsDelayedAndClearsWhenSyncStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var progress: de.tobisk.inkvault.ui.SyncProgressView
        try {
            instrumentation.runOnMainSync {
                progress = de.tobisk.inkvault.ui.SyncProgressView(activity)
                activity.setContentView(progress)
                progress.setSyncing(true)
                assertEquals(android.view.View.INVISIBLE, progress.visibility)
                progress.setSyncing(false)
            }
            SystemClock.sleep(1700)
            instrumentation.runOnMainSync {
                assertEquals("Short sync must never show a delayed indicator", android.view.View.INVISIBLE, progress.visibility)
                progress.setSyncing(true)
            }
            SystemClock.sleep(1700)
            instrumentation.runOnMainSync {
                assertEquals(android.view.View.VISIBLE, progress.visibility)
                progress.setSyncing(false)
                assertEquals(android.view.View.INVISIBLE, progress.visibility)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun fullRefreshPreservesWritingMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val view = activity.window.decorView
                val epd = com.onyx.android.sdk.api.device.epd.EpdController.getViewDefaultUpdateMode(view)
                if (de.tobisk.inkvault.ui.BooxFirmware.available) {
                    val device = com.onyx.android.sdk.device.Device.currentDevice()
                    assertNotNull(device.javaClass.getDeclaredField("Z").apply { isAccessible = true }.get(null))
                    assertTrue(de.tobisk.inkvault.ui.BooxDisplay.fullRefresh(view))
                    assertEquals(epd, com.onyx.android.sdk.api.device.epd.EpdController.getViewDefaultUpdateMode(view))
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun reportNativeInkCapabilities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val device = com.onyx.android.sdk.device.Device.currentDevice()
            val firmware = runCatching { Class.forName("android.onyx.ViewUpdateHelper").declaredMethods.filter { method -> listOf("pen", "stroke", "scribble", "moveto", "quadto", "handwriting").any { method.name.contains(it, true) } }.joinToString("\n") }.getOrElse { it.toString() }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Firmware drawing methods:\n$firmware\n") })
            val bindings = listOf("i4", "k4", "x0", "y0").joinToString { name ->
                val field = device.javaClass.getDeclaredField(name).apply { isAccessible = true }
                "$name=${field.get(null)}"
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Native bindings: $bindings\n") })
            val method = device.javaClass.getMethod("moveTo", android.view.View::class.java, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Device=${device.javaClass.name}, moveTo owner=${method.declaringClass.name}, penState=${com.onyx.android.sdk.api.device.epd.EpdController.getPenState()}, valid=${com.onyx.android.sdk.api.device.epd.EpdController.isValidPenState()}\n") })
        }
    }

    @Test fun workspaceLaunchesWithAllFourSidebarModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                fun all(view: android.view.View): List<android.view.View> = listOf(view) + if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
                val views = all(activity.window.decorView)
                val app = instrumentation.targetContext.applicationContext as InkVaultApplication
                app.store.meta("lastOpenFile")?.takeIf { app.store.get(it)?.deleted == false }?.let { expected ->
                    val selected = MainActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }
                    assertEquals("Last file should reopen on a fresh activity launch", expected, selected.get(activity))
                }
                for (name in listOf("Close sidebar", "Vault structure", "Document pages", "History", "Settings")) assertTrue("Missing $name", views.any { it.contentDescription == name })
                assertFalse(views.any { it is android.widget.Button && it.text.toString() == "Save" })
                fun fieldView(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as android.view.View
                val toolPosition = IntArray(2)
                val contentPosition = IntArray(2)
                fieldView("tools").getLocationOnScreen(toolPosition)
                fieldView("content").getLocationOnScreen(contentPosition)
                assertEquals("Toolbar must start at the document edge", contentPosition[0], toolPosition[0])
                val close = views.single { it.contentDescription == "Close sidebar" }
                val tabs = views.single { it.contentDescription == "Vault structure" }
                assertSame(close.parent, (tabs.parent as android.view.View).parent)
                close.performClick()
                assertEquals(android.view.View.GONE, (tabs.parent as android.view.View).visibility)
                assertEquals("Open sidebar", close.contentDescription)
                close.performClick()
                assertEquals(android.view.View.VISIBLE, (tabs.parent as android.view.View).visibility)
                for (name in listOf("Folders", "Files")) {
                    val heading = views.filterIsInstance<android.widget.TextView>().first { it.text.toString() == name }
                    assertEquals(4, (heading.parent as android.view.ViewGroup).childCount)
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun stylusWritesFingerAndReadModeDoNot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val canvas = InkCanvas(instrumentation.targetContext)
            canvas.layout(0, 0, 1000, 1400)
            canvas.writable = true
            canvas.pressureSensitivity = 0.5f
            fun tap(tool: Int) {
                val properties = MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = tool
                }
                val coordinates = MotionEvent.PointerCoords().apply {
                    x = 400f
                    y = 400f
                    pressure = 0.5f
                    size = 1f
                }
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time + 10, action, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
                    canvas.onTouchEvent(event)
                    event.recycle()
                }
            }
            tap(MotionEvent.TOOL_TYPE_FINGER)
            assertTrue(canvas.page.strokes.isEmpty())
            tap(MotionEvent.TOOL_TYPE_STYLUS)
            assertEquals(1, canvas.page.strokes.size)
            assertEquals(750L, canvas.page.strokes.single().points.first().pressure)
            canvas.writable = false
            tap(MotionEvent.TOOL_TYPE_STYLUS)
            assertEquals(1, canvas.page.strokes.size)
            canvas.undo()
            assertTrue(canvas.page.strokes.isEmpty())
            canvas.redo()
            assertEquals(1, canvas.page.strokes.size)
        }
    }

    @Test fun densePageAcceptsIncrementalStrokeWithoutReencodingHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = InkCanvas(instrumentation.targetContext)
            view.layout(0, 0, 1000, 1400)
            val strokes = (0 until 1000).map { row -> de.tobisk.inkvault.ink.InkStroke(points = (0 until 16).map { point -> de.tobisk.inkvault.ink.InkPoint(10000L + point * 500, 10000L + row * 100, point.toLong()) }, style = de.tobisk.inkvault.ink.InkStyle()) }
            view.show(de.tobisk.inkvault.ink.InkPage(strokes = strokes))
            view.writable = true
            val bitmap = android.graphics.Bitmap.createBitmap(1000, 1400, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            view.draw(canvas)
            val properties = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_STYLUS
            }
            val coords = MotionEvent.PointerCoords().apply {
                x = 400f
                y = 400f
                pressure = 1f
            }
            val elapsed = mutableListOf<Long>()
            val start = SystemClock.uptimeMillis()
            for (index in 0..65) {
                val event = MotionEvent.obtain(
                    start, start + index * 8,
                    if (index == 0) {
                        MotionEvent.ACTION_DOWN
                    } else if (index == 65) {
                        MotionEvent.ACTION_UP
                    } else {
                        MotionEvent.ACTION_MOVE
                    },
                    1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0
                )
                val before = System.nanoTime()
                view.onTouchEvent(event)
                view.draw(canvas)
                elapsed.add(System.nanoTime() - before)
                coords.x += 2
                coords.y += 3
                event.recycle()
            }
            assertEquals(1001, view.page.strokes.size)
            assertEquals(66, view.page.strokes.last().points.size)
            android.util.Log.i("InkVaultBenchmark", "1000 strokes / 16000 existing points: input+draw p95=${elapsed.sorted()[62] / 1000000.0}ms penUp=${elapsed.last() / 1000000.0}ms")
            view.undo()
            assertEquals(1000, view.page.strokes.size)
            bitmap.recycle()
        }
    }

    @Test fun nativePreviewSurvivesFrameworkEventRecycling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var view: InkCanvas
        try {
            instrumentation.runOnMainSync {
                view = InkCanvas(activity).apply { writable = true }
                activity.setContentView(view)
            }
            instrumentation.waitForIdleSync()
            repeat(120) { stroke ->
                instrumentation.runOnMainSync {
                    view.pressureSensitivity = if (stroke % 2 == 0) 1f else 0.5f
                    view.style = de.tobisk.inkvault.ink.InkStyle(pressure = true)
                    val props = MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_STYLUS
                    }
                    val coords = MotionEvent.PointerCoords().apply {
                        x = view.width / 2f
                        y = view.height / 2f
                        pressure = 0.5f
                    }
                    val time = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(time, time + 1, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
                        view.onTouchEvent(event)
                        event.recycle()
                        coords.x += 2f
                    }
                }
                if (stroke % 20 == 0) System.gc()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(120, view.page.strokes.size)
                if (de.tobisk.inkvault.ui.BooxPenBridge.supported) {
                    assertEquals(120, view.directInkStrokes)
                    assertEquals(360, view.directInkPoints)
                    instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Native submissions: ${view.directInkPoints}, max call ${view.directInkMaxSubmitNanos / 1000000.0} ms\n") })
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun fiveHundredPagePdfRendersOnlyRequestedPageWithinBitmapBound() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "500-page-test.pdf")
        try {
            val document = PdfDocument()
            try {
                repeat(500) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    document.finishPage(page)
                }
                file.outputStream().use { document.writeTo(it) }
            } finally {
                document.close()
            }
            val dimensions = PageRenderer.dimensions(file)
            assertEquals(500, dimensions.size)
            val page = PageRenderer.pdf(file, 499)
            try {
                assertTrue(page.allocationByteCount <= 1600 * 2000 * 4)
                assertTrue(page.width > 0)
            } finally {
                page.recycle()
            }
        } finally {
            file.delete()
        }
    }
}

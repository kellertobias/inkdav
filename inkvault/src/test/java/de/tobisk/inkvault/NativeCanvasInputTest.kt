package de.tobisk.inkvault

import android.app.Application
import android.view.MotionEvent
import de.tobisk.inkvault.ink.*
import de.tobisk.inkvault.ui.InkCanvas
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Runs on the host JVM: no tablet installation, application lifecycle or vault access. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class NativeCanvasInputTest {
    private fun canvas() = InkCanvas(RuntimeEnvironment.getApplication()).apply {
        hardwareEnabled = false
        infinite = true
        writable = true
        layout(0, 0, 1000, 1400)
        show(InkPage())
    }
    private var time = 0L
    private fun event(view: InkCanvas, action: Int, vararg pointers: Triple<Int, Float, Float>) {
        time += 20
        val properties = pointers.mapIndexed { index, p ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = p.first
            }
        }.toTypedArray()
        val coordinates = pointers.map { p ->
            MotionEvent.PointerCoords().apply {
                x = p.second
                y = p.third
                pressure = 1f
            }
        }.toTypedArray()
        MotionEvent.obtain(0, time, action, pointers.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0).also {
            view.onTouchEvent(it)
            it.recycle()
        }
    }
    private fun finger(x: Float, y: Float) = Triple(MotionEvent.TOOL_TYPE_FINGER, x, y)
    private fun pen(x: Float, y: Float) = Triple(MotionEvent.TOOL_TYPE_STYLUS, x, y)
    private fun stroke(view: InkCanvas) {
        event(view, MotionEvent.ACTION_DOWN, pen(100f, 100f))
        event(view, MotionEvent.ACTION_MOVE, pen(200f, 200f))
        event(view, MotionEvent.ACTION_UP, pen(250f, 200f))
    }

    @Test fun fingersNeverEditWithAnySelectedTool() {
        val view = canvas()
        stroke(view)
        val written = view.page
        for (tool in listOf("pen", "eraser", "lasso", "rectangle", "ellipse", "arrow")) {
            view.tool = tool
            val before = view.viewportCenter()
            event(view, MotionEvent.ACTION_DOWN, finger(100f, 100f))
            event(view, MotionEvent.ACTION_MOVE, finger(200f, 200f))
            event(view, MotionEvent.ACTION_UP, finger(200f, 200f))
            assertEquals(written, view.page)
            assertNotEquals(before, view.viewportCenter())
        }
    }

    @Test fun pencilWritesBeyondPageBoundsAndUndoRedoWork() {
        val view = canvas()
        event(view, MotionEvent.ACTION_DOWN, finger(100f, 100f))
        event(view, MotionEvent.ACTION_MOVE, finger(900f, 1000f))
        event(view, MotionEvent.ACTION_UP, finger(900f, 1000f))
        stroke(view)
        assertEquals(1, view.page.strokes.size)
        assertTrue(view.page.strokes.single().points.first().x < 0)
        val written = view.page
        view.undo()
        assertTrue(view.page.strokes.isEmpty())
        view.redo()
        assertEquals(written, view.page)
        event(view, MotionEvent.ACTION_DOWN, pen(100f, 100f))
        event(view, MotionEvent.ACTION_MOVE, pen(150f, 200f))
        event(view, MotionEvent.ACTION_CANCEL, pen(150f, 200f))
        assertEquals(written, view.page)
    }

    @Test fun pinchOnlyChangesViewportAndPointerLiftDoesNotJump() {
        val view = canvas()
        val original = view.page
        event(view, MotionEvent.ACTION_DOWN, finger(100f, 100f))
        event(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), finger(100f, 100f), finger(500f, 500f))
        repeat(5) { i -> event(view, MotionEvent.ACTION_MOVE, finger(100f - i * 10, 100f - i * 10), finger(500f + i * 20, 500f + i * 20)) }
        event(view, MotionEvent.ACTION_POINTER_UP or (1 shl 8), finger(60f, 60f), finger(580f, 580f))
        val center = view.viewportCenter()
        event(view, MotionEvent.ACTION_MOVE, finger(60f, 60f))
        assertEquals(center, view.viewportCenter())
        assertEquals(original, view.page)
    }

    @Test fun pencilCanJoinFingerGestureWithoutFingerEditingOrJumpOnExit() {
        val view = canvas()
        event(view, MotionEvent.ACTION_DOWN, finger(100f, 100f))
        event(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), finger(100f, 100f), pen(300f, 300f))
        event(view, MotionEvent.ACTION_MOVE, finger(200f, 200f), pen(350f, 350f))
        event(view, MotionEvent.ACTION_POINTER_UP or (1 shl 8), finger(200f, 200f), pen(400f, 400f))
        val center = view.viewportCenter()
        event(view, MotionEvent.ACTION_MOVE, finger(200f, 200f))
        assertEquals(center, view.viewportCenter())
        assertEquals(1, view.page.strokes.size)
    }
}

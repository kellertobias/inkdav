package de.tobisk.inkvault

import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import de.tobisk.inkvault.ink.*
import de.tobisk.inkvault.ui.InkCanvas
import org.junit.Assert.*
import org.junit.Test

/** Use a dedicated emulator. This test never opens an Activity, database or user vault. */
class InfiniteCanvasTest {
    @Test fun fingerNavigatesPencilEditsAndUndoRestores() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = InkCanvas(instrumentation.targetContext).apply {
                infinite = true
                hardwareEnabled = false
                writable = true
                layout(0, 0, 1000, 1400)
                show(InkPage())
            }
            fun event(action: Int, x: Float, y: Float, tool: Int) {
                val properties = arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = tool
                    }
                )
                val coordinates = arrayOf(
                    MotionEvent.PointerCoords().apply {
                        this.x = x
                        this.y = y
                        pressure = 1f
                    }
                )
                MotionEvent.obtain(0, 10, action, 1, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0).also {
                    view.onTouchEvent(it)
                    it.recycle()
                }
            }
            fun drag(tool: Int) {
                event(MotionEvent.ACTION_DOWN, 100f, 100f, tool)
                event(MotionEvent.ACTION_MOVE, 250f, 200f, tool)
                event(MotionEvent.ACTION_UP, 250f, 200f, tool)
            }
            val center = view.viewportCenter()
            drag(MotionEvent.TOOL_TYPE_FINGER)
            assertTrue(view.page.strokes.isEmpty())
            assertNotEquals(center, view.viewportCenter())
            drag(MotionEvent.TOOL_TYPE_STYLUS)
            assertEquals(1, view.page.strokes.size)
            val written = view.page
            view.tool = "eraser"
            drag(MotionEvent.TOOL_TYPE_FINGER)
            assertEquals(written, view.page)
            view.undo()
            assertTrue(view.page.strokes.isEmpty())
            view.redo()
            assertEquals(written, view.page)
            val beforeCancel = view.page
            view.tool = "pen"
            event(MotionEvent.ACTION_DOWN, 20f, 20f, MotionEvent.TOOL_TYPE_STYLUS)
            event(MotionEvent.ACTION_MOVE, 40f, 60f, MotionEvent.TOOL_TYPE_STYLUS)
            event(MotionEvent.ACTION_CANCEL, 40f, 60f, MotionEvent.TOOL_TYPE_STYLUS)
            assertEquals(beforeCancel, view.page)
        }
    }
}

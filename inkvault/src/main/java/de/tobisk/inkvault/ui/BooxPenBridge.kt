package de.tobisk.inkvault.ui

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Looper
import android.view.MotionEvent
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.onyx.android.sdk.rx.RxManager
import de.tobisk.inkvault.ink.InkStyle

/** BOOX draws live ink directly on the panel. Android samples remain the durable source. */
class BooxPenBridge(private val view: InkCanvas) {
    private var helper: TouchHelper? = null
    private var configuredBounds: Rect? = null
    private var configuredWidth = 0f
    private var configuredStyle: InkStyle? = null
    var submittedPoints = 0
        private set
    var maxSubmitNanos = 0L
        private set
    var active = false
        private set

    @Volatile var drawing = false
        private set

    @Volatile var receivedStrokes = 0
        private set
    var failure: String? = null
        private set
    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(predicted: Boolean, point: TouchPoint) {
            drawing = true
        }
        override fun onEndRawDrawing(predicted: Boolean, point: TouchPoint) {
            drawing = false
            receivedStrokes++
        }
        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) = Unit
        override fun onRawDrawingTouchPointListReceived(points: TouchPointList) = Unit
        override fun onBeginRawErasing(predicted: Boolean, point: TouchPoint) {
            drawing = true
        }
        override fun onEndRawErasing(predicted: Boolean, point: TouchPoint) {
            drawing = false
        }
        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) = Unit
        override fun onRawErasingTouchPointListReceived(points: TouchPointList) = Unit
        override fun onPenUpRefresh(rect: RectF) {
            // Let the normal surface present the committed source after the native preview.
            view.post {
                if (active && !drawing) {
                    pause()
                    view.invalidate()
                    view.postDelayed({ view.resumeHardware() }, 100)
                }
            }
        }
    }
    fun configure(bounds: Rect, width: Float, style: InkStyle) {
        if (!supported || !BooxFirmware.available || failure != null || bounds.isEmpty) return
        if (active && configuredBounds == bounds && configuredWidth == width && configuredStyle == style) return
        try {
            val pen = helper ?: run {
                RxManager.Builder.initAppContext(view.context.applicationContext)
                TouchHelper.create(view, TouchHelper.FEATURE_APP_PEN_TOUCH_RENDER, callback, false).also {
                    helper = it
                    // TouchHelper configures the hardware region and lifecycle only.
                    // Input is submitted synchronously to EpdController below.
                    it.setTouchListenerEnabled(false)
                    view.setOnTouchListener(null)
                    it.setPostInputEvent(true)
                    it.enableFingerTouch(false)
                    it.setPenUpRefreshEnabled(true)
                    it.setPenUpRefreshTimeMs(120)
                }
            }
            pen.setLimitRect(bounds, emptyList())
            if (!pen.isRawDrawingCreated) pen.openRawDrawing()
            pen.setStrokeWidth(width.coerceAtLeast(1f)).setStrokeColor(style.color.toInt())
                .setStrokeStyle(
                    when {
                        style.tool == "marker" -> TouchHelper.STROKE_STYLE_MARKER
                        style.extra["brushType"] == "Pencil" -> TouchHelper.STROKE_STYLE_CHARCOAL_V2
                        else -> {
                            // Fountain adds a firmware-specific speed curve. The stable pen
                            // path plus the explicit pressure values below matches InkCanvas.
                            TouchHelper.STROKE_STYLE_PENCIL
                        }
                    }
                )
            pen.setRawDrawingRenderEnabled(true).setRawDrawingEnabled(true)
            active = pen.isRawDrawingInputEnabled && pen.isRawDrawingRenderEnabled
            configuredBounds = Rect(bounds)
            configuredWidth = width
            configuredStyle = style
        } catch (e: Exception) {
            disable(e)
        } catch (e: LinkageError) {
            disable(e)
        }
    }
    fun process(event: MotionEvent) {
        if (!active || event.pointerCount == 0) return
        val pointer = (0 until event.pointerCount).firstOrNull { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS } ?: return
        val started = System.nanoTime()
        try {
            val style = configuredStyle ?: return
            fun submit(history: Int?) {
                val x = if (history == null) event.getX(pointer) else event.getHistoricalX(pointer, history)
                val y = if (history == null) event.getY(pointer) else event.getHistoricalY(pointer, history)
                if (style.tool == "pen") EpdController.quadTo(view, x, y, UpdateMode.DU, FIXED_PRESSURE) else EpdController.quadTo(view, x, y, UpdateMode.DU)
                submittedPoints++
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (configuredBounds?.contains(event.getX(pointer).toInt(), event.getY(pointer).toInt()) != true) return
                    drawing = true
                    if (style.tool == "pen") EpdController.moveTo(view, event.getX(pointer), event.getY(pointer), configuredWidth, FIXED_PRESSURE) else EpdController.moveTo(view, event.getX(pointer), event.getY(pointer), configuredWidth)
                    submittedPoints++
                }
                MotionEvent.ACTION_MOVE -> if (drawing) {
                    for (sample in 0 until event.historySize) submit(sample)
                    submit(null)
                }
                MotionEvent.ACTION_UP -> if (drawing) {
                    submit(null)
                    EpdController.penUp()
                    drawing = false
                    receivedStrokes++
                }
                MotionEvent.ACTION_CANCEL -> if (drawing) {
                    EpdController.penUp()
                    drawing = false
                }
            }
        } catch (e: Exception) {
            disable(e)
        } catch (e: LinkageError) {
            disable(e)
        } finally {
            maxSubmitNanos = maxOf(maxSubmitNanos, System.nanoTime() - started)
        }
    }
    fun pause() {
        active = false
        try {
            if (drawing) EpdController.penUp()
            drawing = false
            helper?.setRawDrawingEnabled(false)
        } catch (_: Exception) { } catch (_: LinkageError) { }
    }
    fun close() {
        pause()
        try {
            helper?.closeRawDrawing()
        } catch (_: Exception) { } catch (_: LinkageError) { }
        helper = null
    }
    private fun disable(error: Throwable) {
        failure = error.message ?: error.javaClass.simpleName
        close()
        if (Looper.myLooper() == Looper.getMainLooper()) view.failed("BOOX direct ink unavailable: $failure") else view.post { view.failed("BOOX direct ink unavailable: $failure") }
    }
    companion object {
        /** Neutral native pressure uses the same width scale as Android Canvas. */
        const val WIDTH_CALIBRATION = 1f

        /** BOOX charcoal spreads beyond its configured centerline width. */
        const val PENCIL_WIDTH_CALIBRATION = 0.6f
        private const val FIXED_PRESSURE = 1f
        val supported get() = Build.MANUFACTURER.contains("onyx", true) || Build.BRAND.contains("onyx", true)
    }
}

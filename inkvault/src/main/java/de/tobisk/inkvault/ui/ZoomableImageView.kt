package de.tobisk.inkvault.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

/** Fits an image initially, then keeps the point under the fingers fixed while zooming. */
class ZoomableImageView(context: Context, private val bitmap: Bitmap) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val detector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(scale: ScaleGestureDetector): Boolean {
                val old = zoom
                zoom = (zoom * scale.scaleFactor).coerceIn(1f, 8f)
                val ratio = zoom / old
                val oldCenterX = (width - bitmap.width * min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * old) / 2
                val oldCenterY = (height - bitmap.height * min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * old) / 2
                val newCenterX = (width - bitmap.width * min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * zoom) / 2
                val newCenterY = (height - bitmap.height * min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * zoom) / 2
                panX = scale.focusX - newCenterX - (scale.focusX - oldCenterX - panX) * ratio
                panY = scale.focusY - newCenterY - (scale.focusY - oldCenterY - panY) * ratio
                invalidate()
                return true
            }
        }
    )

    init {
        setBackgroundColor(Color.WHITE)
        contentDescription = "Graphic. Pinch to zoom; drag to pan."
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val fit = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        matrix.reset()
        matrix.setScale(fit * zoom, fit * zoom)
        matrix.postTranslate((width - bitmap.width * fit * zoom) / 2 + panX, (height - bitmap.height * fit * zoom) / 2 + panY)
        canvas.drawBitmap(bitmap, matrix, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_POINTER_UP -> dragging = false
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && dragging && !detector.isInProgress && zoom > 1f) {
                panX += event.x - lastX
                panY += event.y - lastY
                invalidate()
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}

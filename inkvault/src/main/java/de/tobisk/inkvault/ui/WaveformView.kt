package de.tobisk.inkvault.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class WaveformView(context: Context) : View(context) {
    var samples = emptyList<Float>()
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 2f
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val center = height / 2f
        canvas.drawLine(0f, center, width.toFloat(), center, paint)
        samples.forEachIndexed { index, sample ->
            val x = width * index / 40f
            val amplitude = (sample * center).coerceAtLeast(1f)
            canvas.drawLine(x, center - amplitude, x, center + amplitude, paint)
        }
    }
}

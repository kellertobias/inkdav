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
    private val baseline = Paint().apply {
        color = Color.BLACK
        strokeWidth = resources.displayMetrics.density
    }
    private val waveform = Paint().apply {
        color = Color.BLACK
        strokeWidth = resources.displayMetrics.density * 3f
        strokeCap = Paint.Cap.ROUND
    }
    override fun onDraw(canvas: Canvas) {
        val center = height / 2f
        canvas.drawLine(0f, center, width.toFloat(), center, baseline)
        samples.forEachIndexed { index, sample ->
            val x = if (samples.size <= 1) width / 2f else (width - 1) * index / (samples.size - 1f)
            val amplitude = (sample * center).coerceAtLeast(1f)
            canvas.drawLine(x, center - amplitude, x, center + amplitude, waveform)
        }
    }
}

package de.tobisk.inkvault.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/** Delayed, indeterminate activity bar with low-frequency updates for e-ink. */
class SyncProgressView(context: Context) : View(context) {
    private val paint = Paint().apply { color = Color.WHITE }
    private var started: Long? = null
    private val update = object : Runnable {
        override fun run() {
            val start = started ?: return
            if (!isAttachedToWindow || windowVisibility != VISIBLE) return
            val elapsed = SystemClock.elapsedRealtime() - start
            if (elapsed < 1500) {
                postDelayed(this, 1500 - elapsed)
                return
            }
            visibility = VISIBLE
            invalidate()
            postDelayed(this, 600)
        }
    }

    init {
        visibility = INVISIBLE
        contentDescription = "Sync in progress"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setSyncing(syncing: Boolean) {
        if (syncing == (started != null)) return
        removeCallbacks(update)
        started = if (syncing) SystemClock.elapsedRealtime() else null
        visibility = INVISIBLE
        if (syncing) post(update)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val start = started ?: return
        val segment = width / 4f
        val step = ((SystemClock.elapsedRealtime() - start - 1500).coerceAtLeast(0) / 600 % 4).toInt()
        canvas.drawRect(step * segment, 0f, (step + 1) * segment, height.toFloat(), paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(update)
        if (started != null) post(update)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(update)
        if (visibility == VISIBLE && started != null) post(update)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(update)
        super.onDetachedFromWindow()
    }
}

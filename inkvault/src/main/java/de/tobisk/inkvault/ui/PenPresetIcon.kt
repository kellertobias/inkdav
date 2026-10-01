package de.tobisk.inkvault.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import de.tobisk.inkvault.ink.InkPresetSize
import kotlin.math.sqrt
import org.json.JSONObject

/** Live preset glyph; the small swatch grows with the selected stroke width. */
class PenPresetIcon(private val preset: JSONObject) : Drawable() {
    var active = false
        set(value) {
            field = value
            invalidateSelf()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        val type = preset.optString("type", if (preset.optString("tool") == "marker") "Marker" else "Pen")
        LineIcon(type.lowercase(), if (active) Color.WHITE else Color.BLACK).apply { setBounds(0, 0, 21, 21) }.draw(canvas)
        val normalized = (preset.optLong("width", InkPresetSize.MIN).coerceIn(InkPresetSize.MIN, InkPresetSize.MAX) - InkPresetSize.MIN).toFloat() / (InkPresetSize.MAX - InkPresetSize.MIN)
        val radius = 1.5f + 2.5f * sqrt(normalized)
        paint.style = Paint.Style.FILL
        paint.color = if (active) Color.BLACK else Color.WHITE
        canvas.drawCircle(19f, 19f, radius + 1f, paint)
        paint.color = preset.optLong("color", 0xff000000).toInt()
        canvas.drawCircle(19f, 19f, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.7f
        paint.color = if (active) Color.WHITE else Color.BLACK
        canvas.drawCircle(19f, 19f, radius, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

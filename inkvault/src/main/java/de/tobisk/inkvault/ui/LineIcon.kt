package de.tobisk.inkvault.ui

import android.graphics.*
import android.graphics.drawable.Drawable

/** Original 24-unit line icons, kept at full contrast on electronic paper. */
class LineIcon(private val name: String, private val ink: Int = Color.BLACK) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        if (name == "redo") {
            canvas.translate(24f, 0f)
            canvas.scale(-1f, 1f)
        }
        fun line(vararg p: Float) {
            val path = Path()
            path.moveTo(p[0], p[1])
            for (i in 2 until p.size step 2) path.lineTo(p[i], p[i + 1])
            canvas.drawPath(path, paint)
        }
        fun rect(l: Float, t: Float, r: Float, b: Float) = canvas.drawRect(l, t, r, b, paint)
        when (name) {
            "cloud" -> {
                val cloud = Path()
                cloud.moveTo(6f, 19f)
                cloud.cubicTo(0f, 19f, 1f, 10f, 6f, 10f)
                cloud.cubicTo(5f, 2f, 17f, 2f, 18f, 10f)
                cloud.cubicTo(24f, 9f, 24f, 19f, 18f, 19f)
                cloud.lineTo(6f, 19f)
                canvas.drawPath(cloud, paint)
            }
            "pause" -> {
                line(8f, 5f, 8f, 19f)
                line(16f, 5f, 16f, 19f)
            }
            "thickPen" -> {
                rect(6f, 4f, 18f, 16f)
                line(6f, 16f, 12f, 22f, 18f, 16f)
            }
            "menu" -> {
                line(4f, 6f, 20f, 6f)
                line(4f, 12f, 20f, 12f)
                line(4f, 18f, 20f, 18f)
            }
            "folder", "move" -> {
                line(3f, 20f, 3f, 5f, 10f, 5f, 12f, 8f, 21f, 8f, 21f, 20f, 3f, 20f)
                if (name == "move") {
                    line(7f, 14f, 17f, 14f)
                    line(14f, 11f, 17f, 14f, 14f, 17f)
                }
            }
            "pages", "duplicate" -> {
                rect(7f, 6f, 20f, 21f)
                line(4f, 17f, 4f, 3f, 16f, 3f)
            }
            "history", "undo", "redo", "sync" -> {
                canvas.drawArc(4f, 4f, 20f, 20f, 210f, 290f, false, paint)
                line(3f, 3f, 3f, 9f, 9f, 9f)
                if (name == "history") line(12f, 7f, 12f, 12f, 16f, 14f)
            }
            "refreshDisplay" -> {
                rect(2f, 3f, 22f, 21f)
                canvas.drawArc(7f, 7f, 17f, 17f, 215f, 285f, false, paint)
                line(6f, 6f, 6f, 11f, 11f, 11f)
            }
            "settings" -> {
                line(4f, 6f, 20f, 6f)
                line(4f, 12f, 20f, 12f)
                line(4f, 18f, 20f, 18f)
                rect(7f, 4f, 10f, 8f)
                rect(14f, 10f, 17f, 14f)
                rect(7f, 16f, 10f, 20f)
            }
            "metadata" -> {
                rect(4f, 3f, 16f, 21f)
                line(8f, 8f, 12f, 8f)
                line(8f, 12f, 12f, 12f)
                line(8f, 16f, 12f, 16f)
                canvas.drawCircle(18f, 16f, 4f, paint)
                line(18f, 14f, 18f, 18f)
                line(16f, 16f, 20f, 16f)
            }
            "add" -> {
                line(12f, 4f, 12f, 20f)
                line(4f, 12f, 20f, 12f)
            }
            "delete" -> {
                line(4f, 6f, 20f, 6f)
                line(9f, 3f, 15f, 3f)
                line(6f, 6f, 7f, 21f, 17f, 21f, 18f, 6f)
                line(10f, 10f, 10f, 17f)
                line(14f, 10f, 14f, 17f)
            }
            "collapse" -> {
                line(4f, 8f, 12f, 3f, 20f, 8f)
                line(4f, 16f, 12f, 21f, 20f, 16f)
                line(4f, 12f, 20f, 12f)
            }
            "eye" -> {
                val path = Path()
                path.moveTo(2f, 12f)
                path.quadTo(12f, -1f, 22f, 12f)
                path.quadTo(12f, 25f, 2f, 12f)
                canvas.drawPath(path, paint)
                canvas.drawCircle(12f, 12f, 3f, paint)
            }
            "tree" -> {
                rect(3f, 3f, 9f, 8f)
                line(6f, 8f, 6f, 19f, 14f, 19f)
                line(6f, 12f, 14f, 12f)
                rect(14f, 9f, 21f, 14f)
                rect(14f, 17f, 21f, 22f)
            }
            "play" -> line(7f, 3f, 21f, 12f, 7f, 21f, 7f, 3f)
            "record" -> {
                paint.style = Paint.Style.FILL
                paint.color = Color.RED
                canvas.drawCircle(12f, 12f, 8f, paint)
                paint.style = Paint.Style.STROKE
                paint.color = ink
                canvas.drawCircle(12f, 12f, 8f, paint)
            }
            "stop" -> rect(5f, 5f, 19f, 19f)
            "previous" -> line(16f, 4f, 8f, 12f, 16f, 20f)
            "next" -> line(8f, 4f, 16f, 12f, 8f, 20f)
            "pen" -> {
                line(3f, 21f, 6f, 9f, 14f, 3f, 21f, 10f, 15f, 18f, 3f, 21f)
                line(3f, 21f, 11f, 13f)
                canvas.drawCircle(12f, 12f, 1.5f, paint)
                line(14f, 3f, 17f, 1f, 23f, 7f, 21f, 10f)
            }
            "marker" -> {
                line(3f, 21f, 4f, 15f, 15f, 4f, 21f, 10f, 10f, 21f, 3f, 21f)
                line(4f, 15f, 10f, 21f)
                line(13f, 6f, 19f, 12f)
                line(3f, 21f, 7f, 17f)
            }
            "pens" -> {
                line(2f, 21f, 3f, 13f, 13f, 3f, 17f, 7f, 7f, 17f, 2f, 21f)
                line(11f, 21f, 12f, 14f, 19f, 7f, 23f, 11f, 16f, 18f, 11f, 21f)
                line(10f, 6f, 14f, 10f)
                line(17f, 9f, 21f, 13f)
            }
            "pencil", "rename" -> {
                line(4f, 20f, 6f, 13f, 17f, 2f, 22f, 7f, 11f, 18f, 4f, 20f)
                line(14f, 5f, 19f, 10f)
                line(6f, 13f, 11f, 18f)
            }
            "eraser" -> {
                line(3f, 14f, 14f, 3f, 22f, 11f, 12f, 21f, 10f, 21f, 3f, 14f)
                line(8f, 9f, 16f, 17f)
                line(10f, 21f, 22f, 21f)
            }
            "lasso" -> {
                canvas.drawOval(3f, 3f, 21f, 16f, paint)
                line(7f, 15f, 5f, 21f, 10f, 21f)
            }
            "image" -> {
                rect(3f, 3f, 21f, 21f)
                canvas.drawCircle(8f, 8f, 2f, paint)
                line(3f, 19f, 10f, 12f, 14f, 16f, 18f, 11f, 21f, 15f)
            }
            "shapes" -> {
                rect(2f, 3f, 12f, 13f)
                canvas.drawCircle(16f, 16f, 6f, paint)
            }
            "fit" -> {
                line(3f, 9f, 3f, 3f, 9f, 3f)
                line(15f, 3f, 21f, 3f, 21f, 9f)
                line(3f, 15f, 3f, 21f, 9f, 21f)
                line(15f, 21f, 21f, 21f, 21f, 15f)
            }
            "table" -> {
                rect(3f, 3f, 21f, 21f)
                line(3f, 9f, 21f, 9f)
                line(3f, 15f, 21f, 15f)
                line(9f, 3f, 9f, 21f)
                line(15f, 3f, 15f, 21f)
            }
            "bullet", "numbered", "recordings" -> {
                for (y in listOf(6f, 12f, 18f)) {
                    canvas.drawCircle(4f, y, 1f, paint)
                    line(9f, y, 21f, y)
                }
            }
            "quote" -> {
                line(4f, 7f, 9f, 7f, 9f, 13f, 5f, 17f)
                line(14f, 7f, 19f, 7f, 19f, 13f, 15f, 17f)
            }
            "section" -> {
                line(3f, 4f, 21f, 4f)
                line(3f, 20f, 21f, 20f)
                line(3f, 12f, 7f, 12f)
                line(10f, 12f, 14f, 12f)
                line(17f, 12f, 21f, 12f)
            }
            "heading" -> {
                line(5f, 4f, 5f, 20f)
                line(19f, 4f, 19f, 20f)
                line(5f, 12f, 19f, 12f)
            }
            "bold" -> {
                line(6f, 3f, 6f, 21f)
                val path = Path()
                path.moveTo(6f, 3f)
                path.cubicTo(23f, 1f, 23f, 13f, 6f, 12f)
                path.cubicTo(24f, 10f, 24f, 23f, 6f, 21f)
                canvas.drawPath(path, paint)
            }
            "italic" -> {
                line(10f, 3f, 21f, 3f)
                line(3f, 21f, 14f, 21f)
                line(16f, 3f, 8f, 21f)
            }
            else -> {
                rect(4f, 3f, 20f, 21f)
                line(8f, 8f, 16f, 8f)
                line(8f, 12f, 16f, 12f)
                line(8f, 16f, 13f, 16f)
            }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

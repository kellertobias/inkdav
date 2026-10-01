package de.tobisk.inkvault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.caverock.androidsvg.SVG
import java.io.File
import kotlin.math.min

object PageRenderer {
    /** At most one ~8 MiB PDF bitmap; PDF page size is never changed by fit-to-page. */
    fun pdf(file: File, index: Int, maxWidth: Int = 1600, maxHeight: Int = 2000): Bitmap = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
        renderer.openPage(index).use { page ->
            val scale = min(maxWidth.toFloat() / page.width, maxHeight.toFloat() / page.height)
            Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }
    fun dimensions(file: File): List<Pair<Long, Long>> = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
        require(renderer.pageCount in 1..500) { "PDF must contain 1–500 pages" }
        (0 until renderer.pageCount).map { index -> renderer.openPage(index).use { ((it.width * 25400L / 72) to (it.height * 25400L / 72)) } }
    }
    fun image(file: File, svg: Boolean, maxWidth: Int = 1600, maxHeight: Int = 2000, allowSvgUpscale: Boolean = false): Bitmap? {
        if (svg) {
            return SVG.getFromInputStream(file.inputStream()).let { document ->
                val box = document.documentViewBox
                val width = (box?.width() ?: document.documentWidth).takeIf { it > 0 && it.isFinite() } ?: 1200f
                val height = (box?.height() ?: document.documentHeight).takeIf { it > 0 && it.isFinite() } ?: 1200f
                val scale = min(maxWidth.toFloat() / width, maxHeight.toFloat() / height).coerceAtMost(if (allowSvgUpscale) 8f else 1f)
                Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    document.setDocumentWidth(it.width.toFloat())
                    document.setDocumentHeight(it.height.toFloat())
                    document.renderToCanvas(Canvas(it))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = min(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height).coerceAtMost(1f)
        if (scale == 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true).also { bitmap.recycle() }
    }
}

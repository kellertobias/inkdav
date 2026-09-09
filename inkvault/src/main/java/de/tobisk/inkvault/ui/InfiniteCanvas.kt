package de.tobisk.inkvault.ui

import android.content.Context
import android.graphics.*
import android.util.Base64
import de.tobisk.inkvault.data.VaultStore
import de.tobisk.inkvault.ink.*
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

/** A native InkCanvas session, with Excalidraw used only for storage. */
class InfiniteCanvas(
    context: Context,
    private val store: VaultStore,
    private val path: String,
    private var openedHash: String,
    onEdited: () -> Unit,
    onError: (String) -> Unit
) {
    private val source = store.blobs.file(openedHash).let {
        require(it.length() <= 32 * 1024 * 1024) { "Drawing exceeds 32 MiB limit" }
        it.readText()
    }
    private var document = ExcalidrawDocument(source)
    val view = InkCanvas(context)
    private var savedPage = document.initialPage
    private val images = android.util.LruCache<String, Bitmap>(8)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val order = document.scene.getJSONArray("elements").let { a -> (0 until a.length()).associate { a.getJSONObject(it).getString("id") to it } }

    init {
        view.infinite = true
        view.contentDescription = "Infinite Canvas. Finger pans and pinches to zoom. Pencil edits with the selected toolbar tool."
        view.writable = true
        view.show(document.initialPage)
        view.changed = onEdited
        view.failed = onError
        view.renderInfinite = { canvas, page, selected -> render(canvas, page, selected) }
        view.eraseInfinite = { page, from, to, tolerance ->
            val ink = page.erase(from, to, tolerance)
            val removed = page.objects.filter { obj ->
                val x = obj["x"] as Long
                val y = obj["y"] as Long
                val w = obj["width"] as Long
                val h = obj["height"] as Long
                val corners = listOf(InkPoint(x, y, 0), InkPoint(x + w, y, 0), InkPoint(x + w, y + h, 0), InkPoint(x, y + h, 0), InkPoint(x, y, 0))
                (to.x in min(x, x + w)..max(x, x + w) && to.y in min(y, y + h)..max(y, y + h)) || corners.zipWithNext().any { (a, b) -> InkPage.segmentsIntersect(from, to, a, b) }
            }
            ink.copy(objects = ink.objects - removed.toSet(), tombstones = ink.tombstones + removed.map { it["id"] as String })
        }
        var positioned = false
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!positioned && view.width > 0) {
                positioned = true
                view.fit()
            }
        }
        if (document.warnings.isNotEmpty()) onError(document.warnings.joinToString(" · "))
    }

    fun persist(): Boolean {
        if (view.page == savedPage) return false
        val text = document.encode(view.page)
        val nextDocument = ExcalidrawDocument(text).also { it.adopt(view.page) }
        openedHash = store.saveEditedText(path, text, openedHash).hash
        document = nextDocument
        savedPage = view.page
        return true
    }
    fun close() {
        view.pauseHardware()
        images.evictAll()
    }

    fun insertImage(asset: String) {
        val entry = requireNotNull(store.get(asset))
        val mime = when (asset.substringAfterLast('.').lowercase()) {
            "svg" -> "image/svg+xml"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "image/png"
        }
        val file = store.blobs.file(entry.hash)
        require(file.length() <= 16 * 1024 * 1024) { "Image exceeds 16 MiB limit" }
        val bitmap = requireNotNull(PageRenderer.image(file, mime == "image/svg+xml"))
        val w = 400.0
        val h = w * bitmap.height / bitmap.width
        bitmap.recycle()
        val id = java.util.UUID.randomUUID().toString()
        val files = document.scene.optJSONObject("files") ?: JSONObject().also { document.scene.put("files", it) }
        files.put(entry.hash, JSONObject().put("id", entry.hash).put("mimeType", mime).put("created", System.currentTimeMillis()).put("dataURL", "data:$mime;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)))
        val center = view.viewportCenter()
        val x = center.x - ExcalidrawDocument.unit(w / 2)
        val y = center.y - ExcalidrawDocument.unit(h / 2)
        val element = JSONObject().put("id", id).put("type", "image").put("fileId", entry.hash).put("status", "saved").put("scale", JSONArray(listOf(1, 1)))
            .put("x", x / ExcalidrawDocument.UNIT).put("y", y / ExcalidrawDocument.UNIT).put("width", w).put("height", h).put("angle", 0).put("opacity", 100)
            .put("isDeleted", false).put("groupIds", JSONArray()).put("seed", id.hashCode() and Int.MAX_VALUE).put("strokeColor", "transparent").put("backgroundColor", "transparent").put("fillStyle", "solid").put("strokeWidth", 1).put("strokeStyle", "solid").put("roughness", 0).put("boundElements", JSONArray()).put("frameId", JSONObject.NULL).put("roundness", JSONObject.NULL).put("link", JSONObject.NULL).put("locked", false)
        view.edit(view.page.copy(objects = view.page.objects + mapOf("id" to id, "x" to x, "y" to y, "width" to ExcalidrawDocument.unit(w), "height" to ExcalidrawDocument.unit(h), "excalidraw" to element.toString(), "createdAt" to System.nanoTime())))
    }

    private fun render(canvas: Canvas, page: InkPage, selected: Set<String>) {
        canvas.drawColor(color(document.scene.optJSONObject("appState")?.optString("viewBackgroundColor", "#ffffff") ?: "#ffffff"), PorterDuff.Mode.SRC)
        val strokes = page.strokes.associateBy { it.id }
        val objects = page.objects.associateBy { it["id"] as String }
        (strokes.keys + objects.keys).sortedBy { order[it]?.toLong() ?: (strokes[it]?.extra?.get("createdAt") as? Long) ?: (objects[it]?.get("createdAt") as? Long) ?: Long.MAX_VALUE }.forEach { id ->
            strokes[id]?.let { view.drawStroke(canvas, it.points, it.style, id in selected) }
            objects[id]?.let { drawObject(canvas, document.element(it), id in selected) }
        }
    }

    private fun drawObject(canvas: Canvas, e: JSONObject, selected: Boolean) {
        val u = ExcalidrawDocument.UNIT.toFloat()
        val x = e.optDouble("x", 0.0).toFloat() * u
        val y = e.optDouble("y", 0.0).toFloat() * u
        val w = e.optDouble("width", 0.0).toFloat() * u
        val h = e.optDouble("height", 0.0).toFloat() * u
        canvas.save()
        canvas.rotate(Math.toDegrees(e.optDouble("angle", 0.0)).toFloat(), x + w / 2, y + h / 2)
        canvas.translate(x, y)
        val bounds = RectF(0f, 0f, w, h)
        val shape = Path()
        val type = e.optString("type")
        when (type) {
            "ellipse" -> shape.addOval(bounds, Path.Direction.CW)
            "diamond" -> {
                shape.moveTo(w / 2, 0f)
                shape.lineTo(w, h / 2)
                shape.lineTo(w / 2, h)
                shape.lineTo(0f, h / 2)
                shape.close()
            }
            "line", "arrow" -> e.optJSONArray("points")?.let { points ->
                for (i in 0 until points.length()) {
                    val p = points.getJSONArray(i)
                    if (i == 0) shape.moveTo(p.getDouble(0).toFloat() * u, p.getDouble(1).toFloat() * u) else shape.lineTo(p.getDouble(0).toFloat() * u, p.getDouble(1).toFloat() * u)
                }
                if (type == "arrow" && points.length() > 1) {
                    fun head(end: Int, previous: Int, kind: String) {
                        if (kind == "null" || kind.isEmpty()) return
                        val a = points.getJSONArray(previous)
                        val b = points.getJSONArray(end)
                        val bx = b.getDouble(0).toFloat() * u
                        val by = b.getDouble(1).toFloat() * u
                        val angle = atan2(b.getDouble(1) - a.getDouble(1), b.getDouble(0) - a.getDouble(0))
                        shape.moveTo(bx - (12 * u * cos(angle - PI / 6)).toFloat(), by - (12 * u * sin(angle - PI / 6)).toFloat())
                        shape.lineTo(bx, by)
                        shape.lineTo(bx - (12 * u * cos(angle + PI / 6)).toFloat(), by - (12 * u * sin(angle + PI / 6)).toFloat())
                    }
                    head(points.length() - 1, points.length() - 2, e.optString("endArrowhead", "arrow"))
                    head(0, 1, e.optString("startArrowhead"))
                }
            }
            else -> if (e.optJSONObject("roundness") != null) shape.addRoundRect(bounds, min(w, h) * 0.15f, min(w, h) * 0.15f, Path.Direction.CW) else shape.addRect(bounds, Path.Direction.CW)
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.alpha = e.optInt("opacity", 100) * 255 / 100
        when (type) {
            "text" -> {
                paint.color = color(e.optString("strokeColor", "#000000"))
                paint.alpha = e.optInt("opacity", 100) * 255 / 100
                paint.textSize = e.optDouble("fontSize", 20.0).toFloat() * u
                paint.typeface = if (e.optInt("fontFamily") == 3) Typeface.MONOSPACE else Typeface.DEFAULT
                val align = e.optString("textAlign", "left")
                paint.textAlign = when (align) {
                    "center" -> Paint.Align.CENTER
                    "right" -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
                val tx = when (align) {
                    "center" -> w / 2
                    "right" -> w
                    else -> 0f
                }
                e.optString("text").split('\n').forEachIndexed { i, line -> canvas.drawText(line, tx, -paint.fontMetrics.ascent + i * paint.textSize * e.optDouble("lineHeight", 1.25).toFloat(), paint) }
            }
            "image" -> {
                val image = image(e.optString("fileId"))
                if (image == null) {
                    placeholder(canvas, bounds, "Missing image")
                } else {
                    val scale = e.optJSONArray("scale")
                    canvas.save()
                    canvas.scale(scale?.optDouble(0, 1.0)?.toFloat() ?: 1f, scale?.optDouble(1, 1.0)?.toFloat() ?: 1f, w / 2, h / 2)
                    paint.isFilterBitmap = true
                    canvas.drawBitmap(image, null, bounds, paint)
                    canvas.restore()
                }
            }
            else -> {
                val fill = e.optString("backgroundColor", "transparent")
                if (fill != "transparent" && type != "frame") {
                    paint.color = color(fill)
                    paint.alpha = e.optInt("opacity", 100) * 255 / 100
                    canvas.drawPath(shape, paint)
                }
                paint.style = Paint.Style.STROKE
                paint.color = color(e.optString("strokeColor", "#000000"))
                paint.alpha = e.optInt("opacity", 100) * 255 / 100
                paint.strokeWidth = e.optDouble("strokeWidth", 1.0).toFloat() * u
                paint.strokeJoin = Paint.Join.ROUND
                paint.pathEffect = when (e.optString("strokeStyle")) {
                    "dashed" -> DashPathEffect(floatArrayOf(8 * u, 6 * u), 0f)
                    "dotted" -> DashPathEffect(floatArrayOf(u, 4 * u), 0f)
                    else -> null
                }
                canvas.drawPath(shape, paint)
                if (type !in setOf("rectangle", "ellipse", "diamond", "line", "arrow", "frame")) placeholder(canvas, bounds, type)
            }
        }
        if (selected) {
            paint.reset()
            paint.color = Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = u
            canvas.drawRect(bounds, paint)
        }
        canvas.restore()
    }
    private fun placeholder(canvas: Canvas, bounds: RectF, text: String) {
        paint.reset()
        paint.color = Color.GRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 200f
        canvas.drawRect(bounds, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 2400f
        canvas.drawText(text, bounds.left, bounds.top + 2400f, paint)
    }
    private fun image(id: String): Bitmap? {
        images.get(id)?.let { return it }
        return runCatching {
            val data = document.scene.optJSONObject("files")?.optJSONObject(id)?.optString("dataURL")
            val bitmap = if (data?.startsWith("data:image/") == true) {
                require(data.length < 24 * 1024 * 1024)
                val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
                if (data.startsWith("data:image/svg+xml")) {
                    val svg = com.caverock.androidsvg.SVG.getFromString(bytes.toString(Charsets.UTF_8))
                    Bitmap.createBitmap(1600, 1600, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    options.inJustDecodeBounds = false
                    options.inSampleSize = max(1, max(options.outWidth, options.outHeight) / 2400)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            } else {
                val link = Regex("(?m)^${Regex.escape(id)}:\\s*\\[\\[([^]]+)]]").find(source)?.groupValues?.get(1)
                val resolved = link?.let { Markdown.resolve(path, it.substringBefore('|').substringBefore('#'), store.entries().filter { entry -> !entry.deleted }.map { entry -> entry.path }) }
                resolved?.let { asset -> store.get(asset)?.let { PageRenderer.image(store.blobs.file(it.hash), asset.endsWith(".svg", true)) } }
            }
            bitmap?.also { images.put(id, it) }
        }.getOrNull()
    }
    private fun color(value: String) = if (value == "transparent") Color.TRANSPARENT else runCatching { Color.parseColor(value) }.getOrDefault(Color.BLACK)
}

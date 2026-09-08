package de.tobisk.inkvault.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import de.tobisk.inkvault.ink.*
import kotlin.math.min

/** Only this view accepts ink, and only TOOL_TYPE_STYLUS / ERASER can edit it. */
class InkCanvas(context: Context) : View(context) {
    var page = InkPage()
        private set
    var miniature = false
    var writable = false
        set(value) {
            field = value
            resumeHardware()
        }
    var tool = "pen"
        set(value) {
            field = value
            resumeHardware()
        }
    var style = InkStyle()
        set(value) {
            field = value
            resumeHardware()
        }
    private var hardware: BooxPenBridge? = null
    val directInkActive get() = hardware?.active == true
    val directInkStrokes get() = hardware?.receivedStrokes ?: 0
    val directInkPoints get() = hardware?.submittedPoints ?: 0
    val directInkMaxSubmitNanos get() = hardware?.maxSubmitNanos ?: 0L
    var backgroundPage: Bitmap? = null
        set(value) {
            field?.takeIf { it !== value }?.recycle()
            field = value
            invalidate()
        }
    var changed: () -> Unit = {}
    var failed: (String) -> Unit = {}
    val drawing get() = activePointer != -1 || hardware?.drawing == true
    private val inverse = Matrix()
    private val coordinates = FloatArray(2)
    private var activeErase = false
    var asset: (String) -> Bitmap? = { null }
    private val images = android.util.LruCache<String, Bitmap>(4)
    private var activeBitmap: Bitmap? = null
    private var activeSamples = 0
    var pressureSensitivity = 1f
    private var encodedUpperBound = 0L
    private var flattened: Bitmap? = null
    private var flattenedPage: InkPage? = null
    private var flattenedBackground: Bitmap? = null
    private var flattenedSelection: Set<String> = emptySet()
    private var history = InkHistory()
    private var points = mutableListOf<InkPoint>()
    private var lasso = mutableListOf<InkPoint>()
    private var selected = emptySet<String>()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var previousX = 0f
    private var previousY = 0f
    private var activePointer = -1
    private var commandStart: InkPage? = null
    private var moving = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
                invalidate()
                return true
            }
        }
    )
    init {
        setBackgroundColor(Color.WHITE)
        contentDescription = "Document page. Stylus writes in write mode; two fingers zoom and pan."
        isFocusable = true
    }
    fun show(value: InkPage) {
        page = value
        encodedUpperBound = value.bytes().size.toLong()
        history = InkHistory()
        selected = emptySet()
        points.clear()
        images.evictAll()
        fit()
        invalidate()
    }
    fun fit() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }
    fun undo() {
        history.undo(page)?.let {
            page = it
            encodedUpperBound = it.bytes().size.toLong()
            changed()
            invalidate()
        }
    }
    fun redo() {
        history.redo(page)?.let {
            page = it
            encodedUpperBound = it.bytes().size.toLong()
            changed()
            invalidate()
        }
    }
    fun edit(next: InkPage) {
        if (next == page) return
        history.record(page)
        page = next
        encodedUpperBound = next.bytes().size.toLong()
        changed()
        invalidate()
    }
    fun scaleSelection(factor: Double) {
        edit(page.transform(selected, 0, 0, factor, lasso.firstOrNull()?.x ?: 0, lasso.firstOrNull()?.y ?: 0))
    }
    private fun transform(): Matrix {
        val fit = min((width - 24).toFloat() / page.width, (height - 24).toFloat() / page.height).coerceAtLeast(0.00001f)
        val scale = fit * zoom
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - page.width * scale) / 2 + panX, (height - page.height * scale) / 2 + panY)
        }
    }
    private fun position(event: MotionEvent, index: Int, historical: Int? = null): InkPoint {
        val xy = coordinates
        xy[0] = if (historical == null) event.getX(index) else event.getHistoricalX(index, historical)
        xy[1] = if (historical == null) event.getY(index) else event.getHistoricalY(index, historical)
        inverse.mapPoints(xy)
        val pressure = if (historical == null) event.getPressure(index) else event.getHistoricalPressure(index, historical)
        return InkPoint(xy[0].toLong(), xy[1].toLong(), if (historical == null) event.eventTime else event.getHistoricalEventTime(historical), ((1f + (pressure - 1f) * pressureSensitivity) * 1000).toLong().coerceIn(0, 2000), (event.getAxisValue(MotionEvent.AXIS_TILT, index) * 1000).toLong())
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(transform())
        canvas.clipRect(0f, 0f, page.width.toFloat(), page.height.toFloat())
        if (flattenedPage !== page || flattenedBackground !== backgroundPage || flattenedSelection != selected || flattened == null) {
            flattened?.recycle()
            val scale = min(width.coerceAtLeast(1).toFloat() / page.width, height.coerceAtLeast(1).toFloat() / page.height)
            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val paper = Canvas(bitmap)
            paper.scale(bitmap.width.toFloat() / page.width, bitmap.height.toFloat() / page.height)
            paper.drawColor(Color.WHITE)
            backgroundPage?.let {
                paint.reset()
                paint.isFilterBitmap = !miniature
                paper.drawBitmap(it, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), paint)
            }
            for (obj in page.objects) {
                val path = obj["asset"] as? String ?: continue
                val bitmap = images.get(path) ?: runCatching { asset(path) }.getOrNull()?.also { images.put(path, it) } ?: continue
                val x = (obj["x"] as Long).toFloat()
                val y = (obj["y"] as Long).toFloat()
                paint.reset()
                paper.drawBitmap(bitmap, null, RectF(x, y, x + (obj["width"] as Long), y + (obj["height"] as Long)), paint)
            }
            for (stroke in page.strokes) drawStroke(paper, stroke.points, stroke.style, stroke.id in selected)
            flattened = bitmap
            flattenedPage = page
            flattenedBackground = backgroundPage
            flattenedSelection = selected
        }
        paint.reset()
        paint.isFilterBitmap = !miniature
        flattened?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), paint) }
        if (points.isNotEmpty() && !activeErase && tool != "lasso") {
            if (tool in InkShapes.tools) {
                drawStroke(canvas, InkShapes.points(tool, points.first(), points.last()), style, false)
            } else {
                val paper = requireNotNull(flattened)
                if (activeBitmap?.width != paper.width || activeBitmap?.height != paper.height) {
                    activeBitmap?.recycle()
                    activeBitmap = Bitmap.createBitmap(paper.width, paper.height, Bitmap.Config.ARGB_8888)
                    activeSamples = 0
                }
                val bitmap = requireNotNull(activeBitmap)
                if (activeSamples < points.size) {
                    val ink = Canvas(bitmap)
                    ink.scale(bitmap.width.toFloat() / page.width, bitmap.height.toFloat() / page.height)
                    drawStroke(ink, points.subList((activeSamples - 1).coerceAtLeast(0), points.size), style, false)
                    activeSamples = points.size
                }
                paint.reset()
                canvas.drawBitmap(bitmap, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), paint)
            }
        }
        if (lasso.size > 1) {
            paint.reset()
            paint.color = Color.BLACK
            paint.strokeWidth = 400f
            paint.style = Paint.Style.STROKE
            val path = Path()
            path.moveTo(lasso[0].x.toFloat(), lasso[0].y.toFloat())
            lasso.drop(1).forEach { path.lineTo(it.x.toFloat(), it.y.toFloat()) }
            path.close()
            canvas.drawPath(path, paint)
        }
        canvas.restore()
        paint.reset()
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        val boundary = RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
        transform().mapRect(boundary)
        canvas.drawRect(boundary, paint)
    }
    private fun drawStroke(canvas: Canvas, samples: List<InkPoint>, style: InkStyle, selected: Boolean) {
        paint.reset()
        paint.isAntiAlias = !miniature
        paint.color = style.color.toInt()
        paint.alpha = if (style.tool == "marker") 100 else 255
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        for (index in 0 until maxOf(1, samples.size - 1)) {
            val a = samples[index]
            val b = samples[minOf(index + 1, samples.lastIndex)]
            paint.strokeWidth = style.width.toFloat() * if (style.pressure) ((b.pressure ?: 1000) / 1000f).coerceIn(0.2f, 2f) else 1f
            if (miniature) paint.strokeWidth = maxOf(paint.strokeWidth, page.width.toFloat() / width.coerceAtLeast(1) * 2)
            canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), paint)
        }
        if (selected) {
            paint.alpha = 255
            paint.color = Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 250f
            canvas.drawRect(samples.minOf { it.x }.toFloat() - 800, samples.minOf { it.y }.toFloat() - 800, samples.maxOf { it.x }.toFloat() + 800, samples.maxOf { it.y }.toFloat() + 800, paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val stylus = (0 until event.pointerCount).firstOrNull { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }
        if (stylus == null) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) pauseHardware()
            if (activePointer != -1) cancelStroke()
            scaleDetector.onTouchEvent(event)
            if (event.pointerCount >= 2) {
                val x = (event.getX(0) + event.getX(1)) / 2
                val y = (event.getY(0) + event.getY(1)) / 2
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    panX += x - previousX
                    panY += y - previousY
                    invalidate()
                }
                previousX = x
                previousY = y
            }
            return true
        }
        if (!writable) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestUnbufferedDispatch(event)
            if (!directInkActive) resumeHardware()
        }
        hardware?.process(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            transform().invert(inverse)
        }
        val p = position(event, stylus)
        val erasing = tool == "eraser" || event.getToolType(stylus) == MotionEvent.TOOL_TYPE_ERASER
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (p.x !in 0..page.width || p.y !in 0..page.height) return true
                activeBitmap?.eraseColor(Color.TRANSPARENT)
                activeSamples = 0
                activeErase = erasing
                activePointer = event.getPointerId(stylus)
                commandStart = page
                if (tool == "lasso") {
                    moving = selected.isNotEmpty()
                    if (!moving) lasso.clear()
                    lasso.add(p)
                } else if (erasing) {
                    page = page.erase(p, p, 1500)
                    points.add(p)
                } else {
                    points.add(p)
                }
            }
            MotionEvent.ACTION_MOVE -> if (activePointer == event.getPointerId(stylus)) {
                if (tool == "lasso") {
                    if (moving) {
                        val old = lasso.last()
                        page = page.transform(selected, p.x - old.x, p.y - old.y)
                    }
                    lasso.add(p)
                } else if (erasing) {
                    page = page.erase(points.lastOrNull() ?: p, p, 1500)
                    points.clear()
                    points.add(p)
                } else {
                    if (points.size + event.historySize >= 99999) {
                        cancelStroke()
                        failed("Stroke sample limit reached; use shorter strokes")
                        return true
                    }
                    for (h in 0 until event.historySize) points.add(position(event, stylus, h))
                    points.add(p)
                }
            }
            MotionEvent.ACTION_UP -> if (activePointer == event.getPointerId(stylus)) {
                if (tool == "lasso" && !moving) {
                    selected = select(lasso)
                } else if (!erasing && tool != "lasso" && points.isNotEmpty()) {
                    points.add(p)
                    val newSamples = if (tool in InkShapes.tools) InkShapes.points(tool, points.first(), points.last()) else points.toList()
                    val strokeBound = newSamples.size * 46L + 1024
                    if (page.strokes.size >= 50000 || encodedUpperBound + strokeBound > CanonicalCbor.MAX_BYTES) {
                        cancelStroke()
                        failed("Page limit reached; add a new page")
                        return true
                    }
                    val next = page.copy(strokes = page.strokes + InkStroke(points = newSamples, style = style))
                    encodedUpperBound += strokeBound
                    // Append only the new stroke to the cached paper; older ink is unchanged.
                    flattened?.takeIf { flattenedPage === page && flattenedBackground === backgroundPage && selected.isEmpty() }?.let { bitmap ->
                        val paper = Canvas(bitmap)
                        paper.scale(bitmap.width.toFloat() / page.width, bitmap.height.toFloat() / page.height)
                        drawStroke(paper, next.strokes.last().points, style, false)
                        flattenedPage = next
                    }
                    page = next
                }
                if (commandStart != page) {
                    commandStart?.let { history.record(it) }
                    changed()
                }
                commandStart = null
                activePointer = -1
                points.clear()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> cancelStroke()
        }
        invalidate()
        return true
    }
    fun clearSelection() {
        selected = emptySet()
        lasso.clear()
        invalidate()
    }
    private fun cancelStroke() {
        commandStart?.let { page = it }
        commandStart = null
        activePointer = -1
        points.clear()
        invalidate()
    }
    private fun select(polygon: List<InkPoint>): Set<String> {
        if (polygon.size < 3) return emptySet()
        val edges = (polygon + polygon.first()).zipWithNext()
        fun inside(p: InkPoint): Boolean {
            var result = false
            for ((a, b) in edges) if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x).toDouble() * (p.y - a.y) / (b.y - a.y) + a.x) result = !result
            return result
        }
        val ids = page.strokes.filter { s -> s.points.any(::inside) || s.points.zipWithNext().any { (a, b) -> edges.any { (c, d) -> InkPage.segmentsIntersect(a, b, c, d) } } }.map { it.id }.toMutableSet()
        page.objects.forEach { obj ->
            val x = obj["x"] as Long
            val y = obj["y"] as Long
            val w = obj["width"] as Long
            val h = obj["height"] as Long
            val corners = listOf(InkPoint(x, y, 0), InkPoint(x + w, y, 0), InkPoint(x + w, y + h, 0), InkPoint(x, y + h, 0))
            if (corners.any(::inside) || polygon.any { it.x in x..x + w && it.y in y..y + h }) ids.add(obj["id"] as String)
        }
        return ids
    }
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
    fun pauseHardware() {
        hardware?.pause()
    }
    fun resumeHardware() {
        if (miniature || !isAttachedToWindow || !hasWindowFocus() || !writable || tool != "pen") {
            pauseHardware()
            return
        }
        if (!BooxPenBridge.supported) return
        val bounds = RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
        transform().mapRect(bounds)
        bounds.intersect(0f, 0f, width.toFloat(), height.toFloat())
        val scale = FloatArray(9)
        transform().getValues(scale)
        val bridge = hardware ?: BooxPenBridge(this).also { hardware = it }
        bridge.configure(Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()), style.width * scale[Matrix.MSCALE_X], style)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { resumeHardware() }
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) post { resumeHardware() } else pauseHardware()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        hardware?.close()
        post { resumeHardware() }
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) resumeHardware()
        return super.onHoverEvent(event)
    }
    override fun onDetachedFromWindow() {
        hardware?.close()
        hardware = null
        backgroundPage = null
        activeBitmap?.recycle()
        activeBitmap = null
        flattened?.recycle()
        flattened = null
        flattenedPage = null
        images.evictAll()
        super.onDetachedFromWindow()
    }
}

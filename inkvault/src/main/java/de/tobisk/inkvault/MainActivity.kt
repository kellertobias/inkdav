package de.tobisk.inkvault

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import de.tobisk.inkvault.audio.QuickRecorder
import de.tobisk.inkvault.data.*
import de.tobisk.inkvault.ink.*
import de.tobisk.inkvault.ui.*
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val app get() = application as InkVaultApplication
    private val store get() = app.store
    private lateinit var sidebar: LinearLayout
    private lateinit var tree: ListView
    private lateinit var files: ListView
    private lateinit var content: LinearLayout
    private lateinit var tools: LinearLayout
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var recordButton: Button
    private lateinit var sidebarBody: LinearLayout
    private lateinit var navigation: LinearLayout
    private lateinit var folderActions: LinearLayout
    private lateinit var fileActionsBar: LinearLayout
    private lateinit var waveform: WaveformView
    private var includeChildren = false
    private var showHidden = false
    private var autosaveJob: Job? = null
    private var activePresetIndex = 0
    private val presetButtons = mutableListOf<java.lang.ref.WeakReference<Button>>()
    private val presetIcons = mutableListOf<java.lang.ref.WeakReference<PenPresetIcon>>()
    private val modeButtons = mutableMapOf<String, Button>()
    private var settingsPersist: (() -> Unit)? = null
    private lateinit var recorder: QuickRecorder
    private var folder = ""
    private var selected: String? = null
    private var editor: EditText? = null
    private var canvas: InkCanvas? = null
    private var infiniteCanvas: InfiniteCanvas? = null
    private var document: InkDocument? = null
    private var pageIndex = 0
    private var pdfDimensions = emptyList<Pair<Long, Long>>()
    private var player: MediaPlayer? = null
    private var playbackPath: String? = null
    private var playbackReady = false
    private var playbackButton: Button? = null
    private var recentRecordings: LinearLayout? = null
    private var recordingsExpanded = false
    private lateinit var headerModes: LinearLayout
    private lateinit var sidebarToggle: Button
    private var lastSyncBadge = ""
    private var lastSyncStatus = ""
    private val textUndo = ArrayDeque<String>()
    private var applyingTextUndo = false
    private var openedHash = ""
    private var dirty = false
    private var typingPages = mutableListOf("")
    private var markdownHeader = ""
    private val pageBreak = "\n<!-- inkvault-page-break -->\n"
    private var preview = true
    private val markdownExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pageJobs = mutableMapOf<Int, Job>()
    private val pageCanvases = mutableMapOf<Int, InkCanvas>()
    private val dirtyPages = mutableSetOf<Int>()
    private var pageScroll: ScrollView? = null
    private var sidebarMode = "vault"
    private var pageUndo: String? = null
    private val expanded = mutableSetOf("")
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() else message("Microphone permission was denied") }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var name = "Imported.pdf"
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
            prompt("Import into /$folder", name) { chosen ->
                val path = VaultPath.join(folder, chosen)
                require(store.get(path)?.deleted != false) { "A file with this name already exists" }
                io("Importing") {
                    store.save(path, requireNotNull(contentResolver.openInputStream(uri)))
                    withContext(Dispatchers.Main) {
                        refreshSidebar()
                        open(path)
                        app.syncNow()
                    }
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = QuickRecorder(app)
        val root = column()
        root.setBackgroundColor(Color.BLACK)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.BLACK
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val top = row().apply { setBackgroundColor(Color.BLACK) }
        val sidebarHeader = row()
        top.addView(sidebarHeader, LinearLayout.LayoutParams(dp(320), dp(48)))
        headerModes = row()
        sidebarHeader.addView(headerModes, LinearLayout.LayoutParams(0, dp(48), 1f))
        sidebarToggle = icon("previous", "Close sidebar", true) {
            val visible = sidebar.visibility != View.VISIBLE
            sidebar.visibility = if (visible) View.VISIBLE else View.GONE
            headerModes.visibility = sidebar.visibility
            sidebarHeader.layoutParams.width = if (visible) dp(320) else dp(48)
            sidebarHeader.requestLayout()
            sidebarToggle.contentDescription = if (visible) "Close sidebar" else "Open sidebar"
            sidebarToggle.setCompoundDrawables(LineIcon(if (visible) "previous" else "menu", Color.WHITE).apply { setBounds(0, 0, dp(24), dp(24)) }, null, null, null)
            if (document != null || pdfDimensions.isNotEmpty()) {
                save()
                content.post { showPage() }
            }
        }
        sidebarHeader.addView(sidebarToggle)
        title = label("InkVault").apply {
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        title.visibility = View.GONE
        top.addView(title)
        tools = row()
        val toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        }
        top.addView(toolScroll, LinearLayout.LayoutParams(0, dp(48), 1f))
        navigation = row()
        top.addView(navigation)
        val syncControl = FrameLayout(this)
        syncControl.addView(
            icon("cloud", "Sync now", true) {
                save()
                app.syncNow()
                status.text = "Locally saved · Sync queued"
            }
        )
        val syncProgress = SyncProgressView(this)
        syncControl.addView(syncProgress, FrameLayout.LayoutParams(dp(40), dp(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(2) })
        top.addView(syncControl, LinearLayout.LayoutParams(dp(48), dp(48)))
        androidx.work.WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("vault-sync").observe(this) { work ->
            syncProgress.setSyncing(work.any { it.state == androidx.work.WorkInfo.State.RUNNING })
        }

        top.addView(
            icon("refreshDisplay", "Full rerender", true) {
                canvas?.pauseHardware()
                root.invalidate()
                root.post {
                    if (!BooxDisplay.fullRefresh(root)) floatingBadge("Full display refresh unavailable")
                }
            }
        )
        root.addView(top)
        status = label(store.meta("status") ?: "Offline · Locally saved").apply {
            background = outline()
            textSize = 14f
        }
        status.visibility = View.GONE
        status.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s.toString()
                if (text.contains("failed", true) || text.contains("unavailable", true) || text.startsWith("Logged in") || text.startsWith("Server vault")) floatingBadge(text.replace("Locally saved · ", ""))
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val workspace = row().apply { setBackgroundColor(Color.WHITE) }
        root.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))
        sidebar = object : LinearLayout(this) {
            private val borderPaint = android.graphics.Paint().apply { color = Color.BLACK }
            init {
                orientation = VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(0, 0, dp(2), 0)
            }
            override fun dispatchDraw(canvas: android.graphics.Canvas) {
                super.dispatchDraw(canvas)
                canvas.drawRect((width - dp(2)).toFloat(), 0f, width.toFloat(), height.toFloat(), borderPaint)
            }
        }
        workspace.addView(sidebar, LinearLayout.LayoutParams(dp(320), -1))
        val modes = headerModes
        listOf("vault" to "folder", "pages" to "pages", "history" to "history", "settings" to "settings").forEach { (mode, glyph) ->
            modes.addView(
                icon(
                    glyph,
                    when (mode) {
                        "vault" -> "Vault structure"
                        "pages" -> "Document pages"
                        "history" -> "History"
                        else -> "Settings"
                    }
                ) {
                    sidebarMode = mode
                    buildSidebar()
                }.also { modeButtons[mode] = it },
                LinearLayout.LayoutParams(dp(48), dp(48))
            )
        }

        sidebarBody = column()
        sidebar.addView(sidebarBody, LinearLayout.LayoutParams(-1, 0, 1f))
        tree = ListView(this).apply {
            divider = null
            dividerHeight = 0
        }
        files = ListView(this).apply {
            divider = null
            dividerHeight = 0
        }
        folderActions = sectionRow()
        fileActionsBar = sectionRow()
        waveform = WaveformView(this)
        recordButton = icon("record", "Start or stop recording") {
            if (recorder.running) {
                recorder.stop()
            } else if (!recorder.saving) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording() else microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        val right = column()
        workspace.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        content = column()
        right.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(label("Choose a file or create a note. Everything is saved locally before sync."))
        setContentView(root)
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, root).isAppearanceLightNavigationBars = false
        buildSidebar()
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                if (canvas?.drawing == true) continue
                recordButton.contentDescription = if (recorder.running) "Stop recording · ${(System.currentTimeMillis() - recorder.started) / 1000}s" else "Start recording"
                recordButton.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
                recordButton.setCompoundDrawables(LineIcon(if (recorder.running) "stop" else "record").apply { setBounds(0, 0, dp(24), dp(24)) }, null, null, null)
                waveform.samples = recorder.waveform
                updatePlaybackButton()
                updateRecentRecordings()
                val syncStatus = store.meta("status") ?: ""
                if (syncStatus != lastSyncStatus) {
                    lastSyncStatus = syncStatus
                    if (syncStatus.contains("failed", true) || syncStatus.startsWith("Conflict")) floatingBadge(syncStatus.replace("Locally saved · ", ""))
                }
                val syncBadge = store.meta("syncCompleted") ?: ""
                if (syncBadge != lastSyncBadge) {
                    lastSyncBadge = syncBadge
                    if (syncBadge.isNotEmpty()) floatingBadge(store.meta("status")?.replace("Locally saved · ", "") ?: "Synced")
                    refreshSidebar()
                }
            }
        }
        lifecycleScope.launch {
            while (isActive) {
                delay(2000)
                if (dirty && canvas?.drawing != true && autosaveJob?.isActive != true) queueSave()
            }
        }
        lastSyncBadge = store.meta("syncCompleted") ?: ""
        (savedInstanceState?.getString("selected") ?: store.meta("lastOpenFile"))?.takeIf { store.get(it)?.deleted == false }?.let { open(it) }
    }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            canvas?.let { view ->
                val bounds = android.graphics.Rect()
                view.getGlobalVisibleRect(bounds)
                if (!bounds.contains(event.rawX.toInt(), event.rawY.toInt()) || view.scrollingPage && event.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER) view.pauseHardware()
            }
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        safe { save() }
        outState.putString("selected", selected)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        canvas?.let {
            it.pauseHardware()
            BooxDisplay.apply(it, "System")
        }
        safe { save() }
        settingsPersist?.let { runCatching(it) }
        if (recorder.running) recorder.stop()
        player?.pause()
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        canvas?.let { BooxDisplay.apply(it, store.meta("displayMode") ?: "Writing") }
    }
    override fun onDestroy() {
        markdownExecutor.shutdownNow()
        infiniteCanvas?.close()
        infiniteCanvas = null
        player?.release()
        player = null
        super.onDestroy()
    }
    private fun startRecording() = safe {
        recorder.start { result ->
            runOnUiThread {
                if (!isDestroyed) {
                    status.text = result
                    refreshSidebar()
                    updateRecentRecordings()
                }
            }
        }
        recordButton.contentDescription = "Stop recording"
    }
    private fun refreshSidebar() {
        if (sidebarMode == "settings" || sidebarMode == "history") return
        val entries = store.entries().filter { !it.deleted }
        if (sidebarMode == "outline") {
            val headings = Markdown.outline(editor?.text?.toString() ?: selected?.let { store.get(it)?.let { e -> if (it.endsWith(".md")) store.blobs.file(e.hash).readText() else "" } } ?: "")
            tree.adapter = adapter(headings.map { "  ".repeat(it.level - 1) + it.title })
            tree.setOnItemClickListener { _, _, index, _ ->
                if (preview) {
                    preview = false
                    open(requireNotNull(selected))
                }
                editor?.requestFocus()
                editor?.setSelection(headings[index].offset.coerceAtMost(editor?.length() ?: 0))
            }
            files.adapter = adapter(emptyList())
            return
        }
        if (sidebarMode == "pages") {
            buildPages()
            return
        }
        val folders = sortedSetOf("")
        val visiblePaths = entries.filter { !VaultPath.hidden(it.path) }.map { it.path } + entries.filter { it.path.startsWith(".inkvault/notes/") && it.path.endsWith("/manifest.json") }.mapNotNull { runCatching { JSONObject(store.blobs.file(it.hash).readText()).getString("pdfPath") }.getOrNull() }
        visiblePaths.forEach { path ->
            var parent = VaultPath.folder(path)
            while (parent.isNotEmpty()) {
                folders.add(parent)
                parent = VaultPath.folder(parent)
            }
        }
        val visible = folders.filter { path -> path.isEmpty() || generateSequence(VaultPath.folder(path)) { if (it.isEmpty()) null else VaultPath.folder(it) }.all { it in expanded } }
        tree.adapter = object : BaseAdapter() {
            override fun getCount() = visible.size
            override fun getItem(position: Int) = visible[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val path = visible[position]
                val item = row().apply {
                    setBackgroundColor(if (path == folder) Color.BLACK else Color.WHITE)
                    setPadding(dp(if (path.isEmpty()) 0 else path.count { it == '/' } * 12 + 12), 0, 0, 0)
                }
                item.addView(
                    icon(if (path in expanded) "collapse" else "next", "Expand or collapse /$path") {
                        if (!expanded.add(path)) expanded.remove(path)
                        refreshSidebar()
                    }.apply {
                        background = null
                        setCompoundDrawables(LineIcon(if (path in expanded) "collapse" else "next", if (path == folder) Color.WHITE else Color.BLACK).apply { setBounds(0, 0, dp(24), dp(24)) }, null, null, null)
                    }
                )
                item.addView(
                    label(if (path.isEmpty()) "Vault" else VaultPath.name(path)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(if (path == folder) Color.WHITE else Color.BLACK)
                        setOnClickListener {
                            folder = path
                            refreshSidebar()
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(48), 1f)
                )
                return item
            }
        }
        tree.setOnItemClickListener(null)
        val notes = entries.filter { it.path.startsWith(".inkvault/notes/") && it.path.endsWith("/manifest.json") }.mapNotNull { e ->
            runCatching { JSONObject(store.blobs.file(e.hash).readText()).let { m -> Triple(e.path, m.getString("pdfPath"), m.getString("title")) } }.getOrNull()
        }
        val paired = notes.map { it.second }.toSet()
        val items = entries.filter { (showHidden || !VaultPath.hidden(it.path)) && (VaultPath.folder(it.path) == folder || includeChildren && (folder.isEmpty() || it.path.startsWith("$folder/"))) && it.path !in paired }.map {
            it.path to "${it.path.substringAfterLast('.').uppercase()} · ${VaultPath.name(it.path)}${if (it.conflict.isNotEmpty()) {
                " · CONFLICT"
            } else if (it.dirty) {
                " · pending"
            } else {
                ""
            }}"
        } +
            notes.filter { VaultPath.folder(it.second) == folder || includeChildren && (folder.isEmpty() || it.second.startsWith("$folder/")) }.map { it.first to "INK · ${it.third}" }
        files.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items.map { it.second }) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(if (items[position].first == selected) Color.WHITE else Color.BLACK)
                setBackgroundColor(if (items[position].first == selected) Color.BLACK else Color.WHITE)
                minHeight = dp(44)
                textSize = 16f
            }
        }
        files.setOnItemClickListener { _, _, index, _ -> open(items[index].first) }
        files.setOnItemLongClickListener { _, _, index, _ ->
            fileActions(items[index].first)
            true
        }
    }
    private fun sectionRow() = row().apply {
        background = object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint()
            override fun draw(canvas: android.graphics.Canvas) {
                canvas.drawColor(Color.LTGRAY)
                paint.color = Color.BLACK
                canvas.drawRect(0f, 0f, bounds.width().toFloat(), dp(1).toFloat(), paint)
                canvas.drawRect(0f, (bounds.height() - dp(1)).toFloat(), bounds.width().toFloat(), bounds.height().toFloat(), paint)
            }
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit

            @Deprecated("Deprecated in Android")
            override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
        }
    }
    private fun outline(black: Boolean = false) = GradientDrawable().apply {
        setColor(if (black) Color.BLACK else Color.WHITE)
        setStroke(dp(1).coerceAtLeast(1), if (black) Color.WHITE else Color.BLACK)
    }
    private fun icon(glyph: String, description: String, black: Boolean = false, action: () -> Unit) = Button(this).apply {
        contentDescription = description
        tooltipText = description
        text = ""
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = outline(black)
        stateListAnimator = null
        elevation = 0f
        setCompoundDrawables(LineIcon(glyph, if (black) Color.WHITE else Color.BLACK).apply { setBounds(0, 0, dp(24), dp(24)) }, null, null, null)
        setOnClickListener { safe(action) }
    }
    private fun queueSave() {
        status.text = "Editing · autosave pending"
        autosaveJob?.cancel()
        autosaveJob = lifecycleScope.launch {
            delay(700)
            if (canvas?.drawing == true || !dirty) return@launch
            val view = canvas
            val snapshot = view?.page
            try {
                val bytes = if (infiniteCanvas == null) snapshot?.let { withContext(Dispatchers.Default) { it.bytes() } } else null
                if (canvas === view && (view == null || view.page === snapshot && !view.drawing)) save(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Autosave failed: ${e.message}"
            }
        }
    }
    private fun buildSidebar() {
        settingsPersist?.let { runCatching(it) }
        settingsPersist = null
        modeButtons.forEach { (mode, button) ->
            val active = mode == sidebarMode
            button.background = outline(active)
            button.setCompoundDrawables(
                LineIcon(
                    when (mode) {
                        "vault" -> "folder"
                        else -> mode
                    },
                    if (active) Color.WHITE else Color.BLACK
                ).apply { setBounds(0, 0, dp(24), dp(24)) },
                null,
                null,
                null
            )
        }
        sidebarBody.removeAllViews()
        when (sidebarMode) {
            "settings" -> {
                settings()
                return
            }
            "history" -> {
                selected?.let { history(it) } ?: sidebarBody.addView(label("Open a document to view its history."))
                return
            }
            "pages" -> {
                buildPages()
                return
            }
        }
        folderActions.removeAllViews()
        folderActions.addView(label("Folders"), LinearLayout.LayoutParams(0, -2, 1f))
        folderActions.addView(
            icon("add", "Create folder") {
                prompt("New folder", "New folder") { name ->
                    val path = VaultPath.join(folder, name)
                    require(store.entries().none { !it.deleted && it.path.startsWith("$path/") }) { "Folder exists" }
                    store.saveText("$path/.keep", "")
                    expanded.add(folder)
                    folder = path
                    refreshSidebar()
                }
            }
        )
        folderActions.addView(
            menuButton("rename", "Edit folder", listOf("Rename", "Move", "Delete")) { index ->
                if (index == 0) renameFolder() else folderOperation(index == 2)
            }
        )
        folderActions.addView(
            menuButton("eye", "Folder view options", listOf("Collapse all")) {
                expanded.clear()
                expanded.add("")
                refreshSidebar()
            }
        )
        for (i in 1 until folderActions.childCount) folderActions.getChildAt(i).background = null
        sidebarBody.addView(folderActions)
        (tree.parent as? android.view.ViewGroup)?.removeView(tree)
        sidebarBody.addView(tree, LinearLayout.LayoutParams(-1, 0, 1f))
        fileActionsBar.removeAllViews()
        fileActionsBar.addView(label("Files"), LinearLayout.LayoutParams(0, -2, 1f))
        fileActionsBar.addView(icon("add", "Create file") { createMenu() })
        fileActionsBar.addView(menuButton("rename", "Edit file", listOf("Rename", "Move", "Delete")) { index -> selected?.let { fileActions(it, listOf("rename", "move", "delete")[index]) } })
        fileActionsBar.addView(
            menuButton("eye", "File view options", listOf("Include files in subfolders", "Show hidden files"), { listOf(includeChildren, showHidden) }) { index ->
                if (index == 0) includeChildren = !includeChildren else showHidden = !showHidden
                refreshSidebar()
            }
        )
        for (i in 1 until fileActionsBar.childCount) fileActionsBar.getChildAt(i).background = null
        sidebarBody.addView(fileActionsBar)
        (files.parent as? android.view.ViewGroup)?.removeView(files)
        sidebarBody.addView(files, LinearLayout.LayoutParams(-1, 0, 1f))
        val recording = sectionRow()
        (recordButton.parent as? android.view.ViewGroup)?.removeView(recordButton)
        (waveform.parent as? android.view.ViewGroup)?.removeView(waveform)
        recording.addView(label("Recording"), LinearLayout.LayoutParams(0, -2, 1f))
        recording.addView(waveform, LinearLayout.LayoutParams(dp(72), dp(48)))
        recording.addView(recordButton)
        playbackButton = icon("play", "Play latest recording") {
            val path = playbackPath ?: latestRecordings().firstOrNull()?.path
            path?.let { playRecording(it) }
        }.apply {
            setOnLongClickListener {
                stopPlayback()
                true
            }
        }
        recording.addView(playbackButton)
        recording.addView(
            icon(if (recordingsExpanded) "collapse" else "recordings", if (recordingsExpanded) "Collapse recordings" else "Expand recordings") {
                recordingsExpanded = !recordingsExpanded
                buildSidebar()
            }
        )
        for (i in 1 until recording.childCount) recording.getChildAt(i).background = null
        sidebarBody.addView(recording)
        val recordingRows = column()
        recentRecordings = recordingRows
        val recordingScroll = ScrollView(this).apply { addView(recordingRows) }
        val recordingHeight = if (recordingsExpanded) dp(minOf(allRecordings().size.coerceAtLeast(1) * 48, 360)) else -2
        sidebarBody.addView(recordingScroll, LinearLayout.LayoutParams(-1, recordingHeight))
        updateRecentRecordings()
        updatePlaybackButton()
        refreshSidebar()
    }
    private fun menuButton(glyph: String, name: String, items: List<String>, checked: (() -> List<Boolean>)? = null, action: (Int) -> Unit): Button {
        lateinit var anchor: Button
        anchor = icon(glyph, name) {
            val body = column().apply { background = outline() }
            val popup = PopupWindow(body, dp(260), -2, true).apply {
                setBackgroundDrawable(outline())
                elevation = 0f
            }
            items.forEachIndexed { index, item ->
                body.addView(
                    button((if (checked?.invoke()?.get(index) == true) "✓ " else "") + item) {
                        popup.dismiss()
                        action(index)
                    }
                )
            }
            popup.showAsDropDown(anchor)
        }
        return anchor
    }
    private fun floatingBadge(text: String) {
        val badge = label(text).apply {
            background = outline()
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val popup = PopupWindow(badge, -2, -2, false).apply {
            setBackgroundDrawable(outline())
            elevation = 0f
        }
        if (!isFinishing && !isDestroyed) {
            popup.showAtLocation(content, Gravity.TOP or Gravity.END, dp(16), dp(90))
            badge.postDelayed({ popup.dismiss() }, 3000)
        }
    }
    private fun renameFolder() {
        require(folder.isNotEmpty()) { "Select a folder below the vault root" }
        val source = folder
        prompt("Rename folder", VaultPath.name(source)) { name ->
            val target = VaultPath.join(VaultPath.folder(source), name)
            save()
            store.changeFolder(source, target)
            selected?.let { path -> open(if (path.startsWith("$source/")) target + path.removePrefix(source) else path) }
            folder = target
            expanded.add(VaultPath.folder(target))
            refreshSidebar()
            app.syncNow()
        }
    }
    private fun allRecordings(): List<VaultEntry> {
        val prefix = (store.meta("recordingsFolder") ?: "Recordings").trimEnd('/') + "/"
        return store.entries().filter { !it.deleted && it.path.startsWith(prefix) && it.path.endsWith(".mp3") }.sortedByDescending { it.modified }
    }
    private fun latestRecordings() = allRecordings().take(3)
    private fun updateRecentRecordings() {
        val list = recentRecordings ?: return
        val recordings = if (recordingsExpanded) allRecordings() else latestRecordings()
        val signature = "${if (recordingsExpanded) "all" else "recent"}:" + recordings.joinToString { it.path + it.hash }
        if (list.tag == signature) return
        list.tag = signature
        list.removeAllViews()
        if (recordings.isEmpty()) {
            list.addView(label("No recordings yet."))
        } else {
            recordings.forEach { entry -> list.addView(recordingRow(entry)) }
        }
    }
    private fun recordingRow(entry: VaultEntry, deleted: () -> Unit = {}): LinearLayout = row().apply {
        addView(
            label(VaultPath.name(entry.path)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                textSize = 14f
                contentDescription = "Play recording ${VaultPath.name(entry.path)}"
                isFocusable = true
                setOnClickListener { safe { playRecording(entry.path) } }
                setOnLongClickListener {
                    stopPlayback()
                    true
                }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        addView(
            icon("delete", "Delete recording ${VaultPath.name(entry.path)}") {
                AlertDialog.Builder(this@MainActivity).setTitle("Delete recording?").setMessage(VaultPath.name(entry.path))
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        safe {
                            if (playbackPath == entry.path) {
                                player?.release()
                                player = null
                                playbackReady = false
                                playbackPath = null
                            }
                            store.deleteDocument(entry.path)
                            closeDeletedDocument()
                            updatePlaybackButton()
                            updateRecentRecordings()
                            refreshSidebar()
                            deleted()
                            app.syncNow()
                        }
                    }.show()
            }
        )
    }
    private fun playRecording(path: String) {
        if (playbackPath == path && player != null) {
            if (playbackReady) {
                if (player!!.isPlaying) player!!.pause() else player!!.start()
            }
        } else {
            val entry = requireNotNull(store.get(path))
            player?.release()
            playbackReady = false
            playbackPath = path
            player = MediaPlayer().apply {
                setDataSource(store.blobs.file(entry.hash).path)
                setOnPreparedListener {
                    playbackReady = true
                    it.start()
                    updatePlaybackButton()
                }
                setOnCompletionListener {
                    it.seekTo(0)
                    updatePlaybackButton()
                }
                setOnErrorListener { _, _, _ ->
                    playbackReady = false
                    player?.release()
                    player = null
                    updatePlaybackButton()
                    message("Recording playback failed")
                    true
                }
                prepareAsync()
            }
        }
        updatePlaybackButton()
    }
    private fun stopPlayback() {
        if (!playbackReady && player != null) {
            player?.release()
            player = null
        }
        if (playbackReady) {
            player?.pause()
            player?.seekTo(0)
        }
        updatePlaybackButton()
    }
    private fun updatePlaybackButton() {
        val playing = playbackReady && player?.isPlaying == true
        val description = if (playing) "Pause recording (long press to stop)" else "Play recording (long press to stop)"
        playbackButton?.let { button ->
            if (button.contentDescription != description) {
                button.contentDescription = description
                button.setCompoundDrawables(LineIcon(if (playing) "pause" else "play").apply { setBounds(0, 0, dp(24), dp(24)) }, null, null, null)
            }
        }
    }
    private fun presetKey() = "documentPresets:${selected ?: "none"}"
    private fun documentPresets(): JSONArray {
        val presets = JSONArray(store.meta(presetKey()) ?: "[]")
        if (presets.length() < 3) {
            listOf("Pen 1 (Thin)", "Pen 2 (Thick)", "Marker").forEachIndexed { index, name ->
                presets.put(
                    index,
                    JSONObject().put("name", name).put("tool", if (index == 2) "marker" else "pen")
                        .put("width", listOf(300, 900, 4000)[index]).put("color", if (index == 2) 0xffffff00L else 0xff000000L)
                        .put("hex", if (index == 2) "#FFFF00" else "#000000").put("pressure", index != 2)
                )
            }
            store.setMeta(presetKey(), presets.toString())
        }
        return presets
    }
    private fun selectPreset(view: InkCanvas, preset: JSONObject, index: Int = activePresetIndex) {
        activePresetIndex = index
        view.tool = "pen"
        view.pressureSensitivity = preset.optInt("sensitivity", if (preset.optBoolean("pressure")) 100 else 0) / 100f
        view.style = InkStyle(preset.getString("tool"), preset.getLong("color"), preset.getLong("width"), preset.getBoolean("pressure"), mapOf("brushType" to preset.optString("type", "Pen")))
        updateActiveTools()
    }
    private fun presetButton(view: InkCanvas, presets: JSONArray, index: Int): Button {
        val preset = presets.getJSONObject(index)
        return icon(
            if (preset.getString("tool") == "marker") {
                "marker"
            } else if (index == 1) {
                "thickPen"
            } else {
                "pen"
            },
            preset.getString("name")
        ) {
            val target = canvas ?: view
            if (target.tool == "pen" && activePresetIndex == index) {
                editPreset(presets, index, preset.getString("tool") == "marker", preset, tools.getChildAt(index.coerceAtMost(3)))
            } else {
                selectPreset(target, preset, index)
            }
        }.apply {
            tag = "preset:$index"
            presetButtons.add(java.lang.ref.WeakReference(this))
            val drawable = PenPresetIcon(preset).apply { setBounds(0, 0, dp(24), dp(24)) }
            setCompoundDrawables(drawable, null, null, null)
            presetIcons.add(java.lang.ref.WeakReference(drawable))
            setOnLongClickListener {
                editPreset(presets, index, preset.getString("tool") == "marker", preset, tools.getChildAt(index.coerceAtMost(3)))
                true
            }
        }
    }
    private fun updateActiveTools() {
        val currentTool = canvas?.tool ?: return
        presetButtons.removeAll { it.get() == null }
        presetButtons.forEach { reference ->
            reference.get()?.let { button ->
                val active = currentTool == "pen" && button.tag == "preset:$activePresetIndex"
                button.isSelected = active
                button.background = outline(active)
                (button.compoundDrawables[0] as? PenPresetIcon)?.active = active
            }
        }
        for (i in 0 until tools.childCount) {
            val button = tools.getChildAt(i) as? Button ?: continue
            val glyph = when (button.tag) {
                "tool:pens" -> "pens"
                "tool:eraser" -> "eraser"
                "tool:lasso" -> "lasso"
                else -> if (button.contentDescription == "Insert") "add" else continue
            }
            val active = when (glyph) {
                "pens" -> currentTool == "pen" && activePresetIndex >= 3
                "add" -> currentTool in InkShapes.tools
                else -> currentTool == glyph
            }
            button.isSelected = active
            button.background = outline(active)
            button.setCompoundDrawables(LineIcon(glyph, if (active) Color.WHITE else Color.BLACK).apply { setBounds(0, 0, dp(24), dp(24)) }, null, null, null)
        }
    }
    private fun showMorePens(view: InkCanvas) {
        val presets = documentPresets()
        val body = column().apply { background = outline() }
        val popup = PopupWindow(body, dp(260), -2, true).apply {
            setBackgroundDrawable(outline())
            elevation = 0f
        }
        // Rows stay open for double press and long press; tapping selects immediately.
        for (index in 3 until presets.length()) {
            val row = row()
            row.addView(presetButton(view, presets, index))
            row.addView(label(presets.getJSONObject(index).getString("name")))
            body.addView(row)
        }
        body.addView(
            button("New pen") {
                popup.dismiss()
                editPreset(presets, presets.length(), false, null)
            }
        )
        body.addView(
            button("New marker") {
                popup.dismiss()
                editPreset(presets, presets.length(), true, null)
            }
        )
        updateActiveTools()
        popup.showAsDropDown(tools.getChildAt(3))
    }
    private fun folderOperation(delete: Boolean) {
        require(folder.isNotEmpty()) { "Select a folder below the vault root" }
        val source = folder
        if (delete) {
            AlertDialog.Builder(this).setTitle("Delete /$source and its contents?").setPositiveButton("Delete") { _, _ ->
                safe {
                    save()
                    store.changeFolder(source, null)
                    closeDeletedDocument()
                    folder = ""
                    refreshSidebar()
                    app.syncNow()
                }
            }.setNegativeButton("Cancel", null).show()
        } else {
            prompt("Move folder to vault path", source) { target ->
                save()
                store.changeFolder(source, target)
                selected?.let { path ->
                    if (path.startsWith("$source/")) {
                        open(target + path.removePrefix(source))
                    } else if (document != null) {
                        open(path)
                    }
                }
                folder = target
                expanded.add(VaultPath.folder(target))
                refreshSidebar()
                app.syncNow()
            }
        }
    }
    private fun buildPages() {
        sidebarBody.removeAllViews()
        if (infiniteCanvas != null) {
            sidebarBody.addView(label("Infinite Canvas · no pages"))
            return
        }
        sidebarBody.addView(label("Document pages"))
        val note = document
        if (note != null && !note.annotation) sidebarBody.addView(button("Add page") { addPageTemplate(note) })
        val count = note?.count ?: pdfDimensions.size
        if (count == 0) {
            if (selected?.endsWith(".md") == true || selected?.endsWith(".txt") == true) {
                buildTypingPages()
                return
            }
            sidebarBody.addView(label("Open a document to see its pages."))
            return
        }
        val list = ListView(this)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = count
            override fun getItem(position: Int) = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val item = column().apply { background = outline() }
                val miniature = InkCanvas(this@MainActivity).apply { miniature = true }
                val dimensions = pdfDimensions.getOrNull(position)
                miniature.show(if (position == pageIndex) canvas?.page ?: note?.page(position) ?: InkPage() else note?.page(position) ?: dimensions?.let { InkPage(it.first, it.second) } ?: InkPage())
                miniature.asset = { asset -> store.get(asset)?.let { PageRenderer.image(store.blobs.file(it.hash), asset.endsWith(".svg")) } }
                val template = note?.pageInfo(position)?.optJSONObject("template")
                val background = if (note?.annotation == true) {
                    note.manifest.getString("basePdfHash") to position
                } else if (template != null) {
                    store.get(template.getString("asset"))?.hash?.let { it to template.getInt("page") }
                } else if (note == null) {
                    selected?.let { store.get(it)?.hash }?.let { it to position }
                } else {
                    null
                }
                var thumbnailJob: Job? = null
                miniature.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        if (background != null) {
                            thumbnailJob = lifecycleScope.launch {
                                val bitmap = withContext(Dispatchers.IO) { runCatching { PageRenderer.pdf(store.blobs.file(background.first), background.second, 320, 450) }.getOrNull() }
                                if (miniature.isAttachedToWindow) miniature.backgroundPage = bitmap else bitmap?.recycle()
                            }
                        }
                    }
                    override fun onViewDetachedFromWindow(v: View) {
                        thumbnailJob?.cancel()
                    }
                })
                miniature.setOnClickListener {
                    save()
                    pageIndex = position
                    showPage()
                }
                miniature.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        save()
                        pageIndex = position
                        showPage()
                    }
                    true
                }
                item.addView(miniature, LinearLayout.LayoutParams(-1, dp(170)))
                val actions = row()
                actions.addView(
                    button("${position + 1}${if (position == pageIndex) " · current" else ""}") {
                        save()
                        pageIndex = position
                        showPage()
                    },
                    LinearLayout.LayoutParams(0, dp(48), 1f)
                )
                if (note != null && !note.annotation) {
                    actions.addView(
                        icon("duplicate", "Duplicate page ${position + 1}") {
                            save()
                            pageIndex = note.addPage(position, false, true)
                            showPage()
                        }
                    )
                    actions.addView(
                        icon("move", "Move page ${position + 1}") {
                            prompt("Move to page (1–$count)", "${position + 1}") { value ->
                                val to = value.toInt() - 1
                                require(to in 0 until count)
                                save()
                                note.reorder(position, to)
                                pageIndex = to
                                showPage()
                            }
                        }
                    )
                    actions.addView(
                        icon("delete", "Delete page ${position + 1}") {
                            require(count > 1) { "Keep at least one page" }
                            AlertDialog.Builder(this@MainActivity).setTitle("Delete page ${position + 1}?").setPositiveButton("Delete") { _, _ ->
                                safe {
                                    save()
                                    pageUndo = note.deletePage(position)
                                    pageIndex = pageIndex.coerceAtMost(note.count - 1)
                                    showPage()
                                }
                            }.setNegativeButton("Cancel", null).show()
                        }
                    )
                }
                item.addView(actions)
                return item
            }
        }
        sidebarBody.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private fun buildTypingPages() {
        sidebarBody.addView(
            button("Add page") {
                AlertDialog.Builder(this).setTitle("Choose page template").setItems(arrayOf("Blank", "Meeting notes", "Checklist")) { _, choice ->
                    safe {
                        save()
                        typingPages.add(pageIndex + 1, arrayOf("", "# Meeting\n\n## Notes\n\n## Actions\n", "# Checklist\n\n- [ ] ")[choice])
                        persistTypingPages(pageIndex + 1)
                    }
                }.show()
            }
        )
        val scroll = ScrollView(this)
        val pages = column()
        typingPages.forEachIndexed { index, text ->
            val card = column().apply { background = outline() }
            card.addView(button("Page ${index + 1}\n${if (index == pageIndex) editor?.text?.toString()?.take(160) ?: text.take(160) else text.take(160)}") { navigatePage(index) })
            val actions = row()
            actions.addView(
                icon("duplicate", "Duplicate page ${index + 1}") {
                    save()
                    typingPages.add(index + 1, typingPages[index])
                    persistTypingPages(index + 1)
                }
            )
            actions.addView(
                icon("move", "Move page ${index + 1}") {
                    prompt("Move to page (1–${typingPages.size})", "${index + 1}") { value ->
                        val to = value.toInt() - 1
                        require(to in typingPages.indices)
                        save()
                        typingPages.add(to, typingPages.removeAt(index))
                        persistTypingPages(to)
                    }
                }
            )
            actions.addView(
                icon("delete", "Delete page ${index + 1}") {
                    require(typingPages.size > 1) { "Keep at least one page" }
                    AlertDialog.Builder(this).setTitle("Delete page ${index + 1}?").setPositiveButton("Delete") { _, _ ->
                        safe {
                            save()
                            typingPages.removeAt(index)
                            persistTypingPages(pageIndex.coerceAtMost(typingPages.lastIndex))
                        }
                    }.setNegativeButton("Cancel", null).show()
                }
            )
            card.addView(actions)
            pages.addView(card)
        }
        scroll.addView(pages)
        sidebarBody.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private fun persistTypingPages(index: Int) {
        val path = requireNotNull(selected)
        openedHash = store.saveEditedText(path, markdownHeader + typingPages.joinToString(pageBreak), openedHash).hash
        pageIndex = index
        dirty = false
        markdown(path)
        refreshSidebar()
        app.syncNow()
    }
    private fun addPageTemplate(note: InkDocument) {
        val templates = store.entries().filter { !it.deleted && it.path.startsWith("${store.meta("templatesFolder") ?: "Templates"}/") && it.path.endsWith(".pdf", true) }
        AlertDialog.Builder(this).setTitle("Choose page template").setItems((listOf("Blank A4 · portrait", "Blank A4 · landscape") + templates.map { it.path }).toTypedArray()) { _, choice ->
            safe {
                if (choice < 2) {
                    save()
                    pageIndex = note.addPage(pageIndex, choice == 1)
                    showPage()
                } else {
                    val template = templates[choice - 2]
                    val dimensions = PageRenderer.dimensions(store.blobs.file(template.hash))
                    AlertDialog.Builder(this).setTitle("Template page").setItems(dimensions.indices.map { "Page ${it + 1}" }.toTypedArray()) { _, index ->
                        safe {
                            save()
                            pageIndex = note.addPage(pageIndex, dimensions[index].first > dimensions[index].second)
                            note.template(pageIndex, template.path, index)
                            showPage()
                        }
                    }.show()
                }
            }
        }.show()
    }
    private fun navigatePage(index: Int) {
        textUndo.clear()
        save()
        pageIndex = index
        val scroll = pageScroll
        if (scroll != null) {
            activatePage(index, pageCanvases.size)
            pageCanvases[index]?.let { page -> scroll.post { scroll.scrollTo(0, (page.top - dp(12)).coerceAtLeast(0)) } }
        } else if (canvas != null) {
            showPage()
        } else {
            selected?.let {
                markdown(it)
                refreshSidebar()
            }
        }
    }
    private fun activatePage(index: Int, count: Int) {
        val next = pageCanvases[index] ?: return
        val previous = canvas
        if (previous !== next) {
            previous?.pauseHardware()
            previous?.let {
                next.tool = it.tool
                next.style = it.style
                next.pressureSensitivity = it.pressureSensitivity
            }
            canvas = next
        }
        pageCanvases.forEach { (page, view) -> view.hardwareEnabled = page == index }
        pageIndex = index
        updateNavigation(count)
        updateActiveTools()
    }
    private fun updateNavigation(count: Int) {
        navigation.removeAllViews()
        navigation.addView(icon("previous", "Previous page", true) { if (pageIndex > 0) navigatePage(pageIndex - 1) })
        navigation.addView(
            button("${pageIndex + 1} / $count") {
                prompt("Jump to page (1–$count)", "${pageIndex + 1}") { value ->
                    val index = value.toInt() - 1
                    require(index in 0 until count)
                    navigatePage(index)
                }
            }.apply {
                setTextColor(Color.WHITE)
                background = outline(true)
            }
        )
        navigation.addView(icon("next", "Next page", true) { if (pageIndex + 1 < count) navigatePage(pageIndex + 1) })
    }
    private fun formatText(kind: String) {
        if (preview) {
            preview = false
            selected?.let { markdown(it) }
        }
        val field = editor ?: return
        if (kind == "image") {
            val images = store.entries().filter { !it.deleted && it.path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "svg") }
            AlertDialog.Builder(this).setTitle("Insert image").setItems(images.map { it.path }.toTypedArray()) { _, index -> field.text.insert(field.selectionStart.coerceAtLeast(0), "![[${images[index].path}]]") }.show()
            return
        }
        if (kind == "section") {
            AlertDialog.Builder(this).setTitle("Insert section").setItems(arrayOf("Section divider", "New page")) { _, choice ->
                safe {
                    val offset = field.selectionStart.coerceAtLeast(0)
                    field.text.insert(offset, if (choice == 0) "\n\n---\n\n" else pageBreak)
                    if (choice == 1) {
                        save()
                        pageIndex++
                        selected?.let { markdown(it) }
                        refreshSidebar()
                    }
                }
            }.show()
            return
        }
        val start = field.selectionStart.coerceAtLeast(0)
        val end = field.selectionEnd.coerceAtLeast(start)
        val edit = MarkdownEdit.format(field.text.toString(), start, end, kind)
        field.text.replace(edit.start, edit.end, edit.text)
        field.requestFocus()
        field.setSelection(edit.cursor)
    }
    private fun createMenu() {
        AlertDialog.Builder(this).setTitle("Create in /$folder").setItems(arrayOf("Markdown note", "A4 handwriting · portrait", "A4 handwriting · landscape", "Folder", "Import PDF", "Infinite Canvas")) { _, choice ->
            if (choice == 4) {
                importFile.launch(arrayOf("application/pdf"))
                return@setItems
            }
            prompt(
                "Name",
                if (choice == 0) {
                    "Untitled.md"
                } else if (choice == 3) {
                    "New folder"
                } else if (choice == 5) {
                    "Untitled.excalidraw"
                } else {
                    "Untitled.pdf"
                }
            ) { name ->
                val path = VaultPath.join(folder, name)
                require(store.get(path)?.deleted != false) { "Name already exists" }
                when (choice) {
                    5 -> {
                        require(path.endsWith(".excalidraw", true)) { "Use the .excalidraw file extension" }
                        store.saveText(path, """{"type":"excalidraw","version":2,"source":"InkVault","elements":[],"appState":{"viewBackgroundColor":"#ffffff"},"files":{}}""")
                        open(path)
                    }
                    0 -> {
                        require(path.endsWith(".md"))
                        store.saveText(path, "")
                        open(path)
                    }
                    3 -> {
                        store.saveText("$path/.keep", "")
                        folder = path
                        expanded.add(VaultPath.folder(path))
                        refreshSidebar()
                    }
                    else -> {
                        val note = InkDocument.create(store, path, choice == 2)
                        open(note.manifestPath)
                    }
                }
                app.syncNow()
                refreshSidebar()
            }
        }.show()
    }
    private fun open(path: String) = safe {
        save()
        infiniteCanvas?.close()
        infiniteCanvas = null
        editor?.let { field ->
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
            field.clearFocus()
        }
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        pageCanvases.clear()
        dirtyPages.clear()
        pageScroll = null
        player?.release()
        player = null
        playbackPath = null
        playbackReady = false
        textUndo.clear()
        editor = null
        canvas = null
        markdownHeader = ""
        document = null
        pdfDimensions = emptyList()
        pageIndex = 0
        pageUndo = null
        selected = path
        preview = true
        content.removeAllViews()
        tools.removeAllViews()
        navigation.removeAllViews()
        title.text = VaultPath.name(path)
        dirty = false
        val entry = requireNotNull(store.get(path)) { "File unavailable" }
        store.setMeta("lastOpenFile", path)
        if (entry.conflict.isNotEmpty()) {
            conflict(path)
            return@safe
        }
        if (path.endsWith(".excalidraw", true) ||
            path.endsWith(".excalidraw.md", true) ||
            (
                path.endsWith(".md", true) &&
                    store.blobs.file(entry.hash).bufferedReader().use { reader ->
                        val prefix = CharArray(4096)
                        val count = reader.read(prefix)
                        count > 0 && Regex("(?m)^excalidraw-plugin:").containsMatchIn(String(prefix, 0, count))
                    }
                )
        ) {
            infiniteCanvas = InfiniteCanvas(this, store, path, entry.hash, onEdited = {
                dirty = true
                queueSave()
            }, onError = {
                status.text = "Infinite Canvas: $it"
            }).also {
                canvas = it.view
                content.addView(it.view, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            buildInkTools(null)
            canvas?.post { canvas?.let { BooxDisplay.apply(it, store.meta("displayMode") ?: "Writing") } }
        } else if (path.startsWith(".inkvault/") && path.endsWith("manifest.json")) {
            document = InkDocument(store, path)
            title.text = document!!.title
            showPage()
        } else {
            when (path.substringAfterLast('.').lowercase()) {
                "md", "txt" -> markdown(path)
                "pdf" -> io("Opening PDF") {
                    val dimensions = PageRenderer.dimensions(store.blobs.file(entry.hash))
                    withContext(Dispatchers.Main) {
                        if (selected == path) {
                            pdfDimensions = dimensions
                            showPage()
                        }
                    }
                }
                "png", "jpg", "jpeg", "svg" -> io("Opening image") {
                    val bitmap = PageRenderer.image(store.blobs.file(entry.hash), path.endsWith(".svg", true))
                    withContext(Dispatchers.Main) {
                        if (selected == path) {
                            content.addView(
                                ImageView(this@MainActivity).apply {
                                    setImageBitmap(bitmap)
                                    adjustViewBounds = true
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                },
                                LinearLayout.LayoutParams(-1, -1)
                            )
                        }
                    }
                }
                "mp3" -> {
                    content.addView(label("Audio · ${VaultPath.name(path)}"))
                    player = MediaPlayer().apply {
                        setDataSource(store.blobs.file(entry.hash).path)
                        setOnPreparedListener { status.text = "Audio ready" }
                        prepareAsync()
                    }
                    tools.addView(button("Play") { safe { player?.start() } })
                    tools.addView(button("Pause") { player?.pause() })
                }
                else -> content.addView(label("This file is saved locally. Preview is not available for this type."))
            }
        }
        refreshSidebar()
    }
    private fun markdown(path: String) {
        editor?.let { field ->
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
            field.clearFocus()
        }
        val entry = requireNotNull(store.get(path))
        require(entry.size <= 2 * 1024 * 1024) { "Markdown editor limit is 2 MiB; file remains intact" }
        openedHash = entry.hash
        val file = Markdown.file(store.blobs.file(entry.hash).readText())
        markdownHeader = file.header
        tools.removeAllViews()
        content.removeAllViews()
        editor = null
        typingPages = file.body.split(pageBreak).toMutableList()
        pageIndex = pageIndex.coerceIn(0, typingPages.lastIndex)
        val source = typingPages[pageIndex]
        tools.addView(
            button(if (preview) "Edit" else "View") {
                save()
                preview = !preview
                markdown(path)
                refreshSidebar()
            }
        )
        tools.addView(icon("metadata", "File Metadata") { showFileMetadata(file.properties) })
        listOf("heading" to "Heading", "bold" to "Bold", "italic" to "Italic").forEach { (glyph, name) -> tools.addView(icon(glyph, name) { formatText(glyph) }) }
        tools.addView(menuButton("bullet", "Lists", listOf("Bullet list", "Numbered list")) { formatText(listOf("bullet", "numbered")[it]) })
        tools.addView(menuButton("add", "Insert", listOf("Table", "Quote", "Section", "Image")) { formatText(listOf("table", "quote", "section", "image")[it]) })
        tools.addView(
            icon("undo", "Undo") {
                if (textUndo.isNotEmpty()) {
                    applyingTextUndo = true
                    editor?.setText(textUndo.removeLast())
                    applyingTextUndo = false
                }
            }
        )
        updateNavigation(typingPages.size)
        if (!preview) {
            editor = EditText(this).apply {
                val formatter = FormattedMarkdownEditor.create(this@MainActivity)
                addTextChangedListener(io.noties.markwon.editor.MarkwonEditorTextWatcher.withPreRender(formatter, markdownExecutor, this))
                setText(source)
                setTextColor(Color.BLACK)
                setHintTextColor(Color.BLACK)
                background = outline()
                setPadding(dp(12), dp(12), dp(12), dp(12))
                textSize = 19f
                gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                        if (!applyingTextUndo) {
                            textUndo.addLast(s.toString())
                            while (textUndo.size > 30) textUndo.removeFirst()
                        }
                    }
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        dirty = true
                        queueSave()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) = Unit
                })
            }
            content.addView(editor, LinearLayout.LayoutParams(-1, -1).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        } else {
            val scroll = ScrollView(this)
            val body = column().apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
            scroll.addView(body)
            content.addView(scroll, LinearLayout.LayoutParams(-1, -1))
            val markwon = Markwon.builder(this).usePlugin(
                io.noties.markwon.image.ImagesPlugin.create { plugin ->
                    plugin.addSchemeHandler(object : io.noties.markwon.image.SchemeHandler() {
                        override fun supportedSchemes(): Collection<String> = listOf("vault-image", "http", "https", "file", "data", "content", "")
                        override fun handle(raw: String, uri: android.net.Uri): io.noties.markwon.image.ImageItem {
                            require(uri.scheme == "vault-image" || uri.scheme.isNullOrEmpty()) { "Only local vault images are displayed" }
                            val target = if (uri.scheme == "vault-image") java.net.URLDecoder.decode(raw.removePrefix("vault-image:"), "UTF-8") else raw
                            val resolved = requireNotNull(Markdown.resolve(path, target, store.entries().filter { !it.deleted }.map { it.path })) { "Image unavailable locally" }
                            val bitmap = requireNotNull(PageRenderer.image(store.blobs.file(requireNotNull(store.get(resolved)).hash), resolved.endsWith(".svg", true)))
                            return io.noties.markwon.image.ImageItem.withResult(android.graphics.drawable.BitmapDrawable(resources, bitmap))
                        }
                    })
                }
            ).usePlugin(TablePlugin.create { theme -> theme.tableBorderColor(Color.BLACK).tableBorderWidth(dp(1)).tableOddRowBackgroundColor(Color.WHITE).tableEvenRowBackgroundColor(Color.WHITE).tableHeaderRowBackgroundColor(Color.WHITE) }).usePlugin(StrikethroughPlugin.create()).usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: io.noties.markwon.core.MarkwonTheme.Builder) {
                    builder.blockQuoteColor(Color.BLACK).codeBackgroundColor(Color.WHITE).codeTextColor(Color.BLACK).thematicBreakColor(Color.BLACK).linkColor(Color.BLACK)
                }
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { _, link ->
                        val target = if (link.startsWith("inkvault:")) java.net.URLDecoder.decode(link.removePrefix("inkvault:"), "UTF-8") else link
                        val resolved = Markdown.resolve(path, target, store.entries().filter { !it.deleted }.map { it.path })
                        if (resolved != null) open(resolved) else message("Link unavailable locally: $target")
                    }
                }
            }).build()
            val text = label("")
            text.setTextIsSelectable(true)
            markwon.setMarkdown(text, Markdown.preview(source))
            body.addView(text)
        }
    }
    private fun showFileMetadata(properties: List<Markdown.Property>) {
        val rows = column().apply { setPadding(dp(12), dp(4), dp(12), dp(4)) }
        if (properties.isEmpty()) {
            rows.addView(label("No file properties"))
        } else {
            properties.forEach { property ->
                rows.addView(
                    label(property.name).apply {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(0, dp(8), 0, 0)
                    }
                )
                rows.addView(label(property.value.ifBlank { "—" }).apply { setPadding(0, 0, 0, dp(8)) })
            }
        }
        val scroll = ScrollView(this).apply { addView(rows) }
        AlertDialog.Builder(this).setTitle("File Metadata").setView(scroll).setNegativeButton("Close", null).show()
    }
    private fun showPage() {
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        pageCanvases.values.forEach { BooxDisplay.apply(it, "System") }
        pageCanvases.clear()
        dirtyPages.clear()
        pageScroll = null
        content.removeAllViews()
        tools.removeAllViews()
        val note = document
        val count = note?.count ?: pdfDimensions.size
        if (count == 0) return
        pageIndex = pageIndex.coerceIn(0, count - 1)
        val preferenceKey = if (note != null && !note.annotation) "notePageLayout" else "pdfPageLayout"
        val scrolling = PageLayoutMode.fromStored(store.meta(preferenceKey)) == PageLayoutMode.SCROLL
        val sidebarVisible = sidebar.visibility == View.VISIBLE
        fun createPageView(index: Int): InkCanvas {
            val page = note?.page(index) ?: pdfDimensions[index].let { InkPage(it.first, it.second) }
            val fit = PageFitMode.fromStored(store.meta(PageFitMode.preferenceKey(page.width > page.height, sidebarVisible)))
            return InkCanvas(this).apply {
                show(page)
                scrollingPage = scrolling
                fitToViewport = true
                pageFitMode = fit
                writable = note != null
                hardwareEnabled = false
                failed = { status.text = it }
                changed = {
                    dirtyPages.add(index)
                    dirty = true
                    queueSave()
                }
                activated = { activatePage(index, count) }
                asset = { path -> store.get(path)?.let { PageRenderer.image(store.blobs.file(it.hash), path.endsWith(".svg")) } }
            }.also { pageCanvases[index] = it }
        }
        if (scrolling) {
            val pages = column().apply {
                setPadding(0, dp(12), 0, dp(12))
                background = pageOutsideBackground()
            }
            repeat(count) { index ->
                val pageView = createPageView(index)
                pages.addView(pageView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            }
            pageScroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(pages, FrameLayout.LayoutParams(-1, -2))
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    pageCanvases.values.forEach { page ->
                        if (page.viewportHeight != height) {
                            page.viewportHeight = height
                            page.requestLayout()
                        }
                    }
                }
                setOnScrollChangeListener { _, _, _, _, _ ->
                    val center = scrollY + height / 2
                    pageCanvases.minByOrNull { (_, page) -> kotlin.math.abs((page.top + page.bottom) / 2 - center) }?.key?.let { activatePage(it, count) }
                    updateVisiblePageBackgrounds(this, note)
                }
            }
            content.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val pageView = createPageView(pageIndex)
            val single = FrameLayout(this).apply { background = pageOutsideBackground() }
            val height = if (pageView.pageFitMode == PageFitMode.WIDTH) -2 else -1
            single.addView(pageView, FrameLayout.LayoutParams(-1, height, Gravity.CENTER_VERTICAL))
            content.addView(single, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        activatePage(pageIndex, count)
        canvas?.post {
            BooxDisplay.apply(requireNotNull(canvas), store.meta("displayMode") ?: "Writing")
            if (scrolling) updateVisiblePageBackgrounds(requireNotNull(pageScroll), note) else loadPageBackground(pageIndex, requireNotNull(canvas), pageBackground(note, pageIndex))
        }
        if (scrolling && pageIndex > 0) pageScroll?.post { pageScroll?.let { scroll -> pageCanvases[pageIndex]?.let { scroll.scrollTo(0, it.top) } } }
        if (note != null) buildInkTools(note) else tools.addView(button("Annotate PDF") { annotate() })
        refreshSidebar()
    }
    private fun buildInkTools(note: InkDocument?) {
        val defaults = documentPresets()
        val initial = requireNotNull(canvas)
        repeat(3) { index -> tools.addView(presetButton(initial, defaults, index)) }
        tools.addView(icon("pens", "More pens") { showMorePens(requireNotNull(canvas)) }.apply { tag = "tool:pens" })
        tools.addView(
            icon("eraser", "Eraser") {
                requireNotNull(canvas).tool = "eraser"
                updateActiveTools()
            }.apply { tag = "tool:eraser" }
        )
        tools.addView(
            icon("lasso", "Lasso") {
                requireNotNull(canvas).tool = "lasso"
                requireNotNull(canvas).clearSelection()
                updateActiveTools()
            }.apply { tag = "tool:lasso" }
        )
        tools.addView(
            menuButton("add", "Insert", listOf("Image", "Forms")) { index ->
                if (index == 0) {
                    chooseAsset(false)
                } else {
                    AlertDialog.Builder(this).setTitle("Forms").setItems(arrayOf("Line", "Rectangle", "Ellipse", "Arrow")) { _, shape ->
                        requireNotNull(canvas).tool = arrayOf("line", "rectangle", "ellipse", "arrow")[shape]
                        updateActiveTools()
                    }.show()
                }
            }
        )
        tools.addView(
            icon("undo", "Undo") {
                if (note != null && pageUndo != null) {
                    note.restoreManifest(requireNotNull(pageUndo))
                    pageUndo = null
                    showPage()
                } else {
                    requireNotNull(canvas).undo()
                }
            }
        )
        selectPreset(initial, defaults.getJSONObject(0), 0)
    }
    private fun pageBackground(note: InkDocument?, index: Int): Pair<String, Int>? = if (note?.annotation == true) {
        note.manifest.getString("basePdfHash") to index
    } else if (note != null) {
        note.pageInfo(index).optJSONObject("template")?.let { template ->
            store.get(template.getString("asset"))?.hash?.let { hash -> hash to template.getInt("page") }
        }
    } else {
        store.get(requireNotNull(selected))?.hash?.let { it to index }
    }
    private fun loadPageBackground(index: Int, view: InkCanvas, background: Pair<String, Int>?) {
        if (background == null || view.backgroundPage != null || pageJobs[index]?.isActive == true) return
        pageJobs[index] = lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { PageRenderer.pdf(store.blobs.file(background.first), background.second) }
                if (pageCanvases[index] === view) view.backgroundPage = bitmap else bitmap.recycle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Page preview failed: ${e.message}"
            }
        }
    }
    private fun updateVisiblePageBackgrounds(scroll: ScrollView, note: InkDocument?) {
        val top = scroll.scrollY - scroll.height
        val bottom = scroll.scrollY + scroll.height * 2
        pageCanvases.forEach { (index, view) ->
            if (view.bottom >= top && view.top <= bottom) {
                loadPageBackground(index, view, pageBackground(note, index))
            } else {
                pageJobs.remove(index)?.cancel()
                view.backgroundPage = null
            }
        }
    }
    private fun pageOutsideBackground(): android.graphics.drawable.Drawable {
        if (!BooxFirmware.available) return android.graphics.drawable.ColorDrawable(Color.LTGRAY)
        return object : android.graphics.drawable.Drawable() {
            private val stripe = android.graphics.Paint().apply {
                color = Color.LTGRAY
                strokeWidth = dp(4).toFloat()
            }
            override fun draw(canvas: android.graphics.Canvas) {
                canvas.drawColor(Color.WHITE)
                var x = -bounds.height()
                while (x < bounds.width()) {
                    canvas.drawLine(x.toFloat(), bounds.height().toFloat(), (x + bounds.height()).toFloat(), 0f, stripe)
                    x += dp(14)
                }
            }
            override fun setAlpha(alpha: Int) {
                stripe.alpha = alpha
            }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) {
                stripe.colorFilter = filter
            }

            @Deprecated("Deprecated in Android")
            override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
        }
    }
    private fun save(encodedPage: ByteArray? = null) {
        val canvasSaved = infiniteCanvas?.persist() == true
        if (canvasSaved) dirty = true
        if (!dirty) return
        autosaveJob?.cancel()
        val path = selected ?: return
        if (document != null && canvas != null) {
            val pages = dirtyPages.ifEmpty { mutableSetOf(pageIndex) }.toList().sorted()
            pages.forEach { index ->
                val page = pageCanvases[index] ?: if (index == pageIndex) canvas else null
                if (index == pageIndex && encodedPage != null) {
                    document!!.saveEncodedPage(index, encodedPage)
                } else if (page != null) {
                    document!!.savePage(index, page.page)
                }
            }
            dirtyPages.clear()
        } else if (editor != null) {
            typingPages[pageIndex] = editor!!.text.toString()
            openedHash = store.saveEditedText(path, markdownHeader + typingPages.joinToString(pageBreak), openedHash).hash
        }
        dirty = false
        status.text = if (canvas?.directInkActive == true) "Locally saved · BOOX direct ink · ${canvas?.directInkStrokes} SDK strokes" else "Locally saved · Pending sync"
        app.syncNow()
    }
    private fun pageActions(note: InkDocument) {
        val options = arrayOf("Add portrait", "Add landscape", "Duplicate page", "Move earlier", "Move later", "Delete page")
        AlertDialog.Builder(this).setItems(options) { _, choice ->
            safe {
                save()
                when (choice) {
                    0, 1, 2 -> pageIndex = note.addPage(pageIndex, choice == 1, choice == 2)
                    3 -> if (pageIndex > 0) {
                        note.reorder(pageIndex, pageIndex - 1)
                        pageIndex--
                    }
                    4 -> if (pageIndex < note.count - 1) {
                        note.reorder(pageIndex, pageIndex + 1)
                        pageIndex++
                    }
                    5 -> {
                        pageUndo = note.deletePage(pageIndex)
                        pageIndex = pageIndex.coerceAtMost(note.count - 1)
                    }
                }
                showPage()
                app.syncNow()
            }
        }.show()
    }
    private fun annotate() = safe {
        val path = requireNotNull(selected)
        store.pairRoot(path)?.let { root ->
            open("$root/manifest.json")
            return@safe
        }
        val entry = requireNotNull(store.get(path))
        val note = InkDocument.create(store, path, baseHash = entry.hash, dimensions = pdfDimensions)
        open(note.manifestPath)
    }
    private fun chooseAsset(template: Boolean) {
        val folder = store.meta("templatesFolder") ?: "Templates"
        val entries = store.entries().filter { !it.deleted && !VaultPath.hidden(it.path) && if (template) it.path.startsWith("$folder/") && it.path.endsWith(".pdf", true) else it.path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "svg") }
        AlertDialog.Builder(this).setTitle(if (template) "PDF template from /$folder" else "Insert vault image").setItems(entries.map { it.path }.toTypedArray()) { _, index ->
            safe {
                val entry = entries[index]
                if (infiniteCanvas != null && !template) {
                    infiniteCanvas!!.insertImage(entry.path)
                    return@safe
                }
                val note = requireNotNull(document)
                if (template) {
                    io("Reading template pages") {
                        val dimensions = PageRenderer.dimensions(store.blobs.file(entry.hash))
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(this@MainActivity).setItems(dimensions.indices.map { "Page ${it + 1}" }.toTypedArray()) { _, p ->
                                safe {
                                    save()
                                    note.template(pageIndex, entry.path, p)
                                    showPage()
                                }
                            }.show()
                        }
                    }
                } else {
                    val asset = note.asset(entry.path)
                    val view = requireNotNull(canvas)
                    view.edit(view.page.copy(objects = view.page.objects + mapOf("id" to UUID.randomUUID().toString(), "asset" to asset, "x" to 20000L, "y" to 20000L, "width" to 80000L, "height" to 80000L)))
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }
    private fun editPreset(array: JSONArray, index: Int, marker: Boolean, old: JSONObject?, anchor: View = tools) {
        val documentKey = presetKey()
        val targetCanvas = canvas
        val preset = old ?: JSONObject().put("name", if (marker) "Marker" else "Pen ${index + 1}")
            .put("tool", if (marker) "marker" else "pen").put("type", if (marker) "Marker" else "Pen")
            .put("width", if (marker) 4000 else 600).put("color", 0xff000000L).put("pressure", !marker)
        var type = preset.optString("type", if (preset.optString("tool") == "marker") "Marker" else "Pen")
        var size = preset.getLong("width")
        var sensitivity = preset.optInt("sensitivity", if (preset.optBoolean("pressure")) 100 else 0)
        var color = preset.getLong("color")
        val body = column().apply {
            background = outline()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val popup = PopupWindow(body, dp(640).coerceAtMost(resources.displayMetrics.widthPixels - dp(20)), -2, true).apply {
            setBackgroundDrawable(outline())
            elevation = 0f
        }
        fun persist() {
            preset.put("type", type).put("tool", if (type == "Marker") "marker" else "pen")
                .put("width", size).put("color", color).put("sensitivity", sensitivity).put("pressure", sensitivity > 0)
            array.put(index, preset)
            store.setMeta(documentKey, array.toString())
            presetIcons.removeAll { it.get() == null }
            presetIcons.forEach { it.get()?.invalidateSelf() }
            targetCanvas?.let { selectPreset(it, preset, index) }
        }
        val types = row()
        types.addView(label("Type"), LinearLayout.LayoutParams(dp(72), -2))
        val choices = mutableListOf<Button>()
        fun updateTypes() {
            choices.forEach {
                it.background = outline(it.text == type)
                it.setTextColor(if (it.text == type) Color.WHITE else Color.BLACK)
            }
        }
        listOf("Pen", "Pencil", "Marker").forEach { name ->
            choices.add(
                button(name) {
                    type = name
                    persist()
                    updateTypes()
                }.also { types.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
            )
        }
        updateTypes()
        body.addView(types)
        val sizeRow = row()
        sizeRow.addView(label("Size"), LinearLayout.LayoutParams(dp(72), -2))
        val dot = object : View(this) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: android.graphics.Canvas) {
                paint.color = color.toInt()
                canvas.drawCircle(width / 2f, height / 2f, (size / 1000f * resources.displayMetrics.xdpi / 25.4f / 2).coerceIn(1f, dp(20).toFloat()), paint)
            }
        }.apply { contentDescription = "Selected pen size preview" }
        fun slider(maximum: Int, value: Int, description: String, change: (Int) -> Unit) = SeekBar(this).apply {
            max = maximum
            progress = value
            contentDescription = description
            progressTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) change(progress)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) {
                    persist()
                }
            })
        }
        sizeRow.addView(
            slider(1995, ((size - 50) / 10).toInt(), "Pen size") {
                size = 50L + it * 10
                dot.invalidate()
                dot.contentDescription = "Pen size ${size / 1000.0} mm"
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        sizeRow.addView(dot, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(sizeRow)
        val pressureRow = row()
        pressureRow.addView(label("Pressure\nsensitivity"), LinearLayout.LayoutParams(dp(100), -2))
        pressureRow.addView(slider(100, sensitivity, "Pressure sensitivity") { sensitivity = it }, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(pressureRow)
        val colors = row()
        colors.addView(label("Color"), LinearLayout.LayoutParams(dp(60), -2))
        val palette = listOf("Black" to "#000000", "Grey" to "#808080", "Red" to "#FF0000", "Orange" to "#FF8000", "Yellow" to "#FFFF00", "Lime" to "#BFFF00", "Green" to "#008000", "Teal" to "#008080", "Cyan" to "#00FFFF", "Blue" to "#0000FF", "Purple" to "#800080", "Pink" to "#FF69B4")
        val swatches = mutableListOf<View>()
        fun updateColors() {
            swatches.forEachIndexed { i, v ->
                v.background = GradientDrawable().apply {
                    setColor(Color.parseColor(palette[i].second))
                    setStroke(dp(if ((Color.parseColor(palette[i].second).toLong() and 0xffffffffL) == color) 3 else 1), if (i == 0 && color == 0xff000000L) Color.WHITE else Color.BLACK)
                }
            }
        }
        palette.forEach { (name, hex) ->
            val swatch = View(this).apply {
                contentDescription = name
                isFocusable = true
                setOnClickListener {
                    color = Color.parseColor(hex).toLong() and 0xffffffffL
                    persist()
                    dot.invalidate()
                    updateColors()
                }
            }
            swatches.add(swatch)
            colors.addView(swatch, LinearLayout.LayoutParams(0, dp(36), 1f).apply { setMargins(dp(1), dp(6), dp(1), dp(6)) })
        }
        updateColors()
        body.addView(colors)
        popup.setOnDismissListener { persist() }
        popup.showAsDropDown(anchor)
    }
    private fun fileActions(path: String, action: String? = null) {
        val root = store.pairRoot(path)
        val visible = root?.let { InkDocument(store, "$it/manifest.json").manifest.getString("pdfPath") } ?: path
        require(!path.startsWith(".inkvault/") || root != null) { "Internal files are managed by their document" }
        when (action) {
            "rename", "move" -> prompt(if (action == "rename") "New filename" else "New vault path", if (action == "rename") VaultPath.name(visible) else visible) { name ->
                val destination = if (action == "rename") VaultPath.join(VaultPath.folder(visible), name) else name
                save()
                val newPath = store.moveDocument(path, destination)
                if (selected == path) open(newPath)
                app.syncNow()
                refreshSidebar()
            }
            "delete" -> AlertDialog.Builder(this).setMessage("Delete $visible?").setPositiveButton("Delete") { _, _ ->
                safe {
                    save()
                    store.deleteDocument(path)
                    closeDeletedDocument()
                    app.syncNow()
                    refreshSidebar()
                }
            }.setNegativeButton("Cancel", null).show()
            else -> AlertDialog.Builder(this).setTitle(visible).setItems(arrayOf("Rename", "Move", "Delete", "History")) { _, choice -> safe { if (choice == 3) history(path) else fileActions(path, arrayOf("rename", "move", "delete")[choice]) } }.show()
        }
    }
    private fun closeDeletedDocument() {
        if (selected?.let { store.get(it)?.deleted } == true) {
            selected = null
            dirty = false
            editor = null
            canvas = null
            infiniteCanvas?.close()
            infiniteCanvas = null
            document = null
            content.removeAllViews()
            tools.removeAllViews()
            navigation.removeAllViews()
            title.text = "InkVault"
        }
    }
    private fun history(path: String) {
        sidebarMode = "history"
        sidebarBody.removeAllViews()
        sidebarBody.addView(label("History · ${document?.title ?: VaultPath.name(path)}"))
        sidebarBody.addView(label("Current local version is saved on this tablet."))
        if (store.meta("server").isNullOrBlank()) {
            sidebarBody.addView(label("Connect a server in Settings to view synced revisions."))
            return
        }
        io("Loading history") {
            val revisions = app.client().history(path).getJSONArray("entries")
            withContext(Dispatchers.Main) {
                if (sidebarMode != "history" || selected != path) return@withContext
                val scroll = ScrollView(this@MainActivity)
                val rows = column()
                if (revisions.length() == 0) rows.addView(label("No synced revisions yet."))
                for (index in 0 until revisions.length()) {
                    val revision = revisions.getJSONObject(index)
                    rows.addView(label("${revision.optString("date")}\n${revision.optString("subject")}\n${revision.optString("author")} · ${revision.optString("hash").take(8)}").apply { background = outline() })
                }
                scroll.addView(rows)
                sidebarBody.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
    }
    private fun conflict(path: String) {
        val entry = requireNotNull(store.get(path))
        content.addView(label("Conflict · ${entry.conflict}\nBoth local and received content are retained."))
        val text = path.endsWith(".md") || path.endsWith(".txt")
        if (text) {
            editor = EditText(this).apply {
                gravity = Gravity.TOP
                setText(store.conflictRemote(path)?.let { store.blobs.file(it).readText() } ?: store.blobs.file(entry.hash).readText())
            }
            content.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        tools.addView(button(if (text) "Resolve edited text" else "Keep local version") { resolve(path, if (text) editor!!.text.toString() else null, false) })
        tools.addView(button("Use received version") { resolve(path, null, true) })
    }
    private fun resolve(path: String, text: String?, remote: Boolean) {
        io("Resolving conflict") {
            synchronized(app.syncLock) {
                val pair = store.pairRoot(path)
                if (pair != null) {
                    app.resolvePair(pair, remote)
                    return@synchronized
                }
                if (text != null) {
                    require(!Regex("(?m)^(<<<<<<<|=======|>>>>>>>)").containsMatchIn(text)) { "Remove the conflict markers first" }
                    store.saveText(path, text)
                }
                val entry = requireNotNull(store.get(path))
                val hash = if (remote) store.conflictRemote(path) else entry.hash
                val client = app.client()
                val file = JSONObject().put("path", path)
                if (hash == null) file.put("delete", true) else file.put("uploadId", client.upload(path, store.blobs.file(hash), hash))
                val response = client.resolve(requireNotNull(store.meta("clientId")), "InkVault", JSONArray().put(file), "syncFileReferences" in client.info())
                store.journal(response, store.entries(), setOf(path))
                app.finishJournal(client)
                if (response.getString("status") == "ok") store.clearConflict(path)
            }
            withContext(Dispatchers.Main) {
                dirty = false
                editor = null
                open(path)
                app.syncNow()
            }
        }
    }
    private fun settings() {
        val fields = column()
        fields.addView(label("Page display").apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        fun pageLayoutSetting(name: String, key: String, appliesToOpenDocument: () -> Boolean) {
            fields.addView(label(name))
            val choices = row()
            lateinit var single: Button
            lateinit var scrolling: Button
            fun updateButtons() {
                val mode = PageLayoutMode.fromStored(store.meta(key))
                single.background = outline(mode == PageLayoutMode.SINGLE)
                single.setTextColor(if (mode == PageLayoutMode.SINGLE) Color.WHITE else Color.BLACK)
                scrolling.background = outline(mode == PageLayoutMode.SCROLL)
                scrolling.setTextColor(if (mode == PageLayoutMode.SCROLL) Color.WHITE else Color.BLACK)
            }
            fun select(mode: PageLayoutMode) {
                save()
                store.setMeta(key, mode.storedValue)
                updateButtons()
                if (appliesToOpenDocument()) showPage()
            }
            single = button("One page") { select(PageLayoutMode.SINGLE) }
            scrolling = button("Scrolling pages") { select(PageLayoutMode.SCROLL) }
            choices.addView(single, LinearLayout.LayoutParams(0, dp(48), 1f))
            choices.addView(scrolling, LinearLayout.LayoutParams(0, dp(48), 1f))
            fields.addView(choices)
            updateButtons()
        }
        pageLayoutSetting("Notes", "notePageLayout") { document?.annotation == false }
        pageLayoutSetting("PDFs", "pdfPageLayout") { pdfDimensions.isNotEmpty() || document?.annotation == true }
        fields.addView(label("Scrolling pages are separated by an outside margin and every page keeps a visible border, so the drawable area remains clear."))
        fields.addView(label("Page fit").apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        fun pageFitSetting(name: String, landscape: Boolean, sidebarOpen: Boolean) {
            fields.addView(label(name))
            val key = PageFitMode.preferenceKey(landscape, sidebarOpen)
            val choices = row()
            lateinit var width: Button
            lateinit var height: Button
            fun updateButtons() {
                val mode = PageFitMode.fromStored(store.meta(key))
                width.background = outline(mode == PageFitMode.WIDTH)
                width.setTextColor(if (mode == PageFitMode.WIDTH) Color.WHITE else Color.BLACK)
                height.background = outline(mode == PageFitMode.HEIGHT)
                height.setTextColor(if (mode == PageFitMode.HEIGHT) Color.WHITE else Color.BLACK)
            }
            fun select(mode: PageFitMode) {
                save()
                store.setMeta(key, mode.storedValue)
                updateButtons()
                if (sidebarOpen && (document != null || pdfDimensions.isNotEmpty())) showPage()
            }
            width = button("Fit width") { select(PageFitMode.WIDTH) }
            height = button("Fit height") { select(PageFitMode.HEIGHT) }
            choices.addView(width, LinearLayout.LayoutParams(0, dp(48), 1f))
            choices.addView(height, LinearLayout.LayoutParams(0, dp(48), 1f))
            fields.addView(choices)
            updateButtons()
        }
        pageFitSetting("Portrait · sidebar open", false, true)
        pageFitSetting("Portrait · sidebar closed", false, false)
        pageFitSetting("Landscape · sidebar open", true, true)
        pageFitSetting("Landscape · sidebar closed", true, false)
        fields.addView(label("Fit width uses the full document width and marks the page top and bottom. Fit height fills the available height and crops the page sides when necessary."))
        fields.addView(label("Server and folders").apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val server = input("HTTPS ObsidiSync server", store.meta("server") ?: "")
        val vault = input("Vault slug", store.meta("vault") ?: "")
        val user = input("Username", store.meta("user") ?: "")
        val password = input("Password", "").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }

        val recordings = input("Recordings folder", store.meta("recordingsFolder") ?: "Recordings")
        val templates = input("Templates folder", store.meta("templatesFolder") ?: "Templates")
        listOf(server, vault, user, password, recordings, templates).forEach { fields.addView(it) }
        fields.addView(label("One vault per installation. Existing vault bindings cannot be changed while local files exist. Handwriting sync requires an updated ObsidiSync server."))
        fun persist() {
            val endpoint = server.text.toString().trim().trimEnd('/')
            require(endpoint.startsWith("https://")) { "HTTPS is required" }
            val slug = vault.text.toString().trim()
            require(slug.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid vault slug" }
            if (store.meta("server") != null && store.entries().isNotEmpty()) require(endpoint == store.meta("server") && slug == store.meta("vault")) { "Existing local vault is bound to its server" }
            VaultPath.requireValid(recordings.text.toString())
            VaultPath.requireValid(templates.text.toString())
            if (store.meta("server") != endpoint || store.meta("vault") != slug) {
                require(store.meta("head") == null && store.meta("journal") == null) { "The local revision is bound to its original server and vault" }
                store.setMeta("user", null)
            }
            store.setMeta("server", endpoint)
            store.setMeta("vault", slug)
            store.setMeta("recordingsFolder", recordings.text.toString())
            store.setMeta("templatesFolder", templates.text.toString())
        }
        fields.addView(
            button("Password login") {
                safe {
                    persist()
                    val name = user.text.toString()
                    val secret = password.text.toString()
                    password.setText("")
                    io("Logging in") {
                        app.client().login(name, secret)
                        withContext(Dispatchers.Main) { status.text = "Logged in · Sync now to merge this local vault" }
                    }
                }
            }
        )
        fields.addView(
            button("OIDC device login") {
                safe {
                    persist()
                    oidcLogin()
                }
            }
        )
        fields.addView(
            button("Initialize server vault") {
                safe {
                    persist()
                    prompt("Server Git remote URL (empty for server-only). This updates the selected server vault configuration.", "") { remote ->
                        io("Registering server vault") {
                            app.client().register(remote)
                            withContext(Dispatchers.Main) { status.text = "Server vault registered · Sync now" }
                        }
                    }
                }
            }
        )
        val scroll = ScrollView(this)
        scroll.addView(fields)
        fields.addView(label("Documents save automatically after edits."))
        settingsPersist = {
            VaultPath.requireValid(recordings.text.toString())
            VaultPath.requireValid(templates.text.toString())
            store.setMeta("recordingsFolder", recordings.text.toString())
            store.setMeta("templatesFolder", templates.text.toString())
            if (server.text.isNotBlank() && vault.text.isNotBlank()) persist()
        }
        listOf(server, vault, recordings, templates).forEach { field -> field.setOnFocusChangeListener { _, focused -> if (!focused) runCatching { settingsPersist?.invoke() }.onFailure { status.text = it.message } } }
        sidebarBody.removeAllViews()
        sidebarBody.addView(scroll, LinearLayout.LayoutParams(-1, -1))
    }
    private fun oidcLogin() {
        io("Starting device login") {
            val client = app.client()
            client.info()
            val config = client.authConfig()
            require(config.getString("type") == "oidc") { "Server does not use OIDC" }
            val discovery = client.discover(config.getString("issuer"))
            val fields = mutableMapOf("client_id" to config.getString("clientId"), "scope" to config.optString("scope", "openid profile email"))
            config.optString("audience").takeIf { it.isNotBlank() && it != "null" }?.let {
                fields["audience"] = it
                fields["resource"] = it
            }
            val authorization = client.oidcForm(discovery.getString("device_authorization_endpoint"), fields)
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity).setTitle("Complete sign-in in your browser").setMessage("${authorization.getString("verification_uri")}\nCode: ${authorization.getString("user_code")}").setPositiveButton("Open browser") { _, _ ->
                    val uri = android.net.Uri.parse(authorization.optString("verification_uri_complete", authorization.getString("verification_uri")))
                    if (uri.scheme == "https") startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                }.setNegativeButton("Close", null).show()
            }
            var interval = authorization.optLong("interval", 5).coerceAtLeast(1)
            val deadline = System.currentTimeMillis() + authorization.getLong("expires_in").coerceAtMost(1800) * 1000
            fields["grant_type"] = "urn:ietf:params:oauth:grant-type:device_code"
            fields["device_code"] = authorization.getString("device_code")
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000)
                val response = client.oidcForm(discovery.getString("token_endpoint"), fields)
                if (response.has("access_token")) {
                    client.exchange(response.getString("access_token"))
                    withContext(Dispatchers.Main) { status.text = "Logged in · Sync now to merge this local vault" }
                    return@io
                }
                when (response.optString("error")) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5
                    else -> error("OIDC login failed: ${response.optString("error")}")
                }
            }
            error("OIDC login expired")
        }
    }
    private fun io(label: String, block: suspend () -> Unit) {
        status.text = label
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { status.text = "$label failed: ${e.message}" }
            }
        }
    }
    private fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            message(e.message ?: "Operation failed")
        }
    }
    private fun message(text: String) {
        status.text = text
        AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show()
    }
    private fun prompt(title: String, initial: String, action: (String) -> Unit) {
        val edit = input(title, initial).apply { setSelectAllOnFocus(true) }
        AlertDialog.Builder(this).setTitle(title).setView(edit).setPositiveButton("Apply") { _, _ -> safe { action(edit.text.toString().trim()) } }.setNegativeButton("Cancel", null).show()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        setTextColor(Color.BLACK)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }
    private fun input(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        background = outline()
        setTextColor(Color.BLACK)
        setHintTextColor(Color.BLACK)
        textSize = 18f
        setSingleLine(true)
    }
    private fun button(value: String, action: () -> Unit): Button {
        val glyph = mapOf("Eraser" to "eraser", "Lasso" to "lasso", "Undo" to "undo", "Redo" to "redo", "Insert image" to "image", "Fit page" to "fit", "Preview" to "eye", "Edit source" to "pen", "Play" to "play", "Pause" to "stop")[value]
        if (glyph != null) return icon(glyph, value, action = action)
        return Button(this).apply {
            text = value
            isAllCaps = false
            minHeight = dp(48)
            elevation = 0f
            stateListAnimator = null
            setTextColor(Color.BLACK)
            background = outline()
            setOnClickListener { safe(action) }
        }
    }
    private fun adapter(values: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, values) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = (super.getView(position, convertView, parent) as TextView).apply {
            setTextColor(Color.BLACK)
            background = outline()
            textSize = 16f
        }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}

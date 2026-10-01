package de.tobisk.inkvault.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import de.tobisk.inkvault.data.VaultStore
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/** Package-checked metadata and first-page previews for Ink Launcher. */
class LauncherNotesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        requireLauncherCaller()
        val store = VaultStore(requireNotNull(context))
        val entries = store.entries().associateBy { it.path }
        val notes = entries.values.asSequence()
            .filter { !it.deleted && it.path.startsWith(".inkvault/notes/") && it.path.endsWith("/manifest.json") }
            .mapNotNull { manifest -> runCatching { JSONObject(store.blobs.file(manifest.hash).readText()).let { data -> Note(manifest.path, data.optString("title"), data.optString("pdfPath"), manifest.modified) } }.getOrNull() }
            .sortedByDescending { it.modified }
            .take(4)
            .toList()
        return MatrixCursor(arrayOf("_id", "path", "title", "modified", "thumbnail")).apply {
            notes.forEachIndexed { index, note -> addRow(arrayOf(index, note.path, note.title, note.modified, entries[note.pdfPath]?.let { preview(store, it.hash) })) }
        }
    }

    private fun preview(store: VaultStore, hash: String): ByteArray? = runCatching {
        ParcelFileDescriptor.open(store.blobs.file(hash), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { document ->
                if (document.pageCount == 0) return@use null
                document.openPage(0).use { page ->
                    val width = 300
                    val height = (width * page.height.toFloat() / page.width).toInt().coerceAtMost(420)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)
                        bitmap.recycle()
                        output.toByteArray()
                    }
                }
            }
        }
    }.getOrNull()

    private fun requireLauncherCaller() {
        val packages = requireNotNull(context).packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        check(packages.contains("de.tobisk.inklauncher")) { "This provider is only available to Ink Launcher" }
    }

    private data class Note(val path: String, val title: String, val pdfPath: String, val modified: Long)
    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.tobisk.inkvault.launcher-note"
    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}

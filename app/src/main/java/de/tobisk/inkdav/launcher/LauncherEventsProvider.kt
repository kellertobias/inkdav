package de.tobisk.inkdav.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import de.tobisk.inkdav.data.InkDavDatabase
import kotlinx.coroutines.runBlocking

/** Narrow, package-checked read surface for the companion home screen. */
class LauncherEventsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        requireLauncherCaller()
        val context = requireNotNull(context)
        val now = System.currentTimeMillis()
        val occurrences = runBlocking { InkDavDatabase.get(context).dao().upcomingOccurrences(now, 5) }
            .filter { it.startEpochMillis < now + 48L * 60 * 60 * 1000 }
        val collections = runBlocking { occurrences.associate { it.collectionId to InkDavDatabase.get(context).dao().collection(it.collectionId) } }
        return MatrixCursor(arrayOf("_id", "title", "calendar", "color", "start", "end", "allDay")).apply {
            occurrences.forEachIndexed { index, event ->
                val collection = collections[event.collectionId]
                addRow(arrayOf(index, event.title, collection?.displayName.orEmpty(), collection?.colorArgb ?: 0xff243b53, event.startEpochMillis, event.endEpochMillis, if (event.allDay) 1 else 0))
            }
        }
    }

    private fun requireLauncherCaller() {
        val packages = requireNotNull(context).packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        check(packages.contains("de.tobisk.inklauncher")) { "This provider is only available to Ink Launcher" }
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.tobisk.inkdav.launcher-event"
    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}

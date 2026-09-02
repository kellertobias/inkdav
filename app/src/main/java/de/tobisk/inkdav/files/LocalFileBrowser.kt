package de.tobisk.inkdav.files

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalFileEntry(
    val uri: String,
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String?,
    val sizeBytes: Long?,
    val modifiedAt: Long?
)

data class LocalFolderLocation(val relativePath: String, val name: String)

class LocalFileBrowser(private val context: Context) {
    suspend fun root(uri: String): Pair<LocalFolderLocation, List<LocalFileEntry>> = withContext(Dispatchers.IO) {
        val document = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(uri))) {
            "The selected local folder is unavailable."
        }
        LocalFolderLocation("", document.name ?: "Local files") to list(document, "")
    }

    suspend fun folder(rootUri: String, relativePath: String, name: String): Pair<LocalFolderLocation, List<LocalFileEntry>> = withContext(Dispatchers.IO) {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(rootUri))) {
            "The selected local folder is unavailable."
        }
        val document = relativePath.split('/').filter(String::isNotBlank).fold(root) { parent, component ->
            requireNotNull(parent.findFile(component)) { "The local folder is unavailable." }
        }
        require(document.isDirectory) { "The selected item is not a folder." }
        LocalFolderLocation(relativePath, name) to list(document, relativePath)
    }

    private fun list(folder: DocumentFile, parentPath: String): List<LocalFileEntry> = folder.listFiles().map { document ->
        val name = document.name ?: "Unnamed"
        LocalFileEntry(
            uri = document.uri.toString(),
            relativePath = if (parentPath.isBlank()) name else "$parentPath/$name",
            name = name,
            isDirectory = document.isDirectory,
            mimeType = document.type,
            sizeBytes = document.length().takeIf { it >= 0 },
            modifiedAt = document.lastModified().takeIf { it > 0 }
        )
    }.sortedWith(compareByDescending<LocalFileEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

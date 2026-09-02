package de.tobisk.inkdav.files

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.URLConnection
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
        if (Uri.parse(uri).scheme == "file") return@withContext fileRoot(uri)
        val document = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(uri))) {
            "The selected local folder is unavailable."
        }
        LocalFolderLocation("", document.name ?: "Local files") to list(document, "")
    }

    suspend fun folder(rootUri: String, relativePath: String, name: String): Pair<LocalFolderLocation, List<LocalFileEntry>> = withContext(Dispatchers.IO) {
        if (Uri.parse(rootUri).scheme == "file") return@withContext fileFolder(rootUri, relativePath, name)
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

    private fun fileRoot(uri: String): Pair<LocalFolderLocation, List<LocalFileEntry>> {
        val root = requireNotNull(Uri.parse(uri).path).let(::File).canonicalFile
        require(root.isDirectory && root.canRead()) { "The device storage root is unavailable." }
        return LocalFolderLocation("", "Device storage") to list(root, "")
    }

    private fun fileFolder(rootUri: String, relativePath: String, name: String): Pair<LocalFolderLocation, List<LocalFileEntry>> {
        val root = requireNotNull(Uri.parse(rootUri).path).let(::File).canonicalFile
        val folder = File(root, relativePath).canonicalFile
        require(folder.path == root.path || folder.path.startsWith(root.path + File.separator)) { "The folder is outside the selected root." }
        require(folder.isDirectory && folder.canRead()) { "The local folder is unavailable." }
        return LocalFolderLocation(relativePath, name) to list(folder, relativePath)
    }

    private fun list(folder: File, parentPath: String): List<LocalFileEntry> = folder.listFiles().orEmpty().map { file ->
        val relativePath = if (parentPath.isBlank()) file.name else "$parentPath/${file.name}"
        LocalFileEntry(
            uri = Uri.fromFile(file).toString(),
            relativePath = relativePath,
            name = file.name,
            isDirectory = file.isDirectory,
            mimeType = if (file.isDirectory) null else URLConnection.guessContentTypeFromName(file.name),
            sizeBytes = file.length().takeIf { file.isFile },
            modifiedAt = file.lastModified().takeIf { it > 0 }
        )
    }.sortedWith(compareByDescending<LocalFileEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

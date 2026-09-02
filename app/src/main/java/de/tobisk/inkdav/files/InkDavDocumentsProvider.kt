package de.tobisk.inkdav.files

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import de.tobisk.inkdav.InkDavApplication
import de.tobisk.inkdav.data.CollectionKind
import de.tobisk.inkdav.data.DavCollectionEntity
import de.tobisk.inkdav.data.FileNodeEntity
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.File
import java.util.concurrent.Executors

/** Makes indexed DAV files visible to every Storage Access Framework-aware Android application. */
class InkDavDocumentsProvider : DocumentsProvider() {
    private val executor = Executors.newCachedThreadPool()
    private val container get() = (requireNotNull(context).applicationContext as InkDavApplication).container
    private val dao get() = container.database.dao()

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        val roots = runBlocking { dao.enabledAccounts().flatMap { dao.collections(it.id) }.filter { it.kind == CollectionKind.FILE_ROOT || it.kind == CollectionKind.SHARE } }
        roots.forEach { root -> cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, root.id)
            add(Root.COLUMN_DOCUMENT_ID, rootDocumentId(root.id))
            add(Root.COLUMN_TITLE, root.displayName)
            add(Root.COLUMN_SUMMARY, "InkDAV · online and offline files")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_ICON, android.R.drawable.ic_menu_agenda)
        } }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { cursor ->
            if (documentId.startsWith("root:")) {
                val collection = runBlocking { dao.collection(documentId.removePrefix("root:")) } ?: throw FileNotFoundException(documentId)
                includeRoot(cursor, collection)
            } else includeFile(cursor, requireFile(documentId))
        }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val (collectionId, parentHref) = if (parentDocumentId.startsWith("root:")) {
            val collection = runBlocking { dao.collection(parentDocumentId.removePrefix("root:")) } ?: throw FileNotFoundException(parentDocumentId)
            collection.id to collection.href
        } else {
            val parent = requireFile(parentDocumentId)
            parent.collectionId to parent.href
        }
        runBlocking { dao.files(collectionId, parentHref) }.forEach { includeFile(cursor, it) }
        return cursor
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        // The provider intentionally searches the bounded local index; it never wakes a NAS disk for type-ahead.
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val root = runBlocking { dao.collection(rootId) } ?: return cursor
        val queue = ArrayDeque<String>().apply { add(root.href) }
        var visited = 0
        while (queue.isNotEmpty() && visited < 5_000) {
            val parent = queue.removeFirst()
            runBlocking { dao.files(root.id, parent) }.forEach { file ->
                visited++
                if (file.displayName.contains(query, ignoreCase = true)) includeFile(cursor, file)
                if (file.isDirectory) queue.addLast(file.href)
            }
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val child = runCatching { requireFile(documentId) }.getOrNull() ?: return false
        return if (parentDocumentId.startsWith("root:")) child.collectionId == parentDocumentId.removePrefix("root:")
        else child.parentHref == runCatching { requireFile(parentDocumentId).href }.getOrNull()
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("The indexed DAV provider is read-only; edit a mirrored local folder for queued upload")
        val file = requireFile(documentId)
        if (file.isDirectory) throw FileNotFoundException("Cannot open a directory")
        file.localUri?.let { local ->
            if (Uri.parse(local).scheme == "file") return ParcelFileDescriptor.open(File(requireNotNull(Uri.parse(local).path)), ParcelFileDescriptor.MODE_READ_ONLY)
            return requireNotNull(context).contentResolver.openFileDescriptor(Uri.parse(local), "r", signal)
                ?: throw FileNotFoundException(file.displayName)
        }
        val collection = runBlocking { dao.collection(file.collectionId) } ?: throw FileNotFoundException(file.displayName)
        val account = runBlocking { dao.account(collection.accountId) } ?: throw FileNotFoundException(file.displayName)
        val password = container.credentials.get(account.id) ?: throw FileNotFoundException("Credentials unavailable")
        val pipe = ParcelFileDescriptor.createPipe()
        executor.execute {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                try { runBlocking { container.davClient.get(account, password, file.href) }.use { it.copyTo(output) } }
                finally { password.fill('\u0000') }
            }
        }
        return pipe[0]
    }

    private fun requireFile(documentId: String): FileNodeEntity {
        if (!documentId.startsWith("file:")) throw FileNotFoundException(documentId)
        return runBlocking { dao.file(documentId.removePrefix("file:")) } ?: throw FileNotFoundException(documentId)
    }

    private fun includeRoot(cursor: MatrixCursor, collection: DavCollectionEntity) = cursor.newRow().apply {
        add(Document.COLUMN_DOCUMENT_ID, rootDocumentId(collection.id))
        add(Document.COLUMN_DISPLAY_NAME, collection.displayName)
        add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
        add(Document.COLUMN_FLAGS, 0)
    }

    private fun includeFile(cursor: MatrixCursor, file: FileNodeEntity) = cursor.newRow().apply {
        add(Document.COLUMN_DOCUMENT_ID, "file:${file.id}")
        add(Document.COLUMN_DISPLAY_NAME, file.displayName)
        add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else file.mimeType ?: "application/octet-stream")
        add(Document.COLUMN_SIZE, file.sizeBytes)
        add(Document.COLUMN_LAST_MODIFIED, file.modifiedAt)
        add(Document.COLUMN_FLAGS, 0)
    }

    private fun rootDocumentId(id: String) = "root:$id"

    companion object {
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }
}

package de.tobisk.inkvault.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

data class VaultEntry(
    val path: String,
    val hash: String,
    val size: Long,
    val modified: Long,
    val generation: Long,
    val dirty: Boolean,
    val deleted: Boolean,
    val conflict: String
) {
    fun json() = JSONObject().put("path", path).put("sha256", hash).put("size", size)
        .put("mtime", modified).put("generation", generation).put("dirty", dirty).put("deleted", deleted)
}

class VaultStore(context: Context) : SQLiteOpenHelper(context, "inkvault.db", null, 1) {
    val blobs = BlobStore(java.io.File(context.filesDir, "vault-blobs"))
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE files(path TEXT PRIMARY KEY, hash TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, generation INTEGER NOT NULL, dirty INTEGER NOT NULL, deleted INTEGER NOT NULL, conflict TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE conflicts(path TEXT PRIMARY KEY, localHash TEXT NOT NULL, remoteHash TEXT, reason TEXT NOT NULL)")
        db.execSQL("CREATE INDEX pending_files ON files(dirty, deleted)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported database migration")

    @Synchronized fun entries(): List<VaultEntry> = readableDatabase.rawQuery("SELECT * FROM files ORDER BY path", null).use { c ->
        buildList { while (c.moveToNext()) add(c.entry()) }
    }

    @Synchronized fun get(path: String): VaultEntry? = readableDatabase.rawQuery("SELECT * FROM files WHERE path=?", arrayOf(path)).use { if (it.moveToFirst()) it.entry() else null }

    @Synchronized fun meta(key: String): String? = readableDatabase.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized fun setMeta(key: String, value: String?) {
        if (value == null) {
            writableDatabase.delete("metadata", "key=?", arrayOf(key))
        } else {
            writableDatabase.insertWithOnConflict(
                "metadata",
                null,
                ContentValues().apply {
                    put("key", key)
                    put("value", value)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    @Synchronized fun save(path: String, input: InputStream): VaultEntry {
        VaultPath.requireValid(path)
        val hash = blobs.put(input)
        return transaction {
            val old = get(path)
            require(path.startsWith(".inkvault/") || pairRoot(path) == null || old?.hash == hash) { "Edit this PDF through its InkNote" }
            if (old?.hash == hash && !old.deleted) return@transaction old
            val entry = VaultEntry(path, hash, blobs.file(hash).length(), System.currentTimeMillis(), nextGeneration(), true, false, old?.conflict ?: "")
            put(entry)
            entry
        }
    }
    fun saveText(path: String, text: String) = save(path, text.byteInputStream())

    @Synchronized fun saveEditedText(path: String, text: String, openedHash: String): VaultEntry = transaction {
        val current = get(path)
        val saved = saveText(path, text)
        if (current != null && (current.hash != openedHash || current.deleted) && current.hash != saved.hash) {
            val reason = "Remote changed while this editor was open"
            writableDatabase.insertWithOnConflict(
                "conflicts",
                null,
                ContentValues().apply {
                    put("path", path)
                    put("localHash", saved.hash)
                    put("remoteHash", if (current.deleted) null else current.hash)
                    put("reason", reason)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            put(saved.copy(conflict = reason))
        }
        requireNotNull(get(path))
    }

    @Synchronized fun delete(path: String) = transaction {
        require(path.startsWith(".inkvault/") || pairRoot(path) == null) { "InkNote source and PDF must be deleted together" }
        get(path)?.let { put(it.copy(deleted = true, dirty = true, generation = nextGeneration())) }
    }

    @Synchronized fun move(path: String, destination: String) = transaction {
        require(pairRoot(path) == null) { "InkNote renaming is not available yet" }
        VaultPath.requireValid(destination)
        require(get(destination)?.deleted != false) { "Destination already exists" }
        val entry = requireNotNull(get(path))
        require(entry.conflict.isEmpty()) { "Resolve the conflict before renaming" }
        put(entry.copy(path = destination, generation = nextGeneration(), dirty = true))
        delete(path)
    }

    /** Stage only authoritative source changes; the server publishes PDF moves atomically. */
    @Synchronized fun moveDocument(path: String, destination: String): String = transaction {
        val root = pairRoot(path) ?: return@transaction move(path, destination).let { destination }
        val manifestPath = "$root/manifest.json"
        val manifestEntry = requireNotNull(get(manifestPath))
        val manifest = JSONObject(blobs.file(manifestEntry.hash).readText())
        val oldPdf = manifest.getString("pdfPath")
        VaultPath.requireValid(destination)
        require(destination.endsWith(".pdf", true)) { "Handwritten documents use a PDF filename" }
        require(destination != oldPdf && get(destination)?.deleted != false && pairRoot(destination) == null) { "Destination already exists" }
        require(entries().none { (it.path.startsWith("$root/") || it.path == oldPdf) && it.conflict.isNotEmpty() }) { "Resolve the document conflict first" }
        if (manifest.has("basePdfHash") && !manifest.has("basePdfPath")) manifest.put("basePdfPath", oldPdf)
        manifest.put("pdfPath", destination).put("title", VaultPath.name(destination).removeSuffix(".pdf"))
            .put("modified", System.currentTimeMillis()).put("sourceRevision", manifest.getLong("sourceRevision") + 1)
        saveText(manifestPath, manifest.toString())
        get(oldPdf)?.let { old ->
            put(old.copy(path = destination, generation = nextGeneration(), dirty = false))
            // A locally imported annotation base must still upload before its first source publication.
            val pendingBase = manifest.has("basePdfHash") && manifest.isNull("basePdfRevision") && manifest.optString("basePdfPath") == oldPdf && old.dirty && !old.deleted
            if (!pendingBase) put(old.copy(deleted = true, dirty = false, generation = nextGeneration()))
        }
        manifestPath
    }

    @Synchronized fun deleteDocument(path: String) = transaction {
        val root = pairRoot(path)
        if (root == null) {
            delete(path)
            return@transaction
        }
        val manifest = JSONObject(blobs.file(requireNotNull(get("$root/manifest.json")).hash).readText())
        val pdf = manifest.getString("pdfPath")
        val members = entries().filter { it.path.startsWith("$root/") || it.path == pdf }
        require(members.none { it.conflict.isNotEmpty() }) { "Resolve the document conflict first" }
        members.forEach { put(it.copy(deleted = true, dirty = it.path != pdf, generation = nextGeneration())) }
    }

    @Synchronized fun changeFolder(source: String, destination: String?) = transaction {
        VaultPath.requireValid(source)
        if (destination != null) {
            VaultPath.requireValid(destination)
            require(destination != source && !destination.startsWith("$source/")) { "Choose a different parent folder" }
            require(entries().none { !it.deleted && (it.path == destination || it.path.startsWith("$destination/")) }) { "Destination already exists" }
        }
        val manifests = entries().filter { !it.deleted && it.path.startsWith(".inkvault/notes/") && it.path.endsWith("/manifest.json") }
        val notes = manifests.map { it.path to JSONObject(blobs.file(it.hash).readText()).getString("pdfPath") }.filter { it.second.startsWith("$source/") }
        val paired = notes.map { it.second }.toSet()
        val ordinary = entries().filter { !it.deleted && it.path.startsWith("$source/") && it.path !in paired }
        notes.forEach { (manifest, pdf) -> if (destination == null) deleteDocument(manifest) else moveDocument(manifest, destination + pdf.removePrefix(source)) }
        ordinary.forEach { if (destination == null) delete(it.path) else move(it.path, destination + it.path.removePrefix(source)) }
    }

    @Synchronized fun savePackage(files: Map<String, ByteArray>) = transaction {
        files.forEach { (path, bytes) -> save(path, bytes.inputStream()) }
    }

    @Synchronized fun journal(response: JSONObject, snapshot: List<VaultEntry>, acknowledged: Set<String> = snapshot.map { it.path }.toSet()) = transaction {
        setMeta("journal", JSONObject().put("response", response).put("snapshot", JSONArray(snapshot.map { it.json() })).put("acknowledged", JSONArray(acknowledged.toList())).toString())
    }

    /** Apply an entire response only after every blob is verified. Newer local generations win locally and remain queued. */
    @Synchronized fun applyJournal() = transaction {
        val journal = JSONObject(requireNotNull(meta("journal")))
        val response = journal.getJSONObject("response")
        val snapshot = journal.getJSONArray("snapshot")
        val generations = (0 until snapshot.length()).associate { snapshot.getJSONObject(it).let { row -> row.getString("path") to row.getLong("generation") } }
        val acknowledgedArray = journal.optJSONArray("acknowledged") ?: snapshot
        val acknowledged = (0 until acknowledgedArray.length()).map { if (acknowledgedArray.optJSONObject(it) != null) acknowledgedArray.getJSONObject(it).getString("path") else acknowledgedArray.getString(it) }.toSet()
        val conflicts = response.getJSONArray("conflicts")
        val reasons = (0 until conflicts.length()).associate { conflicts.getJSONObject(it).let { row -> row.getString("path") to row.getString("reason") } }
        val files = response.getJSONArray("files")
        val pairByPath = mutableMapOf<String, String>()
        val pairPaths = mutableMapOf<String, MutableSet<String>>()
        val candidatePaths = entries().map { it.path } + (0 until files.length()).map { files.getJSONObject(it).getString("path") }
        for (path in candidatePaths.filter { it.startsWith(".inkvault/notes/") }) {
            val root = path.split('/').take(3).joinToString("/")
            pairByPath[path] = root
            pairPaths.getOrPut(root) { mutableSetOf() }.add(path)
            if (path.endsWith("/manifest.json")) {
                val remoteFile = (0 until files.length()).map { files.getJSONObject(it) }.firstOrNull { it.getString("path") == path && it.getString("op") == "upsert" }
                for (hash in listOfNotNull(get(path)?.hash, remoteFile?.getString("sha256"))) {
                    val pdf = JSONObject(blobs.file(hash).readText()).getString("pdfPath")
                    pairByPath[pdf] = root
                    pairPaths.getValue(root).add(pdf)
                }
            }
        }
        val blockedPairs = mutableSetOf<String>()
        for (i in 0 until files.length()) {
            val remote = files.getJSONObject(i)
            val path = remote.getString("path")
            val old = get(path)
            val changedLocally = old?.generation != generations[path] || (old?.dirty == true && path !in acknowledged)
            if (reasons.containsKey(path) || changedLocally) pairByPath[path]?.let { blockedPairs.add(it) }
        }
        for (i in 0 until files.length()) {
            val remote = files.getJSONObject(i)
            val path = VaultPath.requireValid(remote.getString("path"))
            val old = get(path)
            val deleted = remote.getString("op") == "delete"
            val hash = if (deleted) old?.hash ?: "" else remote.getString("sha256")
            if (!deleted) check(blobs.file(hash).isFile)
            val editedDuringSync = old?.generation != generations[path] || (old?.dirty == true && path !in acknowledged)
            val reason = reasons[path] ?: pairByPath[path]?.takeIf { it in blockedPairs }?.let { "InkNote pair changed: $it" }
            if (reason != null || editedDuringSync) {
                // Preserve both sides durably. Concurrent clean pulls can be retried without losing the user's edit.
                val why = reason ?: "Edited locally while remote content changed"
                writableDatabase.insertWithOnConflict(
                    "conflicts",
                    null,
                    ContentValues().apply {
                        put("path", path)
                        put("localHash", old?.hash ?: "")
                        put("remoteHash", if (deleted) null else hash)
                        put("reason", why)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                if (old != null) put(old.copy(conflict = why)) else put(VaultEntry(path, hash, if (deleted) 0 else blobs.file(hash).length(), System.currentTimeMillis(), nextGeneration(), false, deleted, why))
            } else {
                put(VaultEntry(path, hash, if (deleted) 0 else blobs.file(hash).length(), remote.optLong("mtime", old?.modified ?: System.currentTimeMillis()), nextGeneration(), false, deleted, ""))
            }
        }
        for (root in blockedPairs) for (path in pairPaths.getValue(root)) get(path)?.let { put(it.copy(conflict = "InkNote pair changed: $root")) }
        if (blockedPairs.isNotEmpty() || reasons.isNotEmpty()) setMeta("conflictHead", response.optString("serverHead"))
        // The server can report a conflict without returning different bytes for that path.
        for ((path, reason) in reasons) get(path)?.let { put(it.copy(conflict = reason)) }
        for (i in 0 until snapshot.length()) {
            val row = snapshot.getJSONObject(i)
            val current = get(row.getString("path")) ?: continue
            if (current.path in acknowledged && current.generation == row.getLong("generation") && current.conflict.isEmpty() && response.getString("status") == "ok") put(current.copy(dirty = false))
        }
        // A conflict response must never advance the merge base (same semantics as the plugin).
        if (response.getString("status") == "ok") setMeta("head", response.optString("serverHead").takeUnless { it == "null" || it.isEmpty() })
        setMeta("initialSyncDone", "true")
        setMeta("journal", null)
    }

    @Synchronized fun pairRoot(path: String): String? {
        if (path.startsWith(".inkvault/notes/")) return path.split('/').take(3).joinToString("/")
        return entries().firstOrNull { entry ->
            entry.path.startsWith(".inkvault/notes/") &&
                entry.path.endsWith("/manifest.json") &&
                listOfNotNull(entry.hash.takeIf { it.isNotEmpty() }, conflictRemote(entry.path)).any { hash ->
                    JSONObject(blobs.file(hash).readText()).optString("pdfPath") == path
                }
        }?.path?.substringBeforeLast('/')
    }

    @Synchronized fun acceptRemotePair(root: String) = transaction {
        val members = entries().filter { pairRoot(it.path) == root }
        require(members.isNotEmpty() && members.any { it.conflict.isNotEmpty() })
        for (entry in members) {
            val hash = conflictRemote(entry.path)
            put(entry.copy(hash = hash ?: entry.hash, size = hash?.let { blobs.file(it).length() } ?: 0, deleted = hash == null, generation = nextGeneration(), dirty = false, conflict = ""))
            writableDatabase.delete("conflicts", "path=?", arrayOf(entry.path))
        }
    }

    @Synchronized fun conflictRemote(path: String): String? = readableDatabase.rawQuery("SELECT remoteHash FROM conflicts WHERE path=?", arrayOf(path)).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    @Synchronized fun clearConflict(path: String) = transaction {
        get(path)?.let { put(it.copy(conflict = "")) }
        writableDatabase.delete("conflicts", "path=?", arrayOf(path))
    }
    private fun nextGeneration(): Long = (meta("generation")?.toLong() ?: 0).plus(1).also { setMeta("generation", it.toString()) }
    private fun put(e: VaultEntry) {
        writableDatabase.insertWithOnConflict(
            "files",
            null,
            ContentValues().apply {
                put("path", e.path)
                put("hash", e.hash)
                put("size", e.size)
                put("modified", e.modified)
                put("generation", e.generation)
                put("dirty", if (e.dirty) 1 else 0)
                put("deleted", if (e.deleted) 1 else 0)
                put("conflict", e.conflict)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }
    private fun Cursor.entry() = VaultEntry(getString(0), getString(1), getLong(2), getLong(3), getLong(4), getInt(5) != 0, getInt(6) != 0, getString(7))
}

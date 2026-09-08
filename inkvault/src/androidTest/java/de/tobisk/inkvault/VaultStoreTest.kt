package de.tobisk.inkvault

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import de.tobisk.inkvault.data.VaultStore
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VaultStoreTest {
    private fun isolated(): Pair<VaultStore, File> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        }
        return VaultStore(context) to root
    }

    @Test fun folderMovesKeepPackagesTogetherAndCollisionRollsBack() {
        val (store, root) = isolated()
        try {
            val note = de.tobisk.inkvault.ink.InkDocument.create(store, "Folder/Note.pdf")
            store.saveText("Folder/Text.md", "keep this")
            store.changeFolder("Folder", "Moved")
            assertEquals("Moved/Note.pdf", de.tobisk.inkvault.ink.InkDocument(store, note.manifestPath).manifest.getString("pdfPath"))
            assertTrue(store.get("Folder/Text.md")!!.deleted)
            assertEquals("keep this", store.blobs.file(store.get("Moved/Text.md")!!.hash).readText())
            store.saveText("Occupied/file.md", "existing")
            assertThrows(IllegalArgumentException::class.java) { store.changeFolder("Moved", "Occupied") }
            assertFalse(store.get("Moved/Text.md")!!.deleted)
            store.changeFolder("Moved", null)
            assertTrue(store.get(note.manifestPath)!!.deleted)
            assertTrue(store.get("Moved/Text.md")!!.deleted)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test fun responseJournalSurvivesRestartAndDoesNotClobberNewerLocalEdit() {
        val (store, root) = isolated()
        try {
            store.saveText("note.md", "first")
            val snapshot = store.entries()
            val remote = store.blobs.put("server".byteInputStream())
            store.journal(
                JSONObject().put("status", "ok").put("serverHead", "head-1").put("conflicts", JSONArray())
                    .put("files", JSONArray().put(JSONObject().put("path", "note.md").put("op", "upsert").put("sha256", remote))),
                snapshot
            )
            store.saveText("note.md", "newer local")
            store.close()
            val reopened = VaultStore(object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
                override fun getFilesDir() = root
                override fun getDatabasePath(name: String) = File(root, name)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
            })
            reopened.use {
                assertNotNull(it.meta("journal"))
                it.applyJournal()
                val current = it.get("note.md")!!
                assertEquals("newer local", it.blobs.file(current.hash).readText())
                assertTrue(current.dirty)
                assertTrue(current.conflict.isNotEmpty())
                assertEquals(remote, it.conflictRemote("note.md"))
                assertNull(it.meta("journal"))
            }
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test fun incompleteDownloadCannotAdvanceHeadOrClearOutbox() {
        val (store, root) = isolated()
        store.use {
            try {
                it.saveText("note.md", "local")
                it.journal(
                    JSONObject().put("status", "ok").put("serverHead", "new").put("conflicts", JSONArray()).put(
                        "files",
                        JSONArray()
                            .put(JSONObject().put("path", "remote.md").put("op", "upsert").put("sha256", "a".repeat(64)))
                    ),
                    it.entries()
                )
                assertThrows(IllegalStateException::class.java) { it.applyJournal() }
                assertNull(it.meta("head"))
                assertTrue(it.get("note.md")!!.dirty)
                assertNotNull(it.meta("journal"))
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test fun pairJournalKeepsEntireLocalPackageWhenOnePageChangesDuringSync() {
        val (store, directory) = isolated()
        store.use {
            try {
                val root = ".inkvault/notes/11111111-1111-4111-8111-111111111111"
                val manifest = "$root/manifest.json"
                val page = "$root/pages/page.cbor"
                it.saveText("Note.pdf", "old PDF")
                it.saveText(manifest, JSONObject().put("pdfPath", "Note.pdf").put("sourceRevision", 1).toString())
                it.saveText(page, "old page")
                val snapshot = it.entries()
                val remoteManifest = it.blobs.put(JSONObject().put("pdfPath", "Note.pdf").put("sourceRevision", 2).toString().byteInputStream())
                val remotePage = it.blobs.put("remote page".byteInputStream())
                val remotePdf = it.blobs.put("remote PDF".byteInputStream())
                val files = JSONArray()
                for ((path, hash) in listOf(manifest to remoteManifest, page to remotePage, "Note.pdf" to remotePdf)) files.put(JSONObject().put("op", "upsert").put("path", path).put("sha256", hash))
                it.journal(JSONObject().put("status", "ok").put("serverHead", "remote-head").put("files", files).put("conflicts", JSONArray()), snapshot)
                it.saveText(page, "new local page")
                it.applyJournal()
                assertEquals(1, JSONObject(it.blobs.file(it.get(manifest)!!.hash).readText()).getInt("sourceRevision"))
                assertEquals("new local page", it.blobs.file(it.get(page)!!.hash).readText())
                assertEquals("old PDF", it.blobs.file(it.get("Note.pdf")!!.hash).readText())
                assertTrue(it.entries().all { e -> e.conflict.isNotEmpty() })
                assertEquals("remote-head", it.meta("conflictHead"))
                it.acceptRemotePair(root)
                assertEquals(remoteManifest, it.get(manifest)!!.hash)
                assertEquals(remotePage, it.get(page)!!.hash)
                assertEquals(remotePdf, it.get("Note.pdf")!!.hash)
                assertTrue(it.entries().all { e -> e.conflict.isEmpty() && !e.dirty })
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun ordinarySyncDoesNotAcknowledgeUnsentInkNoteChanges() {
        val (store, directory) = isolated()
        store.use {
            try {
                val hidden = ".inkvault/notes/11111111-1111-4111-8111-111111111111/manifest.json"
                it.saveText(hidden, JSONObject().put("pdfPath", "Ink.pdf").toString())
                it.saveText("Note.md", "local text")
                it.journal(JSONObject().put("status", "ok").put("serverHead", "head").put("files", JSONArray()).put("conflicts", JSONArray()), it.entries(), setOf("Note.md"))
                it.applyJournal()
                assertTrue(it.get(hidden)!!.dirty)
                assertFalse(it.get("Note.md")!!.dirty)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun offlinePdfAnnotationWaitsForVerifiedServerBase() {
        val (store, directory) = isolated()
        store.use {
            try {
                it.setMeta("head", "older-vault-head")
                val pdf = it.saveText("Imported.pdf", "imported PDF bytes")
                val note = de.tobisk.inkvault.ink.InkDocument.create(it, "Imported.pdf", baseHash = pdf.hash, dimensions = listOf(210000L to 297000L))
                assertTrue(note.manifest.isNull("basePdfRevision"))
                assertEquals(note.root, it.pairRoot("Imported.pdf"))
                assertThrows(IllegalArgumentException::class.java) { it.saveText("Imported.pdf", "independent replacement") }
                assertThrows(IllegalArgumentException::class.java) { it.delete("Imported.pdf") }
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}

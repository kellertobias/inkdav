package de.tobisk.inkvault.ink

import de.tobisk.inkvault.data.VaultPath
import de.tobisk.inkvault.data.VaultStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class InkDocument(private val store: VaultStore, val manifestPath: String) {
    var manifest = JSONObject(store.blobs.file(requireNotNull(store.get(manifestPath)).hash).readText())
        private set
    init {
        require(manifest.getInt("schemaVersion") == 1)
        require(manifest.getJSONArray("pages").length() in 1..500)
    }
    val annotation get() = manifest.has("basePdfHash")
    val count get() = manifest.getJSONArray("pages").length()
    val root get() = manifestPath.substringBeforeLast('/')
    val title get() = manifest.getString("title")
    fun pageInfo(index: Int) = manifest.getJSONArray("pages").getJSONObject(index)
    fun pagePath(index: Int) = "$root/pages/${pageInfo(index).getString("id")}.cbor"
    fun page(index: Int): InkPage {
        val entry = store.get(pagePath(index))
        return if (entry != null) InkPage.decode(store.blobs.file(entry.hash).readBytes()) else InkPage(pageInfo(index).getLong("width"), pageInfo(index).getLong("height"))
    }
    fun savePage(index: Int, page: InkPage) {
        saveEncodedPage(index, page.bytes())
    }
    fun saveEncodedPage(index: Int, bytes: ByteArray) {
        pageInfo(index).put("sha256", VaultPath.sha256(bytes))
        persist(mapOf(pagePath(index) to bytes))
    }
    fun addPage(after: Int, landscape: Boolean, duplicate: Boolean = false): Int {
        require(!annotation) { "PDF annotation pages are fixed" }
        require(count < 500)
        val page = if (duplicate) {
            page(after)
        } else if (landscape) {
            InkPage(297000, 210000)
        } else {
            InkPage()
        }
        val info = info(page)
        if (duplicate) info.put("template", pageInfo(after).opt("template") ?: JSONObject.NULL)
        val list = (0 until count).map { pageInfo(it) }.toMutableList().apply { add(after + 1, info) }
        manifest.put("pages", JSONArray(list))
        persist(mapOf("$root/pages/${info.getString("id")}.cbor" to page.bytes()))
        return after + 1
    }
    fun deletePage(index: Int): String {
        require(!annotation && count > 1)
        val before = manifest.toString()
        manifest.getJSONArray("pages").remove(index)
        // Retain removed source blobs as tombstones for undo and other clients.
        persist(emptyMap())
        return before
    }
    fun restoreManifest(value: String) {
        require(!annotation)
        val revision = manifest.getLong("sourceRevision")
        manifest = JSONObject(value).put("sourceRevision", revision)
        persist(emptyMap())
    }
    fun reorder(index: Int, to: Int) {
        require(!annotation && to in 0 until count)
        val list = (0 until count).map { pageInfo(it) }.toMutableList()
        list.add(to, list.removeAt(index))
        manifest.put("pages", JSONArray(list))
        persist(emptyMap())
    }
    fun asset(path: String): String {
        val entry = requireNotNull(store.get(path))
        val extension = path.substringAfterLast('.').lowercase()
        require(extension in setOf("png", "jpg", "jpeg", "svg", "pdf"))
        val destination = "$root/assets/${entry.hash}.$extension"
        store.save(destination, store.blobs.file(entry.hash).inputStream())
        return destination
    }
    fun template(index: Int, path: String, pdfPage: Int) {
        pageInfo(index).put("template", JSONObject().put("asset", asset(path)).put("page", pdfPage))
        persist(emptyMap())
    }
    private fun persist(files: Map<String, ByteArray>) {
        manifest.put("modified", System.currentTimeMillis())
        manifest.put("sourceRevision", manifest.getLong("sourceRevision") + 1)
        val render = (0 until count).joinToString("|") { pageInfo(it).toString() }
        manifest.put("renderRevision", VaultPath.sha256(render.toByteArray()))
        store.savePackage(files + (manifestPath to manifest.toString().toByteArray()))
    }
    companion object {
        private fun info(page: InkPage) = JSONObject().put("id", UUID.randomUUID().toString()).put("width", page.width).put("height", page.height)
            .put("orientation", if (page.width > page.height) "landscape" else "portrait").put("template", JSONObject.NULL).put("sha256", VaultPath.sha256(page.bytes()))
        fun create(store: VaultStore, pdfPath: String, landscape: Boolean = false, baseHash: String? = null, dimensions: List<Pair<Long, Long>>? = null): InkDocument {
            VaultPath.requireValid(pdfPath)
            require(pdfPath.endsWith(".pdf", true))
            val id = UUID.randomUUID().toString()
            val root = ".inkvault/notes/$id"
            val pages = dimensions?.map { InkPage(it.first, it.second) } ?: listOf(if (landscape) InkPage(297000, 210000) else InkPage())
            require(pages.size in 1..500)
            val infos = pages.map { info(it) }
            val manifest = JSONObject().put("schemaVersion", 1).put("documentId", id).put("pdfPath", pdfPath).put("title", VaultPath.name(pdfPath).removeSuffix(".pdf"))
                .put("created", System.currentTimeMillis()).put("modified", System.currentTimeMillis()).put("sourceRevision", 1).put("renderRevision", "")
                .put("pages", JSONArray(infos))
            if (baseHash != null) {
                manifest.put("basePdfPath", pdfPath).put("basePdfHash", baseHash).put("basePdfRevision", store.meta("head").takeUnless { store.get(pdfPath)?.dirty == true } ?: JSONObject.NULL)
            }
            val files = pages.mapIndexed { index, page -> "$root/pages/${infos[index].getString("id")}.cbor" to page.bytes() }.toMap()
            store.savePackage(files + ("$root/manifest.json" to manifest.toString().toByteArray()))
            return InkDocument(store, "$root/manifest.json")
        }
    }
}

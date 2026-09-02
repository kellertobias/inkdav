package de.tobisk.inkdav.files

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import de.tobisk.inkdav.data.*
import de.tobisk.inkdav.dav.DavClient
import de.tobisk.inkdav.dav.DavResource
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

class MirrorSyncEngine(private val context: Context, private val dao: InkDavDao, private val dav: DavClient) {
    private data class Remote(val path: String, val value: DavResource)
    private data class Local(val path: String, val document: DocumentFile, val hash: String?)

    suspend fun synchronize(account: DavAccountEntity, password: CharArray, collection: DavCollectionEntity, binding: MirrorBindingEntity) {
        val root = DocumentFile.fromTreeUri(context, android.net.Uri.parse(binding.localTreeUri))
            ?: return dao.upsertMirror(binding.copy(state = MirrorState.ERROR, lastError = "Local folder permission is unavailable"))
        try {
            val remote = linkedMapOf<String, Remote>()
            scanRemote(account, password, binding.remoteRootHref, "", remote, intArrayOf(binding.itemLimit))
            val local = linkedMapOf<String, Local>()
            scanLocal(root, "", local, intArrayOf(binding.itemLimit))
            val existing = dao.mirrorEntries(binding.id).associateByTo(linkedMapOf()) { it.relativePath }
            if (remote.isEmpty() &&
                existing.isNotEmpty()
            ) {
                error("Remote mirror scan unexpectedly returned no entries; deletion reconciliation aborted")
            }
            propagateRenames(account, password, binding, root, remote, local, existing)
            val updated = mutableListOf<MirrorEntryEntity>()
            (remote.keys + local.keys + existing.keys).sortedBy { it.count { ch -> ch == '/' } }.forEach { path ->
                val r = remote[path]
                val l = local[path]
                val old = existing[path]
                if (r?.value?.isCollection == true || l?.document?.isDirectory == true) {
                    if (r == null &&
                        l != null &&
                        old == null
                    ) {
                        dav.makeCollection(account, password, remoteHref(binding.remoteRootHref, path))
                        return@forEach
                    }
                    if (l == null && r != null) ensureDirectory(root, path)
                    if (r != null || l != null) updated += entry(binding.id, path, r, local[path], old, MirrorEntryStatus.CLEAN, null)
                    return@forEach
                }
                var remoteFingerprint = r?.value?.etag ?: r?.let { "${it.value.size}:${it.value.modifiedAt}" }
                var effectiveRemote = r
                val localFingerprint = l?.hash
                val contentEqual = if (r != null &&
                    l != null &&
                    (old == null || (remoteFingerprint != old.baselineRemoteEtag && localFingerprint != old.baselineLocalHash))
                ) {
                    remoteHash(account, password, r.value.href) ==
                        localFingerprint
                } else {
                    false
                }
                when (
                    MirrorReconciler.decide(
                        binding.state == MirrorState.BASELINED,
                        old?.baselineRemoteEtag,
                        old?.baselineLocalHash,
                        remoteFingerprint,
                        localFingerprint,
                        contentEqual
                    )
                ) {
                    MirrorDecision.DOWNLOAD -> if (r != null) download(account, password, root, path, r.value.href)
                    MirrorDecision.UPLOAD -> if (l != null) {
                        val href = remoteHref(binding.remoteRootHref, path)
                        val uploadedEtag = dav.putStream(
                            account,
                            password,
                            href,
                            {
                                requireNotNull(context.contentResolver.openInputStream(l.document.uri))
                            },
                            l.document.length().takeIf {
                                it >=
                                    0
                            },
                            l.document.type ?: "application/octet-stream",
                            old?.baselineRemoteEtag,
                            createOnly = old == null
                        )
                        remoteFingerprint = uploadedEtag ?: remoteFingerprint
                        effectiveRemote =
                            Remote(
                                path,
                                DavResource(
                                    href = href,
                                    displayName = l.document.name.orEmpty(),
                                    etag = uploadedEtag,
                                    contentType = l.document.type,
                                    size = l.document.length()
                                )
                            )
                    }
                    MirrorDecision.DELETE_LOCAL -> l?.document?.delete()
                    MirrorDecision.DELETE_REMOTE -> if (r != null) dav.delete(account, password, r.value.href, old?.baselineRemoteEtag)
                    MirrorDecision.REMOVE_ENTRY -> {
                        dao.deleteMirrorEntry(binding.id, path)
                        return@forEach
                    }
                    MirrorDecision.CONFLICT -> {
                        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
                        if (r != null) download(account, password, root, conflictPath(path, "remote-$stamp"), r.value.href)
                        if (l !=
                            null
                        ) {
                            dav.putStream(
                                account,
                                password,
                                remoteHref(binding.remoteRootHref, conflictPath(path, "local-$stamp")),
                                {
                                    requireNotNull(context.contentResolver.openInputStream(l.document.uri))
                                },
                                l.document.length().takeIf {
                                    it >=
                                        0
                                },
                                l.document.type ?: "application/octet-stream",
                                null,
                                createOnly = true
                            )
                        }
                        updated += entry(binding.id, path, r, l, old, MirrorEntryStatus.CONFLICT, "Both local and remote content changed")
                        return@forEach
                    }
                    MirrorDecision.BASELINE_EQUAL, MirrorDecision.NO_CHANGE -> Unit
                }
                val refreshedLocal = find(root, path)?.let { Local(path, it, if (it.isFile) hash(it) else null) }
                updated += entry(binding.id, path, effectiveRemote, refreshedLocal, old, MirrorEntryStatus.CLEAN, null).copy(
                    baselineRemoteEtag = remoteFingerprint,
                    baselineLocalHash = refreshedLocal?.hash ?: localFingerprint
                )
            }
            dao.upsertMirrorEntries(updated)
            dao.upsertMirror(binding.copy(state = MirrorState.BASELINED, lastCompleteSyncAt = System.currentTimeMillis(), lastError = null))
        } catch (error: Exception) {
            dao.upsertMirror(
                binding.copy(
                    state = if (binding.state ==
                        MirrorState.BASELINED
                    ) {
                        MirrorState.BASELINED
                    } else {
                        MirrorState.ERROR
                    },
                    lastError = error.message
                )
            )
            throw error
        }
    }

    private suspend fun propagateRenames(
        account: DavAccountEntity,
        password: CharArray,
        binding: MirrorBindingEntity,
        root: DocumentFile,
        remote: MutableMap<String, Remote>,
        local: MutableMap<String, Local>,
        existing: MutableMap<String, MirrorEntryEntity>
    ) {
        val newLocalFiles = local.values.filter { it.document.isFile && remote[it.path] == null && existing[it.path] == null }
        newLocalFiles.forEach { added ->
            val candidates = existing.values.filter { old ->
                !old.isDirectory &&
                    local[old.relativePath] == null &&
                    remote[old.relativePath]?.let { current ->
                        fingerprint(current.value) == old.baselineRemoteEtag
                    } == true &&
                    added.hash == old.baselineLocalHash
            }
            if (candidates.size != 1) return@forEach
            val old = candidates.single()
            val oldRemote = remote.remove(old.relativePath) ?: return@forEach
            val destination = remoteHref(binding.remoteRootHref, added.path)
            dav.move(account, password, oldRemote.value.href, destination, old.baselineRemoteEtag)
            remote[added.path] = Remote(
                added.path,
                oldRemote.value.copy(href = destination, displayName = added.document.name.orEmpty())
            )
            existing.remove(old.relativePath)
            existing[added.path] = old.copy(
                relativePath = added.path,
                remoteHref = destination,
                localDocumentUri = added.document.uri.toString()
            )
        }

        val newRemoteFiles = remote.values.filter { !it.value.isCollection && local[it.path] == null && existing[it.path] == null }
        newRemoteFiles.forEach { added ->
            val candidates = existing.values.filter { old ->
                !old.isDirectory &&
                    remote[old.relativePath] == null &&
                    local[old.relativePath]?.hash == old.baselineLocalHash &&
                    fingerprint(added.value) == old.baselineRemoteEtag
            }
            if (candidates.size != 1) return@forEach
            val old = candidates.single()
            val oldLocal = local.remove(old.relativePath) ?: return@forEach
            val renamed = if (old.relativePath.substringBeforeLast('/', "") == added.path.substringBeforeLast('/', "")) {
                oldLocal.document.renameTo(added.path.substringAfterLast('/'))
            } else {
                download(account, password, root, added.path, added.value.href)
                oldLocal.document.delete()
                true
            }
            if (!renamed) {
                if (find(root, added.path) == null) return@forEach
            }
            val document = find(root, added.path) ?: return@forEach
            val movedLocal = Local(added.path, document, hash(document))
            local[added.path] = movedLocal
            existing.remove(old.relativePath)
            existing[added.path] = old.copy(
                relativePath = added.path,
                remoteHref = added.value.href,
                localDocumentUri = document.uri.toString()
            )
        }
    }

    private fun fingerprint(resource: DavResource): String? = resource.etag ?: "${resource.size}:${resource.modifiedAt}"

    private suspend fun scanRemote(
        account: DavAccountEntity,
        password: CharArray,
        href: String,
        prefix: String,
        out: MutableMap<String, Remote>,
        budget: IntArray
    ) {
        dav.list(account, password, href).forEach { resource ->
            if (--budget[0] < 0) error("Mirror exceeds its ${out.size} item safety limit")
            val name = resource.displayName.ifBlank { resource.href.trimEnd('/').substringAfterLast('/') }
            require(name != "." && name != ".." && '/' !in name) { "Unsafe remote path component" }
            val path = if (prefix.isBlank()) name else "$prefix/$name"
            require(out.put(path, Remote(path, resource)) == null) { "Ambiguous remote path $path" }
            if (resource.isCollection) scanRemote(account, password, resource.href, path, out, budget)
        }
    }

    private fun scanLocal(folder: DocumentFile, prefix: String, out: MutableMap<String, Local>, budget: IntArray) {
        folder.listFiles().forEach { document ->
            if (--budget[0] < 0) error("Mirror exceeds its item safety limit")
            val name = requireNotNull(document.name)
            if (name.startsWith(".inkdav-part-")) return@forEach
            val path = if (prefix.isBlank()) name else "$prefix/$name"
            require(out.keys.none { it.equals(path, true) }) { "Case-colliding local path $path" }
            out[path] = Local(path, document, if (document.isFile) hash(document) else null)
            if (document.isDirectory) scanLocal(document, path, out, budget)
        }
    }

    private suspend fun remoteHash(account: DavAccountEntity, password: CharArray, href: String): String = dav.get(account, password, href).use(::hash)
    private fun hash(document: DocumentFile): String? = context.contentResolver.openInputStream(document.uri)?.use(::hash)
    private fun hash(input: java.io.InputStream): String = MessageDigest.getInstance("SHA-256").let { digest ->
        input.copyTo(object : java.io.OutputStream() {
            override fun write(b: Int) {
                digest.update(b.toByte())
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                digest.update(b, off, len)
            }
        })
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun download(account: DavAccountEntity, password: CharArray, root: DocumentFile, path: String, href: String) {
        val parent = ensureDirectory(root, path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        val temporary =
            parent.createFile("application/octet-stream", ".inkdav-part-${UUID.randomUUID()}")
                ?: error("Cannot create temporary mirror file")
        try {
            context.contentResolver.openOutputStream(temporary.uri, "wt")!!.use { output ->
                dav.get(account, password, href).use { it.copyTo(output) }
            }
            parent.findFile(name)?.delete()
            require(temporary.renameTo(name)) { "Local provider cannot commit downloaded file" }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun ensureDirectory(root: DocumentFile, path: String): DocumentFile = path.split('/').filter(String::isNotBlank).fold(root) {
            current,
            name
        ->
        current.findFile(name)?.takeIf(DocumentFile::isDirectory)
            ?: current.createDirectory(name)
            ?: error("Cannot create local directory $name")
    }
    private fun find(root: DocumentFile, path: String): DocumentFile? = path.split('/').filter(String::isNotBlank).fold(root as DocumentFile?) { current, name -> current?.findFile(name) }

    @Suppress("DEPRECATION")
    private fun remoteHref(root: String, path: String): String = path.split('/').filter(String::isNotBlank).fold(root) { current, name ->
        URI(
            current.trimEnd('/') + "/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        ).toString()
    }
    private fun conflictPath(path: String, suffix: String): String {
        val dot = path.lastIndexOf('.')
        return if (dot >
            path.lastIndexOf('/')
        ) {
            path.substring(0, dot) + " (InkDAV $suffix)" + path.substring(dot)
        } else {
            "$path (InkDAV $suffix)"
        }
    }
    private fun entry(
        binding: String,
        path: String,
        remote: Remote?,
        local: Local?,
        old: MirrorEntryEntity?,
        status: MirrorEntryStatus,
        reason: String?
    ) = MirrorEntryEntity(
        id = old?.id ?: UUID.nameUUIDFromBytes("$binding|$path".encodeToByteArray()).toString(), bindingId = binding, relativePath = path,
        remoteHref = remote?.value?.href ?: old?.remoteHref.orEmpty(), localDocumentUri = local?.document?.uri?.toString(),
        isDirectory =
        remote?.value?.isCollection ?: local?.document?.isDirectory ?: false,
        mimeType = remote?.value?.contentType ?: local?.document?.type, baselineRemoteEtag = old?.baselineRemoteEtag, baselineLocalHash = old?.baselineLocalHash,
        currentRemoteEtag = remote?.value?.etag, currentLocalHash = local?.hash, status = status, conflictReason = reason
    )
}

package de.tobisk.inkvault.data

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object VaultPath {
    fun requireValid(path: String): String {
        require(path.isNotBlank() && path.length <= 4096 && !path.startsWith('/')) { "Invalid vault path" }
        require(path.none { it == '\\' || it.code < 32 || it.code == 127 }) { "Invalid vault path" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid vault path" }
        require(!path.startsWith(".git/") && path != ".git" && !path.startsWith(".obsidian-git-sync/")) { "Reserved vault path" }
        return path
    }
    fun folder(path: String) = path.substringBeforeLast('/', "")
    fun name(path: String) = path.substringAfterLast('/')
    fun hidden(path: String) = path.split('/').any { it.startsWith('.') }
    fun join(folder: String, name: String) = requireValid(if (folder.isEmpty()) name else "$folder/$name")
    fun sha256(input: InputStream): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
    fun sha256(bytes: ByteArray) = sha256(bytes.inputStream())
}

/** Immutable blobs make SQLite pointer + outbox commits atomic, including after process death. */
class BlobStore(private val root: File) {
    init {
        check(root.mkdirs() || root.isDirectory)
    }
    fun file(hash: String): File {
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256" }
        return File(root, hash)
    }
    fun put(input: InputStream, expectedHash: String? = null, expectedSize: Long? = null): String {
        val temporary = File.createTempFile("incoming-", ".tmp", root)
        try {
            input.use { source ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        size += n
                        require(size <= (expectedSize ?: MAX_FILE_SIZE)) { "File exceeds declared size or 512 MiB limit" }
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            require(expectedSize == null || temporary.length() == expectedSize) { "File size mismatch" }
            val hash = VaultPath.sha256(temporary.inputStream())
            require(expectedHash == null || hash == expectedHash) { "Downloaded file checksum mismatch" }
            val target = file(hash)
            if (!target.exists()) check(temporary.renameTo(target)) { "Cannot commit local file" }
            return hash
        } finally {
            temporary.delete()
        }
    }
    companion object {
        const val MAX_FILE_SIZE = 512L * 1024 * 1024
    }
}

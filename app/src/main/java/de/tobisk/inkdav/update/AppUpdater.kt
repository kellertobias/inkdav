package de.tobisk.inkdav.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val RELEASES_URL = "https://api.github.com/repos/kellertobias/inkdav/releases/latest"
private const val DOWNLOAD_PREFIX = "https://github.com/kellertobias/inkdav/releases/download/"

sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class Downloading(val version: String, val bytesRead: Long, val totalBytes: Long) : UpdateState

    data class Ready(val version: String, val apkPath: String, val installPrompted: Boolean = false) : UpdateState

    data class UpToDate(val version: String) : UpdateState

    data class Error(val message: String) : UpdateState
}

internal data class ReleaseAsset(val name: String, val downloadUrl: String, val digest: String?)

internal data class InkDavRelease(
    val version: String,
    val apk: ReleaseAsset,
    val checksum: ReleaseAsset
)

internal class AppUpdater(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient()
) {
    fun currentVersion(): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    suspend fun latestRelease(): InkDavRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "InkDAV-Android")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub returned HTTP ${response.code}." }
            parseRelease(JSONObject(checkNotNull(response.body).string()))
        }
    }

    suspend fun download(
        release: InkDavRelease,
        progress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        requireTrustedDownload(release.apk.downloadUrl)
        requireTrustedDownload(release.checksum.downloadUrl)

        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val destination = File(directory, release.apk.name)
        val partial = File(directory, "${release.apk.name}.part")
        partial.delete()

        val request = Request.Builder()
            .url(release.apk.downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "InkDAV-Android")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "APK download returned HTTP ${response.code}." }
            val body = checkNotNull(response.body)
            val total = body.contentLength()
            body.byteStream().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var received = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        progress(received, total)
                    }
                }
            }
        }

        val publishedChecksum = downloadChecksum(release.checksum, release.apk.name)
        val calculatedChecksum = sha256(partial)
        check(calculatedChecksum.equals(publishedChecksum, ignoreCase = true)) {
            "The downloaded APK did not match its published SHA-256 checksum."
        }
        release.apk.digest?.removePrefix("sha256:")?.takeIf { it.isNotBlank() }?.let { apiDigest ->
            check(calculatedChecksum.equals(apiDigest, ignoreCase = true)) {
                "The APK checksum did not match GitHub's release metadata."
            }
        }
        destination.delete()
        check(partial.renameTo(destination)) { "Could not finalize the downloaded APK." }
        destination
    }

    fun isNewer(version: String): Boolean = compareSemanticVersions(version, currentVersion()) > 0

    private fun parseRelease(json: JSONObject): InkDavRelease {
        val version = json.getString("tag_name").removePrefix("v")
        check(parseSemanticVersion(version) != null) { "GitHub's latest release has an invalid version." }
        val assetsJson = json.getJSONArray("assets")
        val assets = buildList {
            for (index in 0 until assetsJson.length()) {
                val asset = assetsJson.getJSONObject(index)
                add(
                    ReleaseAsset(
                        name = asset.getString("name"),
                        downloadUrl = asset.getString("browser_download_url"),
                        digest = asset.optString("digest").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
        return selectReleaseAssets(version, assets)
    }

    private fun downloadChecksum(asset: ReleaseAsset, apkName: String): String {
        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("Accept", "text/plain")
            .header("User-Agent", "InkDAV-Android")
            .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Checksum download returned HTTP ${response.code}." }
            val parts = checkNotNull(response.body).string().trim().split(Regex("\\s+"), limit = 2)
            check(parts.size == 2 && parts[1].removePrefix("*") == apkName) {
                "The release checksum does not name the expected APK."
            }
            check(parts[0].matches(Regex("[0-9a-fA-F]{64}"))) { "The release checksum is invalid." }
            parts[0]
        }
    }

    private fun requireTrustedDownload(url: String) {
        require(url.startsWith(DOWNLOAD_PREFIX)) { "GitHub returned an unexpected download address." }
    }
}

internal fun selectReleaseAssets(version: String, assets: List<ReleaseAsset>): InkDavRelease {
    val apkName = "InkDAV-v$version-boox-note-air5c.apk"
    val checksumName = "$apkName.sha256"
    return InkDavRelease(
        version = version,
        apk = checkNotNull(assets.singleOrNull { it.name == apkName }) { "The release has no BOOX APK." },
        checksum = checkNotNull(assets.singleOrNull { it.name == checksumName }) {
            "The release has no APK checksum."
        }
    )
}

internal fun compareSemanticVersions(left: String, right: String): Int {
    val leftParts = checkNotNull(parseSemanticVersion(left)) { "Invalid release version: $left" }
    val rightParts = checkNotNull(parseSemanticVersion(right)) { "Invalid installed version: $right" }
    return compareValuesBy(leftParts, rightParts, { it.first }, { it.second }, { it.third })
}

private fun parseSemanticVersion(value: String): Triple<Int, Int, Int>? {
    val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matchEntire(value) ?: return null
    return Triple(
        match.groupValues[1].toIntOrNull() ?: return null,
        match.groupValues[2].toIntOrNull() ?: return null,
        match.groupValues[3].toIntOrNull() ?: return null
    )
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

sealed interface InstallUpdateResult {
    data object PermissionRequired : InstallUpdateResult

    data object InstallerOpened : InstallUpdateResult

    data class Rejected(val reason: String) : InstallUpdateResult
}

object UpdateInstaller {
    fun unknownSourcesIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun install(context: Context, apkPath: String): InstallUpdateResult {
        if (!context.packageManager.canRequestPackageInstalls()) return InstallUpdateResult.PermissionRequired
        val apk = File(apkPath)
        val rejection = validatePackage(context, apk)
        if (rejection != null) return InstallUpdateResult.Rejected(rejection)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = android.content.ClipData.newRawUri("InkDAV update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(intent)
            InstallUpdateResult.InstallerOpened
        }.getOrElse { InstallUpdateResult.Rejected("Android could not open the package installer.") }
    }

    @Suppress("DEPRECATION")
    private fun validatePackage(context: Context, apk: File): String? {
        if (!apk.isFile) return "The downloaded update is no longer available."
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return "Android could not read the downloaded APK."
        if (archive.packageName != context.packageName) return "The update has the wrong application ID."

        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            return "The downloaded APK is not newer than the installed application."
        }
        if (signatures(archive) != signatures(installed)) {
            return "The update was not signed by the same InkDAV release key."
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val values = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return values.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        }.toSet()
    }
}

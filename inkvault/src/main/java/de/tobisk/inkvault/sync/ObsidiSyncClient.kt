package de.tobisk.inkvault.sync

import de.tobisk.inkvault.data.BlobStore
import de.tobisk.inkvault.data.VaultEntry
import de.tobisk.inkvault.data.VaultPath
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

interface SessionStore {
    var access: String
    var refresh: String
    fun accept(body: JSONObject)
}

class HttpFailure(val status: Int, detail: String = "") : IOException("ObsidiSync HTTP $status${if (detail.isEmpty()) "" else ": $detail"}")

/** Wire names and semantics mirror obsidisync/src/protocol.ts, API version 1. */
class ObsidiSyncClient(
    server: String,
    user: String,
    vault: String,
    private val session: SessionStore,
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build(),
    allowLoopbackForTests: Boolean = false
) {
    private val root = server.trimEnd('/').toHttpUrl().also {
        require(it.isHttps || (allowLoopbackForTests && it.host in setOf("localhost", "127.0.0.1"))) { "Use an HTTPS sync server" }
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    private val vaultUrl = root.newBuilder().addPathSegments("v1/users").addPathSegment(user).addPathSegment("vaults").addPathSegment(vault).build()
    private fun rootUrl(path: String) = root.newBuilder().addPathSegments(path).build()
    private fun url(path: String) = vaultUrl.newBuilder().addPathSegments(path).build()
    fun info(): Set<String> {
        val result = request(rootUrl("v1/server/info"), authenticated = false)
        require(result.getInt("apiVersion") >= 1 && result.getInt("minClientApiVersion") <= 1) { "Incompatible ObsidiSync API" }
        val features = result.optJSONArray("features") ?: JSONArray()
        return (0 until features.length()).map { features.getString(it) }.toSet()
    }
    fun login(username: String, password: String) {
        info()
        session.accept(request(rootUrl("v1/auth/password/login"), json(JSONObject().put("username", username).put("password", password)), false))
    }
    fun authConfig() = request(rootUrl("v1/auth/config"), authenticated = false)
    fun discover(issuer: String): JSONObject = request(secureUrl(issuer.trimEnd('/') + "/.well-known/openid-configuration"), authenticated = false)
    fun oidcForm(endpoint: String, fields: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return request(secureUrl(endpoint), body, false, allowOAuthError = true)
    }
    fun exchange(token: String) = session.accept(request(rootUrl("v1/auth/oidc/login"), json(JSONObject().put("accessToken", token)), false))
    private fun secureUrl(value: String) = value.toHttpUrl().also { require(it.isHttps && it.username.isEmpty() && it.password.isEmpty()) }
    fun register(remoteUrl: String = "") = request(url("register"), json(JSONObject().put("remoteUrl", remoteUrl).put("branch", "main").put("authorName", "InkVault").put("authorEmail", "inkvault@localhost")))
    fun sync(head: String?, clientId: String, device: String, entries: List<VaultEntry>, changes: JSONArray, references: Boolean, resolvePair: Boolean = false): JSONObject = request(
        url(if (resolvePair) "inkvault/resolve" else "sync"),
        json(
            JSONObject().put("baseHead", head ?: JSONObject.NULL).put("clientId", clientId).put("deviceName", device)
                .put("changes", changes).put("clientManifest", JSONArray(entries.filter { !it.deleted }.map { it.json() }))
                .put("fileContent", if (references) "reference" else "inline")
        )
    )
    fun resolve(clientId: String, device: String, files: JSONArray, references: Boolean) = request(
        url("resolve"),
        json(
            JSONObject()
                .put("clientId", clientId).put("deviceName", device).put("files", files).put("fileContent", if (references) "reference" else "inline")
        )
    )
    fun history(path: String): JSONObject = request(url("history").newBuilder().addQueryParameter("path", path).build())
    fun upload(path: String, file: File, hash: String): String {
        VaultPath.requireValid(path)
        val init = request(url("uploads"), json(JSONObject().put("path", path).put("sha256", hash).put("size", file.length())))
        val id = init.getString("uploadId")
        val endpoint = url("uploads").newBuilder().addPathSegment(id).build()
        val chunkSize = init.optInt("chunkSize", 512 * 1024).coerceIn(1, 2 * 1024 * 1024)
        file.inputStream().use { input ->
            val bytes = ByteArray(chunkSize)
            var offset = 0L
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                val result = request(
                    endpoint.newBuilder().addPathSegment("chunk").build(),
                    json(
                        JSONObject().put("offset", offset)
                            .put("contentBase64", Base64.getEncoder().encodeToString(bytes.copyOf(count)))
                    )
                )
                require(result.getLong("received") == offset + count) { "Invalid upload acknowledgement" }
                offset += count
            }
        }
        val complete = request(endpoint.newBuilder().addPathSegment("complete").build(), json(JSONObject()))
        require(complete.getString("sha256") == hash && complete.getLong("size") == file.length()) { "Upload verification failed" }
        return complete.getString("uploadId")
    }
    fun download(file: JSONObject, head: String?, blobs: BlobStore) {
        VaultPath.requireValid(file.getString("path"))
        val hash = file.getString("sha256")
        if (blobs.file(hash).isFile) return
        val size = if (file.has("size")) file.getLong("size") else null
        if (file.has("contentBase64")) {
            blobs.put(Base64.getDecoder().decode(file.getString("contentBase64")).inputStream(), hash, size)
        } else {
            require(!head.isNullOrBlank() && head != "null") { "Missing blob revision" }
            val endpoint = url("blob").newBuilder().addQueryParameter("path", file.getString("path")).addQueryParameter("hash", head).build()
            for (attempt in 0..1) {
                http.newCall(Request.Builder().url(endpoint).header("Authorization", "Bearer ${session.access}").header("X-ObsidiSync-Client-Features", "inkVaultNotesV1").build()).execute().use { response ->
                    if (response.code == 401 && attempt == 0 && refresh()) return@use
                    if (!response.isSuccessful) throw HttpFailure(response.code)
                    blobs.put(response.body.byteStream(), hash, size)
                    return
                }
            }
        }
    }
    private fun refresh(): Boolean {
        if (session.refresh.isBlank()) return false
        session.accept(request(rootUrl("v1/auth/session/refresh"), json(JSONObject().put("refreshToken", session.refresh)), false))
        return true
    }
    private fun request(endpoint: HttpUrl, body: RequestBody? = null, authenticated: Boolean = true, allowOAuthError: Boolean = false): JSONObject {
        for (attempt in 0..1) {
            val request = Request.Builder().url(endpoint).apply {
                if (body != null) post(body)
                if (authenticated) {
                    header("Authorization", "Bearer ${session.access}")
                    header("X-ObsidiSync-Client-Features", "inkVaultNotesV1")
                }
            }.build()
            http.newCall(request).execute().use { response ->
                if (authenticated && response.code == 401 && attempt == 0 && refresh()) return@use
                if (!response.isSuccessful && !(allowOAuthError && response.code == 400)) {
                    val detail = runCatching { JSONObject(response.peekBody(4096).string()).optString("error").take(400) }.getOrDefault("")
                    throw HttpFailure(response.code, detail)
                }
                // Metadata is bounded independently of streaming file bodies.
                val source = response.body.source()
                require(!source.request(32L * 1024 * 1024 + 1)) { "Sync metadata exceeds 32 MiB; update server for referenced files" }
                val payload = source.readUtf8()
                return if (payload.trimStart().startsWith("[")) JSONObject().put("entries", JSONArray(payload)) else JSONObject(payload)
            }
        }
        throw HttpFailure(401)
    }
    private fun json(value: JSONObject) = value.toString().toRequestBody("application/json".toMediaType())
}

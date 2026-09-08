package de.tobisk.inkvault

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.work.*
import de.tobisk.inkvault.data.CredentialStore
import de.tobisk.inkvault.data.VaultStore
import de.tobisk.inkvault.sync.ObsidiSyncClient
import de.tobisk.inkvault.sync.SessionStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class InkVaultApplication :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()
    lateinit var store: VaultStore
    val syncLock = Any()
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        de.tobisk.inkvault.ui.BooxFirmware.initialize()
    }
    override fun onCreate() {
        super.onCreate()
        store = VaultStore(this)
        if (store.meta("clientId") == null) store.setMeta("clientId", UUID.randomUUID().toString())
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "vault-periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<VaultSyncWorker>(15, TimeUnit.MINUTES).setConstraints(online()).build()
        )
        syncNow()
    }
    fun syncNow() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            "vault-sync",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<VaultSyncWorker>().setConstraints(online()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        )
    }
    private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun client(): ObsidiSyncClient {
        val credentials = CredentialStore(this)
        val server = requireNotNull(store.meta("server")) { "Configure a server in Settings" }
        val sessionKey = "session:" + de.tobisk.inkvault.data.VaultPath.sha256(server.toByteArray())
        return ObsidiSyncClient(
            server,
            store.meta("user") ?: "",
            store.meta("vault") ?: "",
            object : SessionStore {
                private fun saved() = JSONObject(credentials.get(sessionKey)?.concatToString() ?: "{}")
                override var access: String
                    get() = saved().optString("accessToken")
                    set(value) {
                        credentials.put(sessionKey, saved().put("accessToken", value).toString().toCharArray())
                    }
                override var refresh: String
                    get() = saved().optString("refreshToken")
                    set(value) {
                        credentials.put(sessionKey, saved().put("refreshToken", value).toString().toCharArray())
                    }
                override fun accept(body: JSONObject) {
                    require(body.getString("accessToken").isNotBlank() && body.getString("refreshToken").isNotBlank())
                    require(store.meta("server") == server) { "Server configuration changed during login" }
                    val oldUser = store.meta("user")
                    require(oldUser.isNullOrBlank() || oldUser == body.getString("user") || store.entries().isEmpty()) { "This local vault belongs to a different user" }
                    credentials.put(sessionKey, body.toString().toCharArray())
                    store.setMeta("user", body.getString("user"))
                }
            }
        )
    }
    fun synchronize() = synchronized(syncLock) {
        if (store.meta("server").isNullOrBlank()) return@synchronized
        store.setMeta("status", "Syncing")
        val client = client()
        val features = client.info()
        store.setMeta("features", JSONArray(features.toList()).toString())
        // Finish a durable response before issuing another request, including after process death.
        if (store.meta("journal") != null) finishJournal(client)
        if (store.entries().any { it.conflict.isNotEmpty() }) {
            store.setMeta("status", "Conflict — open the affected file to resolve")
            return@synchronized
        }
        val native = "inkVaultNotesV1" in features
        fun exchange(selected: List<de.tobisk.inkvault.data.VaultEntry>) {
            val snapshot = store.entries()
            require(snapshot.size <= 20000) { "Vault exceeds the 20,000-file metadata limit" }
            val changes = JSONArray()
            for (entry in selected) {
                val change = JSONObject().put("path", entry.path).put("op", if (entry.deleted) "delete" else "upsert")
                if (!entry.deleted) change.put("uploadId", client.upload(entry.path, store.blobs.file(entry.hash), entry.hash)).put("sha256", entry.hash).put("mtime", entry.modified)
                changes.put(change)
            }
            val response = client.sync(store.meta("head"), requireNotNull(store.meta("clientId")), "InkVault • ${Build.MODEL}", snapshot, changes, "syncFileReferences" in features)
            store.journal(response, snapshot, selected.map { it.path }.toSet())
            finishJournal(client)
        }
        exchange(store.entries().filter { it.dirty && !it.path.startsWith(".inkvault/") })
        if (native && store.entries().none { it.conflict.isNotEmpty() }) {
            val roots = store.entries().filter { it.dirty && it.path.startsWith(".inkvault/notes/") }.map { it.path.split('/').take(3).joinToString("/") }.distinct()
            for (root in roots) {
                if (store.entries().any { it.conflict.isNotEmpty() }) break
                // Upload a complete package; the server derives and commits the matching PDF.
                exchange(store.entries().filter { it.path.startsWith("$root/") })
            }
        }
        val entries = store.entries()
        store.setMeta(
            "status",
            when {
                entries.any { it.conflict.isNotEmpty() } -> "Conflict — open the affected file to resolve"
                entries.any { it.dirty && (native || !it.path.startsWith(".inkvault/")) } -> "Locally saved · Pending sync"
                entries.any { it.dirty } -> "Files synced · InkNotes locally saved (server extension required)"
                else -> "Synced"
            }
        )
        store.setMeta("syncCompleted", System.currentTimeMillis().toString())
    }
    fun resolvePair(root: String, remote: Boolean) {
        if (remote) {
            store.acceptRemotePair(root)
            return
        }
        val client = client()
        require("inkVaultNotesV1" in client.info()) { "Server does not support InkNote pairing" }
        val manifestPath = "$root/manifest.json"
        val entry = requireNotNull(store.get(manifestPath))
        if (!entry.deleted) {
            val manifest = JSONObject(store.blobs.file(entry.hash).readText())
            val remoteRevision = store.conflictRemote(manifestPath)?.let { JSONObject(store.blobs.file(it).readText()).getLong("sourceRevision") } ?: 0
            manifest.put("sourceRevision", maxOf(manifest.getLong("sourceRevision"), remoteRevision) + 1)
            manifest.put("modified", System.currentTimeMillis())
            store.saveText(manifestPath, manifest.toString())
        }
        val snapshot = store.entries()
        val selected = snapshot.filter { it.path.startsWith("$root/") }
        val changes = JSONArray()
        for (file in selected) {
            val change = JSONObject().put("path", file.path).put("op", if (file.deleted) "delete" else "upsert")
            if (!file.deleted) change.put("uploadId", client.upload(file.path, store.blobs.file(file.hash), file.hash)).put("sha256", file.hash).put("mtime", file.modified)
            changes.put(change)
        }
        val response = try {
            client.sync(requireNotNull(store.meta("conflictHead")), requireNotNull(store.meta("clientId")), "InkVault • ${Build.MODEL}", snapshot, changes, true, resolvePair = true)
        } catch (failure: de.tobisk.inkvault.sync.HttpFailure) {
            if (failure.status == 409) {
                val latest = client.sync(null, requireNotNull(store.meta("clientId")), "InkVault", emptyList(), JSONArray(), true)
                val pdf = JSONObject(store.blobs.file(requireNotNull(store.get(manifestPath)).hash).readText()).getString("pdfPath")
                val all = latest.getJSONArray("files")
                val pair = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("path").startsWith("$root/") || it.getString("path") == pdf }
                latest.put("files", JSONArray(pair)).put("status", "conflict").put("conflicts", JSONArray(pair.map { JSONObject().put("path", it.getString("path")).put("reason", "InkNote pair changed: $root") }))
                store.journal(latest, store.entries(), emptySet())
                finishJournal(client)
            }
            throw failure
        }

        store.journal(response, snapshot, selected.map { it.path }.toSet())
        finishJournal(client)
        if (response.getString("status") == "ok") store.entries().filter { store.pairRoot(it.path) == root && it.conflict.isEmpty() }.forEach { store.clearConflict(it.path) }
    }

    fun finishJournal(client: ObsidiSyncClient) {
        val response = JSONObject(requireNotNull(store.meta("journal"))).getJSONObject("response")
        val files = response.getJSONArray("files")
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            require(!file.getString("path").startsWith(".inkvault/") || store.meta("features").orEmpty().contains("inkVaultNotesV1")) { "InkNote pair protocol is not negotiated" }
            if (file.getString("op") == "upsert") client.download(file, response.optString("serverHead"), store.blobs)
        }
        store.applyJournal()
    }
}

class VaultSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as InkVaultApplication
        try {
            app.synchronize()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            app.store.setMeta("status", "Locally saved · Sync failed: ${e.message}")
            if (e is java.io.IOException) Result.retry() else Result.failure()
        }
    }
}

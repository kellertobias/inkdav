package de.tobisk.inkdav.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import de.tobisk.inkdav.InkDavApplication
import de.tobisk.inkdav.data.AccountKind
import de.tobisk.inkdav.data.DavAccountEntity
import de.tobisk.inkdav.data.InkDavDao
import de.tobisk.inkdav.security.CredentialStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val METHOD_SNAPSHOT = "snapshot"
private const val METHOD_APPLY = "apply"
private const val KEY_REVISION = "revision"
private const val KEY_ACCOUNTS = "accounts"
private const val KEY_ID = "id"
private const val KEY_NAME = "name"
private const val KEY_URL = "url"
private const val KEY_USERNAME = "username"
private const val KEY_KIND = "kind"
private const val KEY_ENABLED = "enabled"
private const val KEY_SECRET = "secret"
private const val REVISION_PREFERENCES = "server_setup_replication"
private const val REVISION_KEY = "revision"

private val SERVER_SETUP_AUTHORITIES = listOf(
    "de.tobisk.inkdav.server-setup",
    "de.tobisk.inkdav.todos.server-setup",
    "de.tobisk.inkdav.files.server-setup"
)

/** Replicates server definitions only between installed InkDAV apps signed by the same key. */
class ServerSetupCoordinator(
    private val context: Context,
    private val dao: InkDavDao,
    private val credentials: CredentialStore
) {
    suspend fun pullFromPeers() {
        val localRevision = revision(context)
        val snapshots = peerAuthorities()
            .mapNotNull { authority -> runCatching { context.contentResolver.call(uri(authority), METHOD_SNAPSHOT, null, null) }.getOrNull() }
        val best = snapshots.maxByOrNull { it.getLong(KEY_REVISION) }
        try {
            if (best != null) {
                val remoteRevision = best.getLong(KEY_REVISION)
                val localHasAccounts = dao.observeAccounts().first().isNotEmpty()
                if (remoteRevision > localRevision || (!localHasAccounts && accounts(best).isNotEmpty())) {
                    applySnapshot(context, dao, credentials, best)
                }
            }
        } finally {
            snapshots.forEach(::clearSecrets)
        }
    }

    suspend fun publish() {
        val nextRevision = maxOf(System.currentTimeMillis(), revision(context) + 1)
        setRevision(context, nextRevision)
        val snapshot = snapshot(context, dao, credentials)
        try {
            peerAuthorities().forEach { authority ->
                runCatching { context.contentResolver.call(uri(authority), METHOD_APPLY, null, snapshot) }
            }
        } finally {
            clearSecrets(snapshot)
        }
    }

    private fun peerAuthorities(): List<String> = SERVER_SETUP_AUTHORITIES.filter { authority ->
        authority != "${context.packageName}.server-setup" &&
            context.packageManager.resolveContentProvider(authority, PackageManager.MATCH_ALL) != null
    }
}

class ServerSetupProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val application = requireNotNull(context?.applicationContext) as InkDavApplication
        val container = application.container
        return when (method) {
            METHOD_SNAPSHOT -> runBlocking { snapshot(application, container.database.dao(), container.credentials) }
            METHOD_APPLY -> {
                val incoming = requireNotNull(extras) { "A server setup snapshot is required." }
                runBlocking { applySnapshot(application, container.database.dao(), container.credentials, incoming) }
                Bundle.EMPTY
            }
            else -> super.call(method, arg, extras)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private suspend fun snapshot(context: Context, dao: InkDavDao, credentials: CredentialStore): Bundle {
    val serialized = ArrayList<Bundle>()
    dao.observeAccounts().first().forEach { account ->
        val secret = credentials.get(account.id)?.let { password ->
            try {
                password.concatToString().encodeToByteArray()
            } finally {
                password.fill('\u0000')
            }
        }
        serialized += Bundle().apply {
            putString(KEY_ID, account.id)
            putString(KEY_NAME, account.displayName)
            putString(KEY_URL, account.baseUrl)
            putString(KEY_USERNAME, account.username)
            putString(KEY_KIND, account.kind.name)
            putBoolean(KEY_ENABLED, account.enabled)
            putByteArray(KEY_SECRET, secret)
        }
    }
    return Bundle().apply {
        putLong(KEY_REVISION, revision(context))
        putParcelableArrayList(KEY_ACCOUNTS, serialized)
    }
}

private suspend fun applySnapshot(context: Context, dao: InkDavDao, credentials: CredentialStore, incoming: Bundle) {
    val incomingRevision = incoming.getLong(KEY_REVISION)
    if (incomingRevision < revision(context)) return
    val remoteAccounts = accounts(incoming).associateBy { requireNotNull(it.getString(KEY_ID)) }
    val localAccounts = dao.observeAccounts().first().associateBy(DavAccountEntity::id)

    (localAccounts.keys - remoteAccounts.keys).forEach { id ->
        credentials.remove(id)
        dao.removeAccountData(requireNotNull(localAccounts[id]))
    }
    remoteAccounts.forEach { (id, value) ->
        val existing = localAccounts[id]
        dao.upsertAccount(
            DavAccountEntity(
                id = id,
                displayName = requireNotNull(value.getString(KEY_NAME)),
                baseUrl = requireNotNull(value.getString(KEY_URL)),
                username = requireNotNull(value.getString(KEY_USERNAME)),
                kind = AccountKind.valueOf(requireNotNull(value.getString(KEY_KIND))),
                enabled = value.getBoolean(KEY_ENABLED),
                lastSyncAt = existing?.lastSyncAt,
                lastSyncError = existing?.lastSyncError
            )
        )
        value.getByteArray(KEY_SECRET)?.let { secret ->
            try {
                credentials.put(id, secret.decodeToString().toCharArray())
            } finally {
                secret.fill(0)
            }
        }
    }
    setRevision(context, incomingRevision)
}

@Suppress("DEPRECATION")
private fun accounts(bundle: Bundle): ArrayList<Bundle> = bundle.getParcelableArrayList(KEY_ACCOUNTS) ?: arrayListOf()

private fun clearSecrets(bundle: Bundle) {
    accounts(bundle).forEach { it.getByteArray(KEY_SECRET)?.fill(0) }
}

private fun revision(context: Context): Long = context.getSharedPreferences(REVISION_PREFERENCES, Context.MODE_PRIVATE).getLong(REVISION_KEY, 0)

private fun setRevision(context: Context, revision: Long) {
    context.getSharedPreferences(REVISION_PREFERENCES, Context.MODE_PRIVATE).edit().putLong(REVISION_KEY, revision).apply()
}

private fun uri(authority: String): Uri = Uri.Builder().scheme("content").authority(authority).build()

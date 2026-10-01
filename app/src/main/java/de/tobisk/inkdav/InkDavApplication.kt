package de.tobisk.inkdav

import android.app.Application
import de.tobisk.inkdav.data.InkDavDatabase
import de.tobisk.inkdav.data.OfflineRepository
import de.tobisk.inkdav.dav.OkHttpDavClient
import de.tobisk.inkdav.files.MirrorSyncEngine
import de.tobisk.inkdav.security.CredentialStore
import de.tobisk.inkdav.settings.ServerSetupCoordinator
import de.tobisk.inkdav.settings.UserPreferences
import de.tobisk.inkdav.sync.SyncEngine
import de.tobisk.inkdav.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(application: Application) {
    val database = InkDavDatabase.get(application)
    val credentials = CredentialStore(application)
    val preferences = UserPreferences(application)
    val serverSetup = ServerSetupCoordinator(application, database.dao(), credentials)
    val davClient = OkHttpDavClient()
    val offlineRepository = OfflineRepository(database.dao())
    val mirrorSyncEngine = MirrorSyncEngine(application, database.dao(), davClient)
    val syncEngine =
        SyncEngine(database.dao(), credentials, davClient, preferences, application.filesDir.resolve("offline"), mirrorSyncEngine)
}

open class InkDavApplication : Application() {
    val container by lazy { AppContainer(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { container.serverSetup.pullFromPeers() }
        SyncWorker.schedulePeriodic(this)
    }
}

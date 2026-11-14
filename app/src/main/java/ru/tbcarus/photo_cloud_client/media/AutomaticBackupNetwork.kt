package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.backup.FolderBackupScheduler
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.BackupNetworkMode
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import javax.inject.Inject
import javax.inject.Singleton

/** Единая политика автоматических Camera и SAF запросов; активная VPN остаётся маршрутом соединения. */
@Singleton
open class AutomaticBackupNetwork @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ServerPreferences,
    private val transport: WifiUploadNetwork,
    private val cameraScheduler: SyncScheduler,
    private val folderScheduler: FolderBackupScheduler
) {
    @Inject lateinit var apiServices: ApiServiceFactory
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    open suspend fun current(): Network? =
        transport.current(preferences.backupNetworkMode().first() == BackupNetworkMode.WIFI_AND_MOBILE)

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            var previous: Boolean? = null
            combine(preferences.backupNetworkMode(), networkChanges()) { mode, _ ->
                transport.current(mode == BackupNetworkMode.WIFI_AND_MOBILE) != null
            }.distinctUntilChanged().collect { allowed ->
                if (!allowed && ::apiServices.isInitialized) {
                    apiServices.cancelAutomaticCalls()
                }
                if (allowed && previous == false) {
                    cameraScheduler.enqueueUpload()
                    folderScheduler.enqueue()
                }
                previous = allowed
            }
        }
    }

    private fun networkChanges() = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            trySend(Unit)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        }
        manager.registerDefaultNetworkCallback(callback)
        trySend(Unit)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
}

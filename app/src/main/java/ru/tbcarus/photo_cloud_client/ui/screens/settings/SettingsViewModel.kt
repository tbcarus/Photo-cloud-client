package ru.tbcarus.photo_cloud_client.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.core.server.BackupNetworkMode
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.media.PeriodicSyncCoordinator
import ru.tbcarus.photo_cloud_client.media.SyncScheduler
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val periodicSyncCoordinator: PeriodicSyncCoordinator,
    private val syncScheduler: SyncScheduler,
    private val serverPreferences: ServerPreferences
) : ViewModel() {

    val backupNetworkMode = serverPreferences.backupNetworkMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupNetworkMode.WIFI_ONLY)

    fun setBackupNetworkMode(mode: BackupNetworkMode) {
        viewModelScope.launch { serverPreferences.saveBackupNetworkMode(mode) }
    }

    /**
     * Вызывать, когда в Settings появился полный доступ к фото (ответ на запрос или возврат из настроек Android).
     * Включает автосинк (наблюдатель MediaStore, observer, periodic) и сразу ставит разовый Поставщик,
     * чтобы scan и hash не ждали periodic.
     */
    fun onMediaPermissionGranted() {
        periodicSyncCoordinator.reconcile()
        syncScheduler.enqueueProvider()
    }

    fun onMediaPermissionChanged(hasAnyFullAccess: Boolean) {
        periodicSyncCoordinator.reconcile()
        if (hasAnyFullAccess) syncScheduler.enqueueProvider()
    }
}

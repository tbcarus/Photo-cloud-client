package ru.tbcarus.photo_cloud_client.ui.screens.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ru.tbcarus.photo_cloud_client.media.PeriodicSyncCoordinator
import ru.tbcarus.photo_cloud_client.media.SyncScheduler
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val periodicSyncCoordinator: PeriodicSyncCoordinator,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    /**
     * Вызывать, когда в Settings появился полный доступ к фото (ответ на запрос или возврат из настроек Android).
     * Включает автосинк (наблюдатель MediaStore, observer, periodic) и сразу ставит разовый Поставщик,
     * чтобы scan и hash не ждали periodic.
     */
    fun onMediaPermissionGranted() {
        periodicSyncCoordinator.reconcile()
        syncScheduler.enqueueProvider()
    }
}

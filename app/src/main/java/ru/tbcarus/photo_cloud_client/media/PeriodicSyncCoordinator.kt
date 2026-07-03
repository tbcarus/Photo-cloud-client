package ru.tbcarus.photo_cloud_client.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.tbcarus.photo_cloud_client.auth.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Держит фоновую синхронизацию в согласии с текущим состоянием приложения.
 *
 * Правило: есть токены + настроен baseUrl + выдано media permission →
 *   periodic sync включён и [MediaChangeObserver] слушает галерею;
 * иначе — periodic sync отменён и observer остановлен.
 *
 * Вызывается от изменения состояния (старт приложения после восстановления baseUrl,
 * login, logout, grant media permission), а не «по факту login» —
 * [reconcile] всегда смотрит на актуальное состояние.
 */
@Singleton
class PeriodicSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val syncScheduler: SyncScheduler,
    private val mediaChangeObserver: MediaChangeObserver
) {
    /**
     * true, если автосинк настроен: есть токены и baseUrl.
     * Media permission НЕ учитывается — именно его UI запрашивает при входе на экран.
     */
    fun isAutoSyncConfigured(): Boolean =
        authRepository.getTokens() != null && authRepository.isReady()

    fun reconcile() {
        val hasTokens = authRepository.getTokens() != null
        val hasBaseUrl = authRepository.isReady()
        val hasMediaPermission = hasMediaPermission()

        if (hasTokens && hasBaseUrl && hasMediaPermission) {
            syncScheduler.enqueuePeriodicSync()
            mediaChangeObserver.start()
        } else {
            syncScheduler.cancelPeriodicSync()
            mediaChangeObserver.stop()
        }
    }

    private fun hasMediaPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}

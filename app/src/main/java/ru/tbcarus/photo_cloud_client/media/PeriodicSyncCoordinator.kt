package ru.tbcarus.photo_cloud_client.media

import ru.tbcarus.photo_cloud_client.auth.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Держит periodic sync в согласии с текущим состоянием приложения.
 *
 * Правило: есть токены + настроен baseUrl → periodic включён, иначе выключен.
 * Вызывается от изменения состояния (старт приложения после восстановления baseUrl,
 * login, logout), а не «по факту login» — [reconcile] всегда смотрит на актуальное состояние.
 */
@Singleton
class PeriodicSyncCoordinator @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncScheduler: SyncScheduler
) {
    fun reconcile() {
        val hasTokens = authRepository.getTokens() != null
        val hasBaseUrl = authRepository.isReady()

        if (hasTokens && hasBaseUrl) {
            syncScheduler.enqueuePeriodicSync()
        } else {
            syncScheduler.cancelPeriodicSync()
        }
    }
}

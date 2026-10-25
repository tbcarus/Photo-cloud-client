package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.tbcarus.photo_cloud_client.auth.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Держит фоновую синхронизацию в согласии с текущим состоянием приложения.
 *
 * Правила (A4.1):
 *  - выдано media permission → periodic Поставщика, наблюдатель MediaStore ([MediaTriggerWorker])
 *    и [MediaChangeObserver] включены независимо от входа; иначе — отменены и остановлены;
 *  - дополнительно есть токены и настроен baseUrl → periodic Загрузчика включён; иначе — отменён.
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
        val hasMediaPermission = context.hasMediaReadPermission()
        if (hasMediaPermission) {
            syncScheduler.enqueueProviderPeriodic()
            syncScheduler.enqueueMediaTrigger()
            mediaChangeObserver.start()
        } else {
            syncScheduler.cancelProviderPeriodic()
            syncScheduler.cancelMediaTrigger()
            mediaChangeObserver.stop()
        }

        if (hasMediaPermission && isAutoSyncConfigured()) {
            syncScheduler.enqueueUploadPeriodic()
        } else {
            syncScheduler.cancelUploadPeriodic()
        }
    }

    /**
     * Восстанавливает наблюдатель MediaStore, если его задания нет (сбой, прерывание, разрешение выдано в настройках).
     * Вызывается Поставщиком, в том числе periodic, — отдельного механизма восстановления нет.
     */
    fun ensureMediaTrigger() {
        if (context.hasMediaReadPermission()) {
            syncScheduler.enqueueMediaTrigger()
        }
    }

    /**
     * Разовый запуск Загрузчика: после подготовки записей Поставщиком и после login.
     * Без пары не ставится — иначе после logout каждый прогон Поставщика завершался бы no_session.
     * baseUrl здесь не проверяется: в процессе без UI он ещё не восстановлен, Загрузчик перечитает его сам.
     */
    fun requestUpload() {
        if (authRepository.getTokens() != null) {
            syncScheduler.enqueueUpload()
        }
    }
}

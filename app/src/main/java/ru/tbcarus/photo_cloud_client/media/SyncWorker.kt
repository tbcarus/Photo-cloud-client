package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ru.tbcarus.photo_cloud_client.core.server.ServerRepository
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider

/**
 * Автоматический one-time sync через WorkManager.
 * Прогоняет стадии строго последовательно: scan → checksum → pre-check → upload.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val mediaFileRepository: MediaFileRepository,
    private val checksumRepository: ChecksumRepository,
    private val checksumPrecheckRepository: ChecksumPrecheckRepository,
    private val fileUploadRepository: FileUploadRepository,
    private val serverRepository: ServerRepository,
    private val baseUrlProvider: BaseUrlProvider,
    private val syncStatusStore: SyncStatusStore,
    private val tokenStorage: TokenStorage,
    private val periodicSyncCoordinator: PeriodicSyncCoordinator
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SyncWorker"
        /** Ключ outputData с причиной остановки без синхронизации. */
        const val STOP_REASON_KEY = "stop_reason"
        const val STOP_REASON_NO_SESSION = "no_session"
    }

    override suspend fun doWork(): Result {
        return try {
            runSync()
        } catch (e: Exception) {
            // Не пробрасываем исключение наружу — даём WorkManager шанс повторить.
            Log.e(TAG, "Sync завершился исключением", e)
            syncStatusStore.record(SyncStatus.ERROR)
            Result.retry()
        }
    }

    private suspend fun runSync(): Result {
        // Без пары авторизованные стадии гарантированно получат 401: до сетевых запросов останавливаем фон и не просим retry.
        if (tokenStorage.getTokens() == null) {
            Log.w(TAG, "Нет сессии — фоновая синхронизация остановлена до входа")
            syncStatusStore.record(SyncStatus.ERROR)
            // Последнее действие прогона: reconcile без пары только отменяет periodic и observer и ничего не планирует.
            // Если это сам periodic, WorkManager остановит уже завершающийся прогон — повтора не будет.
            periodicSyncCoordinator.reconcile()
            return Result.success(workDataOf(STOP_REASON_KEY to STOP_REASON_NO_SESSION))
        }

        // Адрес берём из сохранённых настроек, а не из памяти: процесс мог быть запущен WorkManager без UI,
        // а пользователь — сменить адрес после прошлого прогона.
        val baseUrl = baseUrlProvider.reload()
        // Быстрая проверка доступности сервера через существующую тестовую ручку (GET /api/v1/test).
        // Если сервер недоступен — не гоняем весь pipeline впустую в timeout, а сразу просим повтор.
        if (baseUrl.isBlank() || serverRepository.testConnection(baseUrl).isFailure) {
            Log.w(TAG, "Сервер недоступен — откладываем sync")
            syncStatusStore.record(SyncStatus.SERVER_UNAVAILABLE)
            return Result.retry()
        }

        var sync = SyncResult()

        // 0. Auto-retry (Stage 5K): до обычного pipeline возвращаем в очередь
        // временно упавшие (CHECKSUM_IO) записи, у которых прошёл cooldown и не исчерпан лимит попыток.
        // Восстанавливаем один раз за проход — повторно упавшие записи в этом же прогоне не выбираются.
        val restored = checksumRepository.restoreRetryableFailed()
        if (restored > 0) Log.i(TAG, "Auto-retry: восстановлено retryable FAILED = $restored")

        // 1. Scan
        when (val outcome = mediaFileRepository.scanImages()) {
            is ScanOutcome.Success -> sync = sync.copy(scan = outcome.result)
            // Фоновый worker не может запросить runtime-разрешение — это не crash и не retry.
            is ScanOutcome.PermissionDenied -> return Result.success()
            is ScanOutcome.Error -> {
                syncStatusStore.record(SyncStatus.ERROR)
                return Result.retry()
            }
        }

        // 2. Checksum (обязательная стадия — без неё pipeline стоит)
        when (val outcome = checksumRepository.computePendingChecksums()) {
            is ChecksumOutcome.Success -> sync = sync.copy(checksum = outcome.result)
            is ChecksumOutcome.PermissionDenied -> return Result.success()
            is ChecksumOutcome.Error -> {
                syncStatusStore.record(SyncStatus.ERROR)
                return Result.retry()
            }
        }

        // 3. Pre-check
        when (val outcome = checksumPrecheckRepository.runCameraPrecheck()) {
            is ChecksumPrecheckOutcome.Success -> sync = sync.copy(precheck = outcome.result)
            is ChecksumPrecheckOutcome.Error -> {
                syncStatusStore.record(SyncStatus.ERROR)
                return Result.retry()
            }
        }

        // 4. Upload
        // FAILED-записи считаем терминальными для текущего этапа.
        // Их повторный запуск будет отдельной задачей.
        val retryNeeded: Boolean
        when (val outcome = fileUploadRepository.uploadPendingCameraFiles()) {
            is UploadOutcome.Success -> {
                sync = sync.copy(upload = outcome.result)
                // Осталась очередь из-за transient-ошибки → просим повтор.
                retryNeeded = outcome.result.leftPending > 0
            }
            is UploadOutcome.Error -> {
                sync = sync.copy(upload = outcome.result)
                // Прогон upload оборвался на transient-ошибке → повтор.
                retryNeeded = true
            }
        }

        return if (retryNeeded) {
            // Осталась очередь / transient error на upload — WorkManager повторит.
            syncStatusStore.record(SyncStatus.RETRY_SCHEDULED)
            Result.retry()
        } else {
            syncStatusStore.record(SyncStatus.SUCCESS)
            Result.success(buildOutput(sync))
        }
    }

    /** Минимальная сводка по стадиям — для логов/диагностики через outputData. */
    private fun buildOutput(sync: SyncResult): Data = workDataOf(
        "scan_scanned" to (sync.scan?.scanned ?: 0),
        "checksum_processed" to (sync.checksum?.processed ?: 0),
        "precheck_checked" to (sync.precheck?.checked ?: 0),
        "upload_attempted" to (sync.upload?.attempted ?: 0),
        "upload_succeeded" to (sync.upload?.succeeded ?: 0),
        "upload_failed" to (sync.upload?.failed ?: 0),
        "upload_left_pending" to (sync.upload?.leftPending ?: 0)
    )
}

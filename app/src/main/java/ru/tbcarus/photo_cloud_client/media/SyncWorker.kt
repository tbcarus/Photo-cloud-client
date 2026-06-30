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
    private val baseUrlProvider: BaseUrlProvider
) : CoroutineWorker(appContext, params) {

    private companion object {
        const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            runSync()
        } catch (e: Exception) {
            // Не пробрасываем исключение наружу — даём WorkManager шанс повторить.
            Log.e(TAG, "Sync завершился исключением", e)
            Result.retry()
        }
    }

    private suspend fun runSync(): Result {
        // Быстрая проверка доступности сервера через существующую тестовую ручку (GET /api/v1/test).
        // Если сервер недоступен — не гоняем весь pipeline впустую в timeout, а сразу просим повтор.
        val baseUrl = baseUrlProvider.baseUrl
        if (baseUrl.isBlank() || serverRepository.testConnection(baseUrl).isFailure) {
            Log.w(TAG, "Сервер недоступен — откладываем sync")
            return Result.retry()
        }

        var sync = SyncResult()

        // 1. Scan
        when (val outcome = mediaFileRepository.scanImages()) {
            is ScanOutcome.Success -> sync = sync.copy(scan = outcome.result)
            // Фоновый worker не может запросить runtime-разрешение — это не crash и не retry.
            is ScanOutcome.PermissionDenied -> return Result.success()
            is ScanOutcome.Error -> return Result.retry()
        }

        // 2. Checksum (обязательная стадия — без неё pipeline стоит)
        when (val outcome = checksumRepository.computePendingChecksums()) {
            is ChecksumOutcome.Success -> sync = sync.copy(checksum = outcome.result)
            is ChecksumOutcome.PermissionDenied -> return Result.success()
            is ChecksumOutcome.Error -> return Result.retry()
        }

        // 3. Pre-check
        when (val outcome = checksumPrecheckRepository.runCameraPrecheck()) {
            is ChecksumPrecheckOutcome.Success -> sync = sync.copy(precheck = outcome.result)
            is ChecksumPrecheckOutcome.Error -> return Result.retry()
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

        return if (retryNeeded) Result.retry() else Result.success(buildOutput(sync))
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

package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ru.tbcarus.photo_cloud_client.core.server.ServerRepository
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import kotlin.coroutines.cancellation.CancellationException

/**
 * Загрузчик (A4.1): серверная обработка подготовленной Поставщиком очереди — pre-check → upload.
 * Прогон ограничен бюджетом времени; остаток очереди продолжает следующий прогон (A8.2).
 * Не сканирует MediaStore и не вычисляет SHA-256. При отсутствии сессии, сервера или разрешения
 * очередь остаётся в Room без изменения статусов.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val checksumPrecheckRepository: ChecksumPrecheckRepository,
    private val fileUploadRepository: FileUploadRepository,
    private val serverRepository: ServerRepository,
    private val baseUrlProvider: BaseUrlProvider,
    private val syncStatusStore: SyncStatusStore,
    private val tokenStorage: TokenStorage,
    private val periodicSyncCoordinator: PeriodicSyncCoordinator,
    private val syncScheduler: SyncScheduler,
    private val clock: SyncClock
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "UploadWorker"
        /** Ключ outputData с причиной остановки без синхронизации. */
        const val STOP_REASON_KEY = "stop_reason"
        const val STOP_REASON_NO_SESSION = "no_session"
    }

    override suspend fun doWork(): Result {
        // Бюджет отсчитывается от начала прогона: ping, pre-check и upload расходуют его вместе.
        val budget = WorkBudget(clock)
        return try {
            runUpload(budget)
        } catch (e: CancellationException) {
            // Остановка worker системой: UPLOADING вернётся в PENDING_UPLOAD при следующем прогоне.
            throw e
        } catch (e: Exception) {
            // Не пробрасываем исключение наружу — даём WorkManager шанс повторить.
            Log.e(TAG, "Загрузка завершилась исключением", e)
            syncStatusStore.record(SyncStatus.ERROR)
            Result.retry()
        }
    }

    private suspend fun runUpload(budget: WorkBudget): Result {
        // Без пары авторизованные стадии гарантированно получат 401: до сетевых запросов останавливаем фон и не просим retry.
        if (tokenStorage.getTokens() == null) {
            Log.w(TAG, "Нет сессии — фоновая загрузка остановлена до входа")
            syncStatusStore.record(SyncStatus.ERROR)
            // Последнее действие прогона: reconcile без пары отменяет periodic Загрузчика; Поставщик продолжает работу.
            // Если это сам periodic, WorkManager остановит уже завершающийся прогон — повтора не будет.
            periodicSyncCoordinator.reconcile()
            return Result.success(workDataOf(STOP_REASON_KEY to STOP_REASON_NO_SESSION))
        }

        // Адрес берём из сохранённых настроек, а не из памяти: процесс мог быть запущен WorkManager без UI,
        // а пользователь — сменить адрес после прошлого прогона.
        val baseUrl = baseUrlProvider.reload()
        // Быстрая проверка доступности сервера через существующую тестовую ручку (GET /api/v1/test).
        // Если сервер недоступен — не гоняем pre-check и upload впустую в timeout, а сразу просим повтор.
        if (baseUrl.isBlank() || serverRepository.testConnection(baseUrl).isFailure) {
            Log.w(TAG, "Сервер недоступен — откладываем загрузку")
            syncStatusStore.record(SyncStatus.SERVER_UNAVAILABLE)
            return Result.retry()
        }

        // Upload читает исходные файлы: без общего доступа каждая строка ушла бы в постоянный FAILED(PERMISSION).
        // Фоновый worker не может запросить разрешение — очередь не трогаем, без retry.
        if (!applicationContext.hasMediaReadPermission()) {
            Log.w(TAG, "Нет полного доступа к фото — загрузка пропущена, очередь сохранена")
            return Result.success()
        }

        // Pre-check: CHECKSUM_READY → SYNCED (existing) или PENDING_UPLOAD (missing).
        val precheck = when (val outcome = checksumPrecheckRepository.runCameraPrecheck(budget)) {
            is ChecksumPrecheckOutcome.Success -> outcome.result
            is ChecksumPrecheckOutcome.Error -> {
                syncStatusStore.record(SyncStatus.ERROR)
                return Result.retry()
            }
        }

        // Upload: UPLOADING → PENDING_UPLOAD восстанавливается в начале стадии.
        // FAILED-записи считаем терминальными для текущего этапа.
        // Бюджет исчерпан на pre-check — upload в этом прогоне не начинается: стадии не чередуются.
        val upload: UploadResult?
        val retryNeeded: Boolean
        if (budget.isExhausted()) {
            upload = null
            retryNeeded = false
        } else when (val outcome = fileUploadRepository.uploadPendingCameraFiles(budget)) {
            is UploadOutcome.Success -> {
                upload = outcome.result
                // Осталась очередь из-за transient-ошибки → просим повтор.
                retryNeeded = outcome.result.leftPending > 0
            }
            is UploadOutcome.Error -> {
                upload = outcome.result
                // Прогон upload оборвался на transient-ошибке → повтор.
                retryNeeded = true
            }
        }

        if (retryNeeded) {
            // Осталась очередь / transient error на upload — WorkManager повторит.
            // Политика ошибок важнее продолжения: штатное продолжение здесь не регистрируется.
            syncStatusStore.record(SyncStatus.RETRY_SCHEDULED)
            return Result.retry()
        }

        // Очередь осталась (бюджет исчерпан или Поставщик добавил строки) — следующий прогон продолжит её
        // по состоянию Room. Без прогресса продолжение не ставится: остаток подхватят сигналы и periodic.
        // Регистрация подтверждается записью в базу WorkManager до возврата success; её сбой — ошибка прогона (retry).
        val progress = precheck.existing + precheck.pendingUpload + (upload?.attempted ?: 0)
        if (progress > 0 && fileUploadRepository.hasQueuedWork()) {
            syncScheduler.continueUpload(fromOneTimeRun = SyncScheduler.ONE_TIME_TAG in tags).await()
            Log.i(TAG, "Очередь Загрузчика не исчерпана — зарегистрировано продолжение")
        }
        syncStatusStore.record(SyncStatus.SUCCESS)
        return Result.success(buildOutput(precheck, upload))
    }

    /** Минимальная сводка по стадиям — для логов/диагностики через outputData. */
    private fun buildOutput(precheck: ChecksumPrecheckResult, upload: UploadResult?): Data = workDataOf(
        "precheck_checked" to precheck.checked,
        "upload_attempted" to (upload?.attempted ?: 0),
        "upload_succeeded" to (upload?.succeeded ?: 0),
        "upload_failed" to (upload?.failed ?: 0),
        "upload_left_pending" to (upload?.leftPending ?: 0)
    )
}

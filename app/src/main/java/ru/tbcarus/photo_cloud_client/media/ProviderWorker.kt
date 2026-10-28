package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Поставщик (A4.1): вся локальная обработка — restore CHECKSUM_IO → scan DCIM → SHA-256.
 * Доводит записи до CHECKSUM_READY или FAILED и сигналит Загрузчику после каждой готовой партии.
 * Хеширование ограничено бюджетом времени прогона; остаток очереди продолжает следующий прогон (A8.1).
 *
 * Не зависит от сети, адреса Server, токенов и состояния Загрузчика; продолжает работать после logout.
 */
@HiltWorker
class ProviderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val mediaFileRepository: MediaFileRepository,
    private val checksumRepository: ChecksumRepository,
    private val syncStatusStore: SyncStatusStore,
    private val periodicSyncCoordinator: PeriodicSyncCoordinator,
    private val syncScheduler: SyncScheduler,
    private val clock: SyncClock
) : CoroutineWorker(appContext, params) {

    private companion object {
        const val TAG = "ProviderWorker"
    }

    /** KEEP: работающий или ожидающий Загрузчик не дублируется — он сам дочитает очередь из Room. */
    private suspend fun signalUploader() {
        if (mediaFileRepository.hasReadyForUpload()) {
            periodicSyncCoordinator.requestUpload()
        }
    }

    override suspend fun doWork(): Result {
        // Бюджет отсчитывается от начала прогона: scan тоже его расходует.
        val budget = WorkBudget(clock)
        return try {
            runProvider(budget)
        } catch (e: CancellationException) {
            // Остановка worker системой: промежуточные результаты уже в Room, прерванное восстановит следующий прогон.
            throw e
        } catch (e: Exception) {
            // Не пробрасываем исключение наружу — даём WorkManager шанс повторить.
            Log.e(TAG, "Локальная обработка завершилась исключением", e)
            syncStatusStore.record(SyncStatus.ERROR)
            Result.retry()
        }
    }

    private suspend fun runProvider(budget: WorkBudget): Result {
        // Фоновый worker не может запросить runtime-разрешение — это не crash и не retry.
        if (!applicationContext.hasMediaReadPermission()) {
            Log.w(TAG, "Нет полного доступа к фото — локальная обработка пропущена")
            return Result.success()
        }

        // Наблюдатель MediaStore мог пропасть после сбоя — periodic Поставщика восстанавливает его.
        periodicSyncCoordinator.ensureMediaTrigger()

        // Auto-retry (Stage 5K): возвращаем в очередь временно упавшие (CHECKSUM_IO) записи,
        // у которых прошёл cooldown и не исчерпан лимит попыток. Один раз за прогон.
        val restored = checksumRepository.restoreRetryableFailed()
        if (restored > 0) Log.i(TAG, "Auto-retry: восстановлено retryable FAILED = $restored")

        val scan = when (val outcome = mediaFileRepository.scanImages()) {
            is ScanOutcome.Success -> outcome.result
            is ScanOutcome.PermissionDenied -> return Result.success()
            is ScanOutcome.Error -> {
                syncStatusStore.record(SyncStatus.ERROR)
                return Result.retry()
            }
        }

        // HASHING → PENDING восстанавливается в начале стадии; хэшируются только PENDING-записи.
        val checksum = when (val outcome = checksumRepository.computePendingChecksums(budget) { signalUploader() }) {
            is ChecksumOutcome.Success -> outcome.result
            is ChecksumOutcome.PermissionDenied -> return Result.success()
            is ChecksumOutcome.Error -> {
                syncStatusStore.record(SyncStatus.ERROR)
                return Result.retry()
            }
        }

        // Сигнал по состоянию Room, а не по «новым в этом прогоне»: потерянный ранее сигнал восстанавливается.
        signalUploader()

        // Бюджет исчерпан, а очередь осталась — следующий прогон продолжит её по состоянию Room.
        // Без прогресса продолжение не ставится: остаток подхватят события и periodic, бесконечной цепочки нет.
        // Регистрация подтверждается записью в базу WorkManager до возврата success; её сбой — ошибка прогона (retry).
        if (checksum.processed > 0 && checksumRepository.hasPending()) {
            syncScheduler.continueProvider(fromOneTimeRun = SyncScheduler.ONE_TIME_TAG in tags).await()
            Log.i(TAG, "Очередь хеширования не исчерпана — зарегистрировано продолжение Поставщика")
        }

        return Result.success(
            workDataOf(
                "scan_scanned" to scan.scanned,
                "scan_deleted_stale" to scan.deletedStale,
                "checksum_processed" to checksum.processed,
                "checksum_succeeded" to checksum.succeeded,
                "checksum_failed" to checksum.failed
            )
        )
    }
}

package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class ChecksumRepository @Inject constructor(
    private val mediaFileDao: MediaFileDao,
    @ApplicationContext private val context: Context
) {

    private val mutex = Mutex()

    private companion object {
        const val CHECKSUM_BATCH_SIZE = 100
        const val TAG = "ChecksumRepository"

        // Stage 5K: ограниченный auto-retry для временных (CHECKSUM_IO) ошибок.
        const val MAX_AUTO_RETRY_COUNT = 3
        const val RETRY_COOLDOWN_MS = 5 * 60 * 1000L
        const val RETRY_RESTORE_BATCH_SIZE = 500
    }

    /**
     * Возвращает в pipeline временно упавшие (CHECKSUM_IO) записи, у которых
     * не исчерпан лимит попыток и прошёл cooldown. Один снимок за вызов —
     * восстановленные записи становятся PENDING и в этом же прогоне повторно не выбираются
     * (см. требование Stage 5K: максимум один retry записи за проход worker'а).
     * @return число восстановленных записей.
     */
    suspend fun restoreRetryableFailed(): Int = withContext(Dispatchers.IO) {
        val retryBefore = System.currentTimeMillis() - RETRY_COOLDOWN_MS
        val batch = mediaFileDao.getRetryableFailed(
            reason = FailureReason.CHECKSUM_IO,
            maxRetryCount = MAX_AUTO_RETRY_COUNT,
            retryBefore = retryBefore,
            limit = RETRY_RESTORE_BATCH_SIZE
        )
        for (file in batch) {
            mediaFileDao.resetFailedForRetry(file.mediaStoreId)
        }
        batch.size
    }

    /**
     * Вычисляет SHA-256 для всех записей со статусом PENDING.
     * Запускает весь прогон под Mutex — исключает два параллельных запуска.
     * Прогресс не публикуется; per-file ошибки фиксируются статусом FAILED в БД.
     */
    suspend fun computePendingChecksums(): ChecksumOutcome {
        if (!context.hasMediaReadPermission()) {
            return ChecksumOutcome.PermissionDenied
        }

        return try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    runPipeline()
                }
            }
        } catch (e: CancellationException) {
            // Остановка worker — не ошибка стадии; HASHING вернётся в PENDING при следующем прогоне.
            throw e
        } catch (e: Exception) {
            ChecksumOutcome.Error(e.message ?: "Ошибка при вычислении checksum")
        }
    }

    private suspend fun runPipeline(): ChecksumOutcome.Success {
        // Возвращаем зависшие HASHING-записи после возможного падения приложения.
        mediaFileDao.resetStatus(
            sourceStatus = MediaFileStatus.HASHING,
            targetStatus = MediaFileStatus.PENDING
        )

        var processed = 0
        var succeeded = 0
        var failed    = 0

        while (true) {
            val batch = mediaFileDao.getByStatusOnce(MediaFileStatus.PENDING, CHECKSUM_BATCH_SIZE)
            if (batch.isEmpty()) break

            var progressed = false
            for (file in batch) {
                // Строку мог изменить или удалить параллельный scan — она вернётся в следующей выборке.
                if (mediaFileDao.updateStatusIfUnchanged(file, MediaFileStatus.PENDING, MediaFileStatus.HASHING) == 0) {
                    continue
                }
                progressed = true
                processed++

                // Результат применяется только к той же HASHING-версии; сброшенная scan строка снова станет PENDING.
                val failure: FailureReason? = try {
                    val stream = context.contentResolver.openInputStream(Uri.parse(file.uri))
                    if (stream == null) {
                        Log.w(TAG, "openInputStream вернул null, mediaStoreId=${file.mediaStoreId}")
                        FailureReason.FILE_NOT_FOUND
                    } else {
                        val checksum = stream.use { ChecksumUtil.sha256(it) }
                        if (mediaFileDao.updateChecksumIfUnchanged(file.mediaStoreId, checksum, file.size, file.lastModified) > 0) {
                            succeeded++
                        }
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    // TODO: позже различать per-file ошибки доступа и общий отзыв permission.
                    Log.w(TAG, "Ошибка доступа, mediaStoreId=${file.mediaStoreId}: ${e.message}")
                    FailureReason.PERMISSION
                } catch (e: FileNotFoundException) {
                    Log.w(TAG, "Файл не найден, mediaStoreId=${file.mediaStoreId}: ${e.message}")
                    FailureReason.FILE_NOT_FOUND
                } catch (e: IOException) {
                    // Временная IO-ошибка чтения — единственная retryable причина.
                    Log.w(TAG, "IO-ошибка checksum, mediaStoreId=${file.mediaStoreId}: ${e.message}")
                    FailureReason.CHECKSUM_IO
                } catch (e: Exception) {
                    Log.w(TAG, "Неизвестная ошибка checksum, mediaStoreId=${file.mediaStoreId}: ${e.message}")
                    FailureReason.UNKNOWN
                }

                if (failure != null &&
                    mediaFileDao.markFailedIfUnchanged(file, MediaFileStatus.HASHING, failure) > 0
                ) {
                    failed++
                }
            }
            // Ни одна строка не взята в работу (все изменились параллельно) — остаток обработает следующий прогон.
            if (!progressed) break
        }

        return ChecksumOutcome.Success(
            ChecksumResult(
                processed = processed,
                succeeded = succeeded,
                failed    = failed
            )
        )
    }
}

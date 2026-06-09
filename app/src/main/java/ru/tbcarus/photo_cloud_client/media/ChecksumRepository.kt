package ru.tbcarus.photo_cloud_client.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChecksumRepository @Inject constructor(
    private val mediaFileDao: MediaFileDao,
    @ApplicationContext private val context: Context
) {

    private val mutex = Mutex()

    private companion object {
        const val CHECKSUM_BATCH_SIZE = 100
        const val TAG = "ChecksumRepository"
    }

    /**
     * Вычисляет SHA-256 для всех записей со статусом PENDING.
     * Запускает весь прогон под Mutex — исключает два параллельных запуска.
     * Прогресс не публикуется; per-file ошибки фиксируются статусом FAILED в БД.
     */
    suspend fun computePendingChecksums(): ChecksumOutcome {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        // TODO: runtime-запрос разрешений будет добавлен на UI-этапе.
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            return ChecksumOutcome.PermissionDenied
        }

        return try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    runPipeline()
                }
            }
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

            for (file in batch) {
                mediaFileDao.updateStatus(file.mediaStoreId, MediaFileStatus.HASHING)
                processed++

                try {
                    val stream = context.contentResolver.openInputStream(Uri.parse(file.uri))
                    if (stream == null) {
                        Log.w(TAG, "openInputStream вернул null, mediaStoreId=${file.mediaStoreId}")
                        mediaFileDao.updateStatus(file.mediaStoreId, MediaFileStatus.FAILED)
                        failed++
                        continue
                    }

                    val checksum = stream.use { ChecksumUtil.sha256(it) }
                    mediaFileDao.updateChecksum(file.mediaStoreId, checksum, MediaFileStatus.CHECKSUM_READY)
                    succeeded++

                } catch (e: SecurityException) {
                    // TODO: позже различать per-file ошибки доступа и общий отзыв permission.
                    Log.w(TAG, "Ошибка доступа, mediaStoreId=${file.mediaStoreId}: ${e.message}")
                    mediaFileDao.updateStatus(file.mediaStoreId, MediaFileStatus.FAILED)
                    failed++
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка при расчёте checksum, mediaStoreId=${file.mediaStoreId}: ${e.message}")
                    mediaFileDao.updateStatus(file.mediaStoreId, MediaFileStatus.FAILED)
                    failed++
                }
            }
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

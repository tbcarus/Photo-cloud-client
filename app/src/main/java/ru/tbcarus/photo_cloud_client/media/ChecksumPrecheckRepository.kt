package ru.tbcarus.photo_cloud_client.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.api.models.ChecksumExistsRequest
import ru.tbcarus.photo_cloud_client.core.network.ApiErrorParser
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-check слой этапа 5C.
 * Берёт локальные CHECKSUM_READY записи, спрашивает сервер про дубли в папке CAMERA
 * (правило дубля: userId + folderId + checksum) и раскладывает статусы:
 *  - existing → SYNCED (файл уже на сервере, upload не нужен);
 *  - missing  → PENDING_UPLOAD (нужен upload).
 *
 * TODO: serverFileId для SYNCED-файлов без id будет заполнен будущим list-sync.
 * TODO: когда появится поддержка нескольких целевых папок,
 * pre-check должен принимать folderId явно, а не только CAMERA.
 */
@Singleton
class ChecksumPrecheckRepository @Inject constructor(
    private val mediaFileDao: MediaFileDao,
    private val apiServiceFactory: ApiServiceFactory,
    private val folderRepository: FolderRepository
) {

    private val mutex = Mutex()

    private companion object {
        const val PRECHECK_BATCH_SIZE = 500
        const val SQLITE_UPDATE_CHUNK_SIZE = 500
    }

    /** Запускает pre-check по папке CAMERA. Один прогон одновременно (Mutex). */
    suspend fun runCameraPrecheck(): ChecksumPrecheckOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val folder = folderRepository.resolveCameraFolderId()) {
                is CameraFolderOutcome.Error -> ChecksumPrecheckOutcome.Error(folder.message)
                is CameraFolderOutcome.NotCreatedYet -> moveAllReadyToPendingUpload()
                is CameraFolderOutcome.Success -> runPrecheckForFolder(folder.folderId)
            }
        }
    }

    /**
     * CAMERA ещё не создана на сервере, значит в целевой папке нет файлов.
     * Pre-check не нужен: все готовые checksum переходят в очередь upload.
     */
    private suspend fun moveAllReadyToPendingUpload(): ChecksumPrecheckOutcome {
        var pendingUpload = 0
        while (true) {
            val batch = mediaFileDao.getByStatusWithChecksumOnce(
                MediaFileStatus.CHECKSUM_READY,
                PRECHECK_BATCH_SIZE
            )
            if (batch.isEmpty()) break

            val ids = batch.map { it.mediaStoreId }
            updateStatusChunked(ids, MediaFileStatus.PENDING_UPLOAD)
            pendingUpload += ids.size
        }

        return ChecksumPrecheckOutcome.Success(
            ChecksumPrecheckResult(
                checked = pendingUpload,
                existing = 0,
                pendingUpload = pendingUpload,
                unchanged = 0
            )
        )
    }

    private suspend fun runPrecheckForFolder(folderId: Long): ChecksumPrecheckOutcome {
        var existingTotal = 0
        var pendingTotal  = 0
        var unchangedTotal = 0

        try {
            while (true) {
                val batch = mediaFileDao.getByStatusWithChecksumOnce(
                    MediaFileStatus.CHECKSUM_READY,
                    PRECHECK_BATCH_SIZE
                )
                if (batch.isEmpty()) break

                // checksum (lowercase) → список mediaStoreId (один checksum может быть у нескольких фото)
                val checksumToIds: Map<String, List<Long>> = batch
                    .groupBy({ it.checksum!!.lowercase() }, { it.mediaStoreId })

                val checksums = checksumToIds.keys.toList() // уже дедуплицированы (ключи map)
                if (checksums.isEmpty()) break // защитно: не должно случиться

                val request = ChecksumExistsRequest(folderId = folderId, checksums = checksums)
                val response = apiServiceFactory.authFileService().checksumsExist(request).execute()

                if (!response.isSuccessful) {
                    // Уже обновлённые предыдущие батчи остаются; текущий не трогаем.
                    return ChecksumPrecheckOutcome.Error(ApiErrorParser.parse(response))
                }
                val body = response.body()
                    ?: return ChecksumPrecheckOutcome.Error("Empty checksums/exists response")

                val existingSet = body.existing.mapTo(HashSet()) { it.lowercase() }
                val missingSet  = body.missing.mapTo(HashSet()) { it.lowercase() }

                val existingIds = ArrayList<Long>()
                val missingIds  = ArrayList<Long>()
                var unchangedInBatch = 0

                for ((checksum, ids) in checksumToIds) {
                    when {
                        existingSet.contains(checksum) -> existingIds.addAll(ids)
                        missingSet.contains(checksum)  -> missingIds.addAll(ids)
                        else -> unchangedInBatch += ids.size // остаётся CHECKSUM_READY
                    }
                }

                // existing → SYNCED. serverFileId намеренно НЕ заполняется (см. TODO в шапке класса).
                if (existingIds.isNotEmpty()) {
                    updateStatusChunked(existingIds, MediaFileStatus.SYNCED)
                }
                if (missingIds.isNotEmpty()) {
                    updateStatusChunked(missingIds, MediaFileStatus.PENDING_UPLOAD)
                }

                existingTotal += existingIds.size
                pendingTotal  += missingIds.size

                // Если ни одна запись не сменила статус, оставшиеся CHECKSUM_READY будут
                // возвращаться снова и снова — выходим, чтобы не зациклиться.
                if (existingIds.isEmpty() && missingIds.isEmpty()) {
                    unchangedTotal = unchangedInBatch
                    break
                }
            }
        } catch (e: Exception) {
            return ChecksumPrecheckOutcome.Error(e.message ?: "Ошибка при pre-check")
        }

        return ChecksumPrecheckOutcome.Success(
            ChecksumPrecheckResult(
                checked = existingTotal + pendingTotal + unchangedTotal,
                existing = existingTotal,
                pendingUpload = pendingTotal,
                unchanged = unchangedTotal
            )
        )
    }

    /** Обновляет статус по mediaStoreId чанками — лимит bind-параметров SQLite (~999). */
    private suspend fun updateStatusChunked(ids: List<Long>, status: MediaFileStatus) {
        ids.chunked(SQLITE_UPDATE_CHUNK_SIZE).forEach { chunk ->
            mediaFileDao.updateStatusByMediaStoreIds(chunk, status)
        }
    }
}

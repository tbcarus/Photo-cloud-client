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
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pre-check слой этапа 5C.
 * Берёт локальные CHECKSUM_READY записи, спрашивает сервер про дубли в папке CAMERA
 * (правило дубля: userId + folderId + checksum) и раскладывает статусы:
 *  - existing → SYNCED (файл уже на сервере, upload не нужен);
 *  - missing  → PENDING_UPLOAD (нужен upload).
 * Переход применяется условно: строку, которую Поставщик успел сбросить или обновить, ответ не перезаписывает.
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

    /**
     * Запускает pre-check по папке CAMERA партиями FIFO по mediaStoreId. Один прогон одновременно (Mutex).
     * Перед каждым запросом проверяет [budget]: исчерпан — новый запрос не начинается,
     * оставшиеся CHECKSUM_READY проверит следующий прогон.
     */
    suspend fun runCameraPrecheck(budget: WorkBudget = WorkBudget.UNLIMITED): ChecksumPrecheckOutcome =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                when (val folder = folderRepository.resolveCameraFolderId()) {
                    is CameraFolderOutcome.Error -> ChecksumPrecheckOutcome.Error(folder.message)
                    is CameraFolderOutcome.NotCreatedYet -> moveAllReadyToPendingUpload()
                    is CameraFolderOutcome.Success -> runPrecheckForFolder(folder.folderId, budget)
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
                SyncConfig.PRECHECK_BATCH_SIZE
            )
            if (batch.isEmpty()) break

            val moved = moveReady(batch, MediaFileStatus.PENDING_UPLOAD)
            pendingUpload += moved
            // Ни одна строка не перешла — остаток обработает следующий прогон, без зацикливания.
            if (moved == 0) break
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

    private suspend fun runPrecheckForFolder(folderId: Long, budget: WorkBudget): ChecksumPrecheckOutcome {
        var existingTotal = 0
        var pendingTotal  = 0
        var unchangedTotal = 0

        try {
            while (!budget.isExhausted()) {
                val batch = mediaFileDao.getByStatusWithChecksumOnce(
                    MediaFileStatus.CHECKSUM_READY,
                    SyncConfig.PRECHECK_BATCH_SIZE
                )
                if (batch.isEmpty()) break

                // checksum (lowercase) → прочитанные строки (один checksum может быть у нескольких фото)
                val checksumToFiles: Map<String, List<MediaFile>> = batch
                    .groupBy { it.checksum!!.lowercase() }

                val checksums = checksumToFiles.keys.toList() // уже дедуплицированы (ключи map)
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

                val existingFiles = ArrayList<MediaFile>()
                val missingFiles  = ArrayList<MediaFile>()
                var unchangedInBatch = 0

                for ((checksum, files) in checksumToFiles) {
                    when {
                        existingSet.contains(checksum) -> existingFiles.addAll(files)
                        missingSet.contains(checksum)  -> missingFiles.addAll(files)
                        else -> unchangedInBatch += files.size // остаётся CHECKSUM_READY
                    }
                }

                // existing → SYNCED. serverFileId намеренно НЕ заполняется (см. TODO в шапке класса).
                val existingMoved = moveReady(existingFiles, MediaFileStatus.SYNCED)
                val missingMoved  = moveReady(missingFiles, MediaFileStatus.PENDING_UPLOAD)

                existingTotal += existingMoved
                pendingTotal  += missingMoved

                // Если ни одна запись не сменила статус, оставшиеся CHECKSUM_READY будут
                // возвращаться снова и снова — выходим, чтобы не зациклиться.
                if (existingMoved == 0 && missingMoved == 0) {
                    unchangedTotal = unchangedInBatch
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
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

    /** Условно переводит прочитанные CHECKSUM_READY-строки в [status]; возвращает число применённых переходов. */
    private suspend fun moveReady(files: List<MediaFile>, status: MediaFileStatus): Int =
        files.count { mediaFileDao.updateStatusIfUnchanged(it, MediaFileStatus.CHECKSUM_READY, status) > 0 }
}

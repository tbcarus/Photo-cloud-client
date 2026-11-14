package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.io.IOException

/**
 * Pre-check слой этапа 5C.
 * Берёт локальные CHECKSUM_READY записи, спрашивает сервер про дубли в папке CAMERA
 * (правило дубля: userId + folderId + checksum) и раскладывает статусы:
 *  - existing → SYNCED (файл уже на сервере, upload не нужен);
 *  - missing  → PENDING_UPLOAD (нужен upload).
 * Переход применяется условно: строку, которую Поставщик успел сбросить или обновить, ответ не перезаписывает.
 *
 * TODO: serverFileId для SYNCED-файлов без id будет заполнен будущим list-sync.
 */
@Singleton
class ChecksumPrecheckRepository @Inject constructor(
    private val mediaFileDao: MediaFileDao,
    private val apiServiceFactory: ApiServiceFactory
) {

    @Inject lateinit var automaticBackupNetwork: AutomaticBackupNetwork
    @Inject @ApplicationContext lateinit var appContext: Context

    private suspend fun fileService() = if (::automaticBackupNetwork.isInitialized) {
        val network = automaticBackupNetwork.current() ?: throw IOException("Нет разрешённой сети для Camera backup")
        apiServiceFactory.authFileService(network)
    } else apiServiceFactory.authFileService()

    private val mutex = Mutex()

    /**
     * Запускает pre-check по папке [folderId] партиями FIFO по mediaStoreId. Один прогон одновременно (Mutex).
     * Перед каждым запросом проверяет [budget] и паузу ручной сверки [isPaused]: новый запрос не начинается,
     * уже отправленный завершается; оставшиеся CHECKSUM_READY проверит следующий прогон.
     */
    suspend fun runCameraPrecheck(
        folderId: Long,
        budget: WorkBudget = WorkBudget.UNLIMITED,
        isPaused: suspend () -> Boolean = { false }
    ): ChecksumPrecheckOutcome =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runPrecheckForFolder(folderId, budget, isPaused)
            }
        }

    private suspend fun runPrecheckForFolder(
        folderId: Long,
        budget: WorkBudget,
        isPaused: suspend () -> Boolean
    ): ChecksumPrecheckOutcome {
        var existingTotal = 0
        var pendingTotal  = 0
        var unchangedTotal = 0

        try {
            // Пауза читается из Room перед каждой партией: сверка могла начаться во время прошлого запроса.
            while (!budget.isExhausted() && !isPaused()) {
                val batch = if (::appContext.isInitialized) {
                    mediaFileDao.getReadableByStatusWithChecksumOnce(
                        MediaFileStatus.CHECKSUM_READY, SyncConfig.PRECHECK_BATCH_SIZE,
                        appContext.hasMediaReadPermission(), appContext.hasVideoReadPermission()
                    )
                } else mediaFileDao.getByStatusWithChecksumOnce(
                    MediaFileStatus.CHECKSUM_READY, SyncConfig.PRECHECK_BATCH_SIZE
                )
                if (batch.isEmpty()) break

                // checksum (lowercase) → прочитанные строки (один checksum может быть у нескольких фото)
                val checksumToFiles: Map<String, List<MediaFile>> = batch
                    .groupBy { it.checksum!!.lowercase() }

                val checksums = checksumToFiles.keys.toList() // уже дедуплицированы (ключи map)
                if (checksums.isEmpty()) break // защитно: не должно случиться

                val request = ChecksumExistsRequest(folderId = folderId, checksums = checksums)
                val response = fileService().checksumsExist(request).execute()

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

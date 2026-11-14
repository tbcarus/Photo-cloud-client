package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.core.network.ApiErrorParser
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Upload-слой этапа 5D.
 * Берёт локальные записи со статусом PENDING_UPLOAD и грузит их в системную папку CAMERA:
 *  - success с checksum ответа, равным локальному → markUploaded(mediaStoreId, fileItem.id) → SYNCED + serverFileId;
 *  - success с другим checksum (Q-02) → PENDING к Поставщику без checksum, после лимита восстановлений — FAILED;
 *  - permanent error → FAILED;
 *  - transient error → возврат файла в PENDING_UPLOAD и остановка текущего прогона.
 * Все переходы условные (A4.1): строку, которую Поставщик сбросил или обновил во время отправки,
 * поздний результат не перезаписывает.
 *
 * Репозиторий не зависит от UI/lifecycle — пригоден для будущего WorkManager.
 */
@Singleton
class FileUploadRepository @Inject constructor(
    private val mediaFileDao: MediaFileDao,
    private val apiServiceFactory: ApiServiceFactory,
    private val streamOpener: MediaStreamOpener
) {

    @Inject lateinit var resumableUpload: ResumableUpload
    @ApplicationContext @Inject lateinit var appContext: Context
    @Inject lateinit var automaticBackupNetwork: AutomaticBackupNetwork

    private val mutex = Mutex()

    private companion object {
        const val TAG = "FileUploadRepository"

        // Q-02: автоматических восстановлений после несовпадения checksum ответа; следующее несовпадение — FAILED.
        const val MAX_MISMATCH_RECOVERIES = 3
        val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    }

    /**
     * Грузит PENDING_UPLOAD-фото в папку CAMERA [folderId] партиями FIFO по mediaStoreId. Один прогон одновременно (Mutex).
     * Перед каждым файлом проверяет [budget] и паузу ручной сверки [isPaused]: новый файл не начинается,
     * начатая отправка не прерывается; остаток очереди отправит следующий прогон.
     */
    suspend fun uploadPendingCameraFiles(
        folderId: Long,
        budget: WorkBudget = WorkBudget.UNLIMITED,
        isPaused: suspend () -> Boolean = { false }
    ): UploadOutcome =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runUpload(folderId, budget, isPaused)
            }
        }

    /** Есть ли работа для Загрузчика: CHECKSUM_READY с checksum, PENDING_UPLOAD или UPLOADING. */
    suspend fun hasQueuedWork(): Boolean = mediaFileDao.countReadyForUpload() > 0

    /**
     * Дождаться окончания идущего прогона upload другого Загрузчика (Q-01.3). На паузе сверки он дошлёт текущий
     * файл и следующий не возьмёт, поэтому ожидание ограничено одним файлом.
     */
    suspend fun awaitIdle() {
        mutex.withLock { }
    }

    private suspend fun runUpload(folderId: Long, budget: WorkBudget, isPaused: suspend () -> Boolean): UploadOutcome {
        // Возвращаем зависшие UPLOADING-записи после возможного падения приложения.
        mediaFileDao.resetStatus(
            sourceStatus = MediaFileStatus.UPLOADING,
            targetStatus = MediaFileStatus.PENDING_UPLOAD
        )

        if (::resumableUpload.isInitialized) resumableUpload.cleanupOrphans(budget)

        val folderIdPart = folderId.toString().toRequestBody("text/plain".toMediaTypeOrNull())

        var attempted = 0
        var succeeded = 0
        var failed = 0
        var leftPending = 0
        var rehashQueued = 0

        var stopped = false
        while (!stopped) {
            val batch = mediaFileDao.getReadableByStatusInIdOrder(MediaFileStatus.PENDING_UPLOAD, SyncConfig.UPLOAD_BATCH_SIZE,
                !::appContext.isInitialized || appContext.hasMediaReadPermission(),
                !::appContext.isInitialized || appContext.hasVideoReadPermission())
            if (batch.isEmpty()) break

            var progressed = false
            for (file in batch) {
                if (budget.isExhausted()) {
                    stopped = true
                    break
                }
                // Пауза ручной сверки: уже отправленный файл завершён, следующий не берётся.
                if (isPaused()) {
                    Log.i(TAG, "Camera upload приостановлен ручной сверкой — очередь сохранена")
                    stopped = true
                    break
                }
                // Защитно: на 5D грузим только фото.
                if (!file.mimeType.startsWith("image/") && !file.mimeType.startsWith("video/")) {
                    if (mediaFileDao.markFailedIfUnchanged(file, MediaFileStatus.PENDING_UPLOAD, FailureReason.NOT_IMAGE) > 0) {
                        progressed = true
                        attempted++
                        failed++
                    }
                    continue
                }

                val sizeClass = SyncConfig.uploadSizeClass(file.size)
                val sizeReason = when (sizeClass) {
                    UploadSizeClass.UNKNOWN -> FailureReason.SIZE_UNKNOWN
                    UploadSizeClass.TOO_LARGE -> FailureReason.FILE_TOO_LARGE
                    else -> null
                }
                if (sizeReason != null) {
                    if (mediaFileDao.markFailedIfUnchanged(file, MediaFileStatus.PENDING_UPLOAD, sizeReason) > 0) {
                        progressed = true
                        attempted++
                        failed++
                    }
                    continue
                }

                // Проверяем доступ к локальному файлу заранее — так отличаем
                // постоянную ошибку файла от сетевой ошибки во время upload.
                val openFailure = if (sizeClass == UploadSizeClass.MULTIPART) openFailureReason(file) else null
                if (openFailure != null) {
                    if (mediaFileDao.markFailedIfUnchanged(file, MediaFileStatus.PENDING_UPLOAD, openFailure) > 0) {
                        progressed = true
                        attempted++
                        failed++
                    }
                    continue
                }

                // Строку мог сбросить Поставщик после выборки — тогда этот файл в текущем прогоне не отправляем.
                if (mediaFileDao.updateStatusIfUnchanged(file, MediaFileStatus.PENDING_UPLOAD, MediaFileStatus.UPLOADING) == 0) {
                    continue
                }
                progressed = true
                attempted++

                if (sizeClass == UploadSizeClass.RESUMABLE) {
                    val outcome = if (::resumableUpload.isInitialized) {
                        resumableUpload.camera(file, folderId, budget, isPaused)
                    } else ResumableResult.Retry
                    when (outcome) {
                        is ResumableResult.Complete -> {
                            val result = outcome.file
                            val accepted = result.checksum?.takeIf { SHA256_HEX.matches(it) }?.lowercase()
                            val id = result.id
                            if (id == null || id <= 0 || accepted == null) {
                                mediaFileDao.updateStatusIfUnchanged(file, MediaFileStatus.UPLOADING, MediaFileStatus.PENDING_UPLOAD)
                                return UploadOutcome.Error("Некорректная квитанция resumable upload", UploadResult(attempted, succeeded, failed, 1, rehashQueued))
                            }
                            if (accepted == file.checksum?.lowercase()) {
                                if (mediaFileDao.markUploadedIfUnchanged(file.uri, id, file.checksum, file.size, file.lastModified) > 0) succeeded++
                                resumableUpload.forgetBySource(file)
                            } else {
                                if (mediaFileDao.applyUploadMismatchIfUnchanged(file.uri, file.checksum, file.size, file.lastModified,
                                        file.uploadMismatchCount, MAX_MISMATCH_RECOVERIES, System.currentTimeMillis()) > 0) {
                                    if (file.uploadMismatchCount < MAX_MISMATCH_RECOVERIES) rehashQueued++ else failed++
                                }
                                resumableUpload.forgetBySource(file)
                            }
                        }
                        ResumableResult.ChecksumMismatch -> {
                            if (mediaFileDao.applyUploadMismatchIfUnchanged(file.uri, file.checksum, file.size, file.lastModified,
                                    file.uploadMismatchCount, MAX_MISMATCH_RECOVERIES, System.currentTimeMillis()) > 0) {
                                if (file.uploadMismatchCount < MAX_MISMATCH_RECOVERIES) rehashQueued++ else failed++
                            }
                        }
                        ResumableResult.SourceChanged -> {
                            if (mediaFileDao.resetChangedSource(file.uri, file.checksum, file.size, file.lastModified) > 0) rehashQueued++
                        }
                        ResumableResult.Progress -> {
                            mediaFileDao.updateStatusIfUnchanged(file, MediaFileStatus.UPLOADING, MediaFileStatus.PENDING_UPLOAD)
                            return UploadOutcome.Success(UploadResult(attempted, succeeded, failed, 0, rehashQueued))
                        }
                        ResumableResult.Retry, ResumableResult.ManagedTree -> {
                            mediaFileDao.updateStatusIfUnchanged(file, MediaFileStatus.UPLOADING, MediaFileStatus.PENDING_UPLOAD)
                            return UploadOutcome.Error("Resumable upload ожидает повторного прогона", UploadResult(attempted, succeeded, failed, 1, rehashQueued))
                        }
                        is ResumableResult.Failed -> {
                            if (mediaFileDao.markFailedIfUnchanged(file, MediaFileStatus.UPLOADING, outcome.reason) > 0) failed++
                        }
                    }
                    continue
                }

                val response = try {
                    sendWithSingleRetry(file, folderIdPart, budget)
                } catch (e: IOException) {
                    // Повтор не удался или не начат → transient, возвращаем файл в очередь; повтор — WorkManager.
                    Log.w(TAG, "Сетевая ошибка upload, файл возвращён в очередь, mediaStoreId=${file.mediaStoreId}", e)
                    mediaFileDao.updateStatusIfUnchanged(file, MediaFileStatus.UPLOADING, MediaFileStatus.PENDING_UPLOAD)
                    leftPending++
                    return UploadOutcome.Error(
                        e.message ?: "Сетевая ошибка при upload",
                        UploadResult(attempted, succeeded, failed, leftPending, rehashQueued)
                    )
                }

                if (response.isSuccessful) {
                    val body = response.body()
                    val id = body?.id
                    // checksum ответа — SHA-256 байтов, принятых Server в этом (последнем) запросе, в том числе duplicate.
                    val accepted = body?.checksum?.takeIf { SHA256_HEX.matches(it) }?.lowercase()
                    if (id == null || id <= 0 || accepted == null) {
                        // 200, но тело пустое, без id или без корректного checksum → transient: изменение файла не доказано.
                        mediaFileDao.updateStatusIfUnchanged(file, MediaFileStatus.UPLOADING, MediaFileStatus.PENDING_UPLOAD)
                        leftPending++
                        return UploadOutcome.Error(
                            if (accepted == null && id != null && id > 0) "Upload вернул некорректный checksum"
                            else "Upload вернул пустой или некорректный id",
                            UploadResult(attempted, succeeded, failed, leftPending, rehashQueued)
                        )
                    }
                    if (accepted == file.checksum?.lowercase()) {
                        // Идемпотентный ответ сервера (существующий FileItem) — тоже success.
                        // Если фото изменилось во время отправки, строка остаётся за Поставщиком: новая версия пройдёт заново.
                        if (mediaFileDao.markUploadedIfUnchanged(
                                file.uri, id, file.checksum, file.size, file.lastModified
                            ) > 0
                        ) {
                            succeeded++
                        }
                    } else {
                        // Server сохранил другое содержимое: повторный hash и pre-check найдут его без новой передачи.
                        Log.w(TAG, "checksum ответа upload не совпал с локальным, mediaStoreId=${file.mediaStoreId}")
                        if (mediaFileDao.applyUploadMismatchIfUnchanged(
                                file.uri, file.checksum, file.size, file.lastModified,
                                file.uploadMismatchCount, MAX_MISMATCH_RECOVERIES, System.currentTimeMillis()
                            ) > 0
                        ) {
                            if (file.uploadMismatchCount < MAX_MISMATCH_RECOVERIES) rehashQueued++ else failed++
                        }
                    }
                } else {
                    val code = response.code()
                    val message = ApiErrorParser.parse(response)
                    val permanentReason = permanentHttpReason(code)
                    if (permanentReason != null) {
                        if (mediaFileDao.markFailedIfUnchanged(file, MediaFileStatus.UPLOADING, permanentReason) > 0) {
                            failed++
                        }
                        continue
                    } else {
                        // 401 после неудачного refresh / 404 устаревшего folderId / 5xx → transient;
                        // повтор — WorkManager, следующий прогон заново получит ID CAMERA.
                        mediaFileDao.updateStatusIfUnchanged(file, MediaFileStatus.UPLOADING, MediaFileStatus.PENDING_UPLOAD)
                        leftPending++
                        return UploadOutcome.Error(
                            message,
                            UploadResult(attempted, succeeded, failed, leftPending, rehashQueued)
                        )
                    }
                }
            }
            // Ни одна строка не взята в работу (все изменились параллельно) — остаток обработает следующий прогон.
            if (!progressed) break
        }

        return UploadOutcome.Success(UploadResult(attempted, succeeded, failed, leftPending, rehashQueued))
    }

    /**
     * Отправка файла с одной немедленной повторной попыткой при IOException: ответ мог потеряться уже после того,
     * как Server сохранил файл (обрыв при штатном закрытии соединения). Повтор безопасен — Server идемпотентен
     * по checksum, тело заново читается из content://uri. HTTP-ответы не повторяются. Повтор не начинается,
     * если Worker остановлен или бюджет исчерпан; второй IOException уходит вызывающему.
     */
    private suspend fun sendWithSingleRetry(
        file: MediaFile,
        folderIdPart: RequestBody,
        budget: WorkBudget
    ): Response<FileItemDto> {
        val filePart = createFilePart(file)
        try {
            return fileService().uploadFile(filePart, folderIdPart).execute()
        } catch (e: IOException) {
            Log.w(TAG, "Сетевая ошибка upload, mediaStoreId=${file.mediaStoreId}: ${e.javaClass.name}: ${e.message}")
            currentCoroutineContext().ensureActive()
            if (budget.isExhausted()) throw e
        }
        // Retrofit Call одноразовый — для повтора нужен новый.
        return fileService().uploadFile(filePart, folderIdPart).execute()
    }

    private suspend fun fileService() = if (::automaticBackupNetwork.isInitialized) {
        val network = automaticBackupNetwork.current() ?: throw IOException("Нет разрешённой сети для Camera backup")
        apiServiceFactory.authFileService(network)
    } else apiServiceFactory.authFileService()

    /**
     * null — поток к файлу открывается; иначе конкретная permanent-причина FAILED.
     * Различаем отсутствие файла и отзыв доступа для дальнейшего показа в UI.
     */
    private fun openFailureReason(file: MediaFile): FailureReason? = try {
        val uri = Uri.parse(file.uri)
        val opened = (if (file.collection == MediaCollection.IMAGE) streamOpener.open(uri)
                      else streamOpener.open(uri, file.collection))?.use { true } ?: false
        if (opened) null else FailureReason.FILE_NOT_FOUND
    } catch (e: FileNotFoundException) {
        FailureReason.FILE_NOT_FOUND
    } catch (e: SecurityException) {
        FailureReason.PERMISSION
    }

    private fun createFilePart(file: MediaFile): MultipartBody.Part {
        val uri = Uri.parse(file.uri)
        val body = ContentUriRequestBody(streamOpener, uri, file.mimeType, file.size, file.collection)
        val filename = file.displayName.ifBlank { "file_${file.mediaStoreId}" }
        return MultipartBody.Part.createFormData("file", filename, body)
    }

    /** Постоянные HTTP-ошибки upload: повтор не поможет. null — код не считаем permanent. */
    private fun permanentHttpReason(code: Int): FailureReason? = when (code) {
        400 -> FailureReason.HTTP_400
        409 -> FailureReason.HTTP_409
        413 -> FailureReason.HTTP_413
        else -> null
    }
}

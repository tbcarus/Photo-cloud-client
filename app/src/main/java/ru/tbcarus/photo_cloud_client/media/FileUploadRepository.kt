package ru.tbcarus.photo_cloud_client.media

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

/**
 * Upload-слой этапа 5D.
 * Берёт локальные записи со статусом PENDING_UPLOAD и грузит их в системную папку CAMERA:
 *  - success → markUploaded(mediaStoreId, fileItem.id) → SYNCED + serverFileId;
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
    private val folderRepository: FolderRepository,
    private val streamOpener: MediaStreamOpener
) {

    private val mutex = Mutex()

    private companion object {
        const val TAG = "FileUploadRepository"
    }

    /**
     * Грузит PENDING_UPLOAD-фото в CAMERA партиями FIFO по mediaStoreId. Один прогон одновременно (Mutex).
     * Перед каждым файлом проверяет [budget]: исчерпан — новый файл не начинается, начатая отправка
     * не прерывается; остаток очереди отправит следующий прогон.
     */
    suspend fun uploadPendingCameraFiles(budget: WorkBudget = WorkBudget.UNLIMITED): UploadOutcome =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runUpload(budget)
            }
        }

    /** Есть ли работа для Загрузчика: CHECKSUM_READY с checksum, PENDING_UPLOAD или UPLOADING. */
    suspend fun hasQueuedWork(): Boolean = mediaFileDao.countReadyForUpload() > 0

    private suspend fun runUpload(budget: WorkBudget): UploadOutcome {
        // Возвращаем зависшие UPLOADING-записи после возможного падения приложения.
        mediaFileDao.resetStatus(
            sourceStatus = MediaFileStatus.UPLOADING,
            targetStatus = MediaFileStatus.PENDING_UPLOAD
        )

        // Если CAMERA не удалось получить, не блокируем upload:
        // сервер сам направит IMAGE в CAMERA при upload без folderId.
        val folderId: Long? = when (val folder = folderRepository.resolveCameraFolderId()) {
            is CameraFolderOutcome.Success -> folder.folderId
            CameraFolderOutcome.NotCreatedYet -> null
            is CameraFolderOutcome.Error -> null
        }
        val folderIdPart = createFolderIdPart(folderId)

        var attempted = 0
        var succeeded = 0
        var failed = 0
        var leftPending = 0

        var budgetExhausted = false
        while (!budgetExhausted) {
            val batch = mediaFileDao.getByStatusInIdOrder(MediaFileStatus.PENDING_UPLOAD, SyncConfig.UPLOAD_BATCH_SIZE)
            if (batch.isEmpty()) break

            var progressed = false
            for (file in batch) {
                if (budget.isExhausted()) {
                    budgetExhausted = true
                    break
                }
                // Защитно: на 5D грузим только фото.
                if (!file.mimeType.startsWith("image/")) {
                    if (mediaFileDao.markFailedIfUnchanged(file, MediaFileStatus.PENDING_UPLOAD, FailureReason.NOT_IMAGE) > 0) {
                        progressed = true
                        attempted++
                        failed++
                    }
                    continue
                }

                // Проверяем доступ к локальному файлу заранее — так отличаем
                // постоянную ошибку файла от сетевой ошибки во время upload.
                val openFailure = openFailureReason(file.uri)
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

                val response = try {
                    sendWithSingleRetry(file, folderIdPart, budget)
                } catch (e: IOException) {
                    // Повтор не удался или не начат → transient, возвращаем файл в очередь; повтор — WorkManager.
                    Log.w(TAG, "Сетевая ошибка upload, файл возвращён в очередь, mediaStoreId=${file.mediaStoreId}", e)
                    mediaFileDao.updateStatusIfUnchanged(file, MediaFileStatus.UPLOADING, MediaFileStatus.PENDING_UPLOAD)
                    leftPending++
                    return UploadOutcome.Error(
                        e.message ?: "Сетевая ошибка при upload",
                        UploadResult(attempted, succeeded, failed, leftPending)
                    )
                }

                if (response.isSuccessful) {
                    val id = response.body()?.id
                    if (id != null && id > 0) {
                        // Идемпотентный ответ сервера (существующий FileItem) — тоже success.
                        // Если фото изменилось во время отправки, строка остаётся за Поставщиком: новая версия пройдёт заново.
                        if (mediaFileDao.markUploadedIfUnchanged(
                                file.mediaStoreId, id, file.checksum, file.size, file.lastModified
                            ) > 0
                        ) {
                            succeeded++
                        }
                    } else {
                        // 200, но тело пустое/без id → transient.
                        mediaFileDao.updateStatusIfUnchanged(file, MediaFileStatus.UPLOADING, MediaFileStatus.PENDING_UPLOAD)
                        leftPending++
                        return UploadOutcome.Error(
                            "Upload вернул пустой или некорректный id",
                            UploadResult(attempted, succeeded, failed, leftPending)
                        )
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
                        // 401 после неудачного refresh / 404 folder / 5xx → transient; повтор — WorkManager.
                        mediaFileDao.updateStatusIfUnchanged(file, MediaFileStatus.UPLOADING, MediaFileStatus.PENDING_UPLOAD)
                        leftPending++
                        return UploadOutcome.Error(
                            message,
                            UploadResult(attempted, succeeded, failed, leftPending)
                        )
                    }
                }
            }
            // Ни одна строка не взята в работу (все изменились параллельно) — остаток обработает следующий прогон.
            if (!progressed) break
        }

        return UploadOutcome.Success(UploadResult(attempted, succeeded, failed, leftPending))
    }

    /**
     * Отправка файла с одной немедленной повторной попыткой при IOException: ответ мог потеряться уже после того,
     * как Server сохранил файл (обрыв при штатном закрытии соединения). Повтор безопасен — Server идемпотентен
     * по checksum, тело заново читается из content://uri. HTTP-ответы не повторяются. Повтор не начинается,
     * если Worker остановлен или бюджет исчерпан; второй IOException уходит вызывающему.
     */
    private suspend fun sendWithSingleRetry(
        file: MediaFile,
        folderIdPart: RequestBody?,
        budget: WorkBudget
    ): Response<FileItemDto> {
        val filePart = createFilePart(file)
        try {
            return apiServiceFactory.authFileService().uploadFile(filePart, folderIdPart).execute()
        } catch (e: IOException) {
            Log.w(TAG, "Сетевая ошибка upload, mediaStoreId=${file.mediaStoreId}: ${e.javaClass.name}: ${e.message}")
            currentCoroutineContext().ensureActive()
            if (budget.isExhausted()) throw e
        }
        // Retrofit Call одноразовый — для повтора нужен новый.
        return apiServiceFactory.authFileService().uploadFile(filePart, folderIdPart).execute()
    }

    /**
     * null — поток к файлу открывается; иначе конкретная permanent-причина FAILED.
     * Различаем отсутствие файла и отзыв доступа для дальнейшего показа в UI.
     */
    private fun openFailureReason(uriString: String): FailureReason? = try {
        val opened = streamOpener.open(Uri.parse(uriString))?.use { true } ?: false
        if (opened) null else FailureReason.FILE_NOT_FOUND
    } catch (e: FileNotFoundException) {
        FailureReason.FILE_NOT_FOUND
    } catch (e: SecurityException) {
        FailureReason.PERMISSION
    }

    private fun createFilePart(file: MediaFile): MultipartBody.Part {
        val uri = Uri.parse(file.uri)
        val body = ContentUriRequestBody(streamOpener, uri, file.mimeType, file.size)
        val filename = file.displayName.ifBlank { "file_${file.mediaStoreId}" }
        return MultipartBody.Part.createFormData("file", filename, body)
    }

    private fun createFolderIdPart(folderId: Long?): RequestBody? {
        if (folderId == null) return null
        return folderId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
    }

    /** Постоянные HTTP-ошибки upload: повтор не поможет. null — код не считаем permanent. */
    private fun permanentHttpReason(code: Int): FailureReason? = when (code) {
        400 -> FailureReason.HTTP_400
        409 -> FailureReason.HTTP_409
        413 -> FailureReason.HTTP_413
        else -> null
    }
}

package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
 *
 * Репозиторий не зависит от UI/lifecycle — пригоден для будущего WorkManager.
 */
@Singleton
class FileUploadRepository @Inject constructor(
    private val mediaFileDao: MediaFileDao,
    private val apiServiceFactory: ApiServiceFactory,
    private val folderRepository: FolderRepository,
    @ApplicationContext private val context: Context
) {

    private val mutex = Mutex()

    private companion object {
        const val UPLOAD_BATCH_SIZE = 20
    }

    /** Грузит все PENDING_UPLOAD-фото в CAMERA. Один прогон одновременно (Mutex). */
    suspend fun uploadPendingCameraFiles(): UploadOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            runUpload()
        }
    }

    private suspend fun runUpload(): UploadOutcome {
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

        while (true) {
            val batch = mediaFileDao.getByStatusOnce(MediaFileStatus.PENDING_UPLOAD, UPLOAD_BATCH_SIZE)
            if (batch.isEmpty()) break

            for (file in batch) {
                attempted++

                // Защитно: на 5D грузим только фото.
                if (!file.mimeType.startsWith("image/")) {
                    mediaFileDao.updateStatus(file.mediaStoreId, MediaFileStatus.FAILED)
                    failed++
                    continue
                }

                // Проверяем доступ к локальному файлу заранее — так отличаем
                // постоянную ошибку файла от сетевой ошибки во время upload.
                if (!canOpen(file.uri)) {
                    mediaFileDao.updateStatus(file.mediaStoreId, MediaFileStatus.FAILED)
                    failed++
                    continue
                }

                mediaFileDao.updateStatus(file.mediaStoreId, MediaFileStatus.UPLOADING)

                val filePart = createFilePart(file)
                val response = try {
                    apiServiceFactory.authFileService().uploadFile(filePart, folderIdPart).execute()
                } catch (e: IOException) {
                    // TODO: retry/backoff для transient upload-ошибок будет реализован в WorkManager.
                    // Сетевая ошибка/timeout/обрыв тела → transient, возвращаем файл в очередь.
                    mediaFileDao.updateStatus(file.mediaStoreId, MediaFileStatus.PENDING_UPLOAD)
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
                        mediaFileDao.markUploaded(file.mediaStoreId, id)
                        succeeded++
                    } else {
                        // 200, но тело пустое/без id → transient.
                        mediaFileDao.updateStatus(file.mediaStoreId, MediaFileStatus.PENDING_UPLOAD)
                        leftPending++
                        return UploadOutcome.Error(
                            "Upload вернул пустой или некорректный id",
                            UploadResult(attempted, succeeded, failed, leftPending)
                        )
                    }
                } else {
                    val code = response.code()
                    val message = ApiErrorParser.parse(response)
                    if (isPermanentHttpError(code)) {
                        mediaFileDao.updateStatus(file.mediaStoreId, MediaFileStatus.FAILED)
                        failed++
                        continue
                    } else {
                        // TODO: retry/backoff для transient upload-ошибок будет реализован в WorkManager.
                        // 401 после неудачного refresh / 404 folder / 5xx → transient.
                        mediaFileDao.updateStatus(file.mediaStoreId, MediaFileStatus.PENDING_UPLOAD)
                        leftPending++
                        return UploadOutcome.Error(
                            message,
                            UploadResult(attempted, succeeded, failed, leftPending)
                        )
                    }
                }
            }
        }

        return UploadOutcome.Success(UploadResult(attempted, succeeded, failed, leftPending))
    }

    /** true, если поток к файлу открывается; локальные ошибки доступа считаем permanent. */
    private fun canOpen(uriString: String): Boolean = try {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { true } ?: false
    } catch (e: FileNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun createFilePart(file: MediaFile): MultipartBody.Part {
        val uri = Uri.parse(file.uri)
        val body = ContentUriRequestBody(context, uri, file.mimeType, file.size)
        val filename = file.displayName.ifBlank { "file_${file.mediaStoreId}" }
        return MultipartBody.Part.createFormData("file", filename, body)
    }

    private fun createFolderIdPart(folderId: Long?): RequestBody? {
        if (folderId == null) return null
        return folderId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
    }

    /** Постоянные HTTP-ошибки upload: повтор не поможет. */
    private fun isPermanentHttpError(code: Int): Boolean =
        code == 400 || code == 409 || code == 413
}

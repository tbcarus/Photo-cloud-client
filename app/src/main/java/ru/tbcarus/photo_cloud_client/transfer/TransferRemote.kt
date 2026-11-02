package ru.tbcarus.photo_cloud_client.transfer

import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.awaitResponse
import ru.tbcarus.photo_cloud_client.api.models.CreateFolderRequest
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FilePageDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.core.network.ApiErrorParser
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Итог запроса ручной передачи. */
sealed interface RemoteResult<out T> {
    data class Success<T>(val value: T) : RemoteResult<T>
    data class HttpError(val code: Int, val message: String) : RemoteResult<Nothing>
    data class NetworkError(val cause: IOException) : RemoteResult<Nothing>
}

/** Скачанный файл: размер и SHA-256 посчитаны при записи. */
data class DownloadedFile(val size: Long, val sha256: String)

/** Запросы ручной передачи через общий авторизованный клиент: отмена корутины закрывает HTTP-вызов. */
@Singleton
open class TransferRemote @Inject constructor(
    private val apiServiceFactory: ApiServiceFactory
) {

    companion object {
        const val FILES_PAGE_SIZE = 200
        private const val BUFFER_SIZE = 64 * 1024
    }

    open suspend fun children(folderId: Long): RemoteResult<List<FolderDto>> =
        request { apiServiceFactory.authFolderService().getChildren(folderId) }

    open suspend fun files(folderId: Long, page: Int): RemoteResult<FilePageDto> =
        request { apiServiceFactory.authFileService().getFiles(folderId, page, FILES_PAGE_SIZE, null) }

    open suspend fun createFolder(parentId: Long, name: String): RemoteResult<FolderDto> =
        request { apiServiceFactory.authFolderService().createFolder(CreateFolderRequest(parentId, name)) }

    open suspend fun upload(folderId: Long, name: String, body: RequestBody): RemoteResult<FileItemDto> {
        val file = MultipartBody.Part.createFormData("file", name, body)
        val folder = folderId.toString().toRequestBody("text/plain".toMediaType())
        return request { apiServiceFactory.authFileService().uploadFile(file, folder) }
    }

    /** Пишет тело ответа в [target] потоком, считая размер и SHA-256. */
    open suspend fun download(fileId: Long, target: File, onProgress: (Long) -> Unit): RemoteResult<DownloadedFile> =
        withContext(Dispatchers.IO) {
            val call = try {
                apiServiceFactory.authFileService().download(fileId)
            } catch (e: IllegalStateException) {
                return@withContext RemoteResult.NetworkError(IOException(e.message, e))
            }
            try {
                val response = call.awaitResponse()
                val body = response.body()
                if (!response.isSuccessful || body == null) return@withContext httpError(response)
                coroutineScope {
                    // Чтение блокирующее: отмена закрывает вызов, и заблокированное чтение завершается ошибкой.
                    val closer = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            call.cancel()
                        }
                    }
                    try {
                        RemoteResult.Success(body.use { copyWithDigest(it.byteStream(), target, onProgress) })
                    } finally {
                        closer.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ensureActive()
                RemoteResult.NetworkError(e)
            }
        }

    private suspend fun copyWithDigest(
        input: java.io.InputStream,
        target: File,
        onProgress: (Long) -> Unit
    ): DownloadedFile = coroutineScope {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        target.outputStream().use { output ->
            while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
                size += read
                onProgress(size)
            }
        }
        DownloadedFile(size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    // awaitResponse при отмене корутины прерывает и сам HTTP-запрос.
    private suspend fun <T : Any> request(call: () -> Call<T>): RemoteResult<T> = withContext(Dispatchers.IO) {
        try {
            val response = call().awaitResponse()
            val body = response.body()
            if (response.isSuccessful && body != null) RemoteResult.Success(body) else httpError(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MalformedJsonException) {
            RemoteResult.HttpError(0, "Некорректный ответ сервера")
        } catch (e: IOException) {
            RemoteResult.NetworkError(e)
        } catch (e: IllegalStateException) {
            // Адрес Server сброшен: дальнейшая передача невозможна, как и без связи.
            RemoteResult.NetworkError(IOException(e.message, e))
        } catch (e: RuntimeException) {
            RemoteResult.HttpError(0, e.message ?: "Некорректный ответ сервера")
        }
    }

    private fun httpError(response: Response<*>): RemoteResult.HttpError =
        if (response.isSuccessful) {
            RemoteResult.HttpError(response.code(), "Пустой ответ сервера")
        } else {
            RemoteResult.HttpError(response.code(), ApiErrorParser.parse(response))
        }
}

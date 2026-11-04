package ru.tbcarus.photo_cloud_client.backup

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.awaitResponse
import ru.tbcarus.photo_cloud_client.api.models.CreateFolderRequest
import ru.tbcarus.photo_cloud_client.api.models.ErrorResponseDto
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FilePageDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.api.models.RenameFileRequest
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Ответ Server для автоматического резервирования: код ErrorResponse нужен, чтобы отличить FOLDER_MANAGED от CONFLICT. */
sealed interface BackupResult<out T> {
    data class Ok<T>(val value: T) : BackupResult<T>
    data class Http(val code: Int, val errorCode: String?, val message: String) : BackupResult<Nothing>
    data class Network(val cause: IOException) : BackupResult<Nothing>
}

val BackupResult.Http.isFolderManaged: Boolean get() = code == 409 && errorCode == FolderBackupRemote.FOLDER_MANAGED

@Singleton
open class FolderBackupRemote @Inject constructor(
    private val apiServiceFactory: ApiServiceFactory
) {

    companion object {
        const val FOLDER_MANAGED = "FOLDER_MANAGED"
        const val FILES_PAGE_SIZE = 200
        private val gson = Gson()
    }

    open suspend fun root(): BackupResult<FolderDto> = request { apiServiceFactory.authFolderService().getRoot() }

    open suspend fun children(folderId: Long): BackupResult<List<FolderDto>> =
        request { apiServiceFactory.authFolderService().getChildren(folderId) }

    open suspend fun files(folderId: Long, page: Int): BackupResult<FilePageDto> =
        request { apiServiceFactory.authFileService().getFiles(folderId, page, FILES_PAGE_SIZE, null) }

    open suspend fun file(id: Long): BackupResult<FileItemDto> = request { apiServiceFactory.authFileService().getFile(id) }

    open suspend fun rename(id: Long, name: String, backupSource: String): BackupResult<FileItemDto> =
        request { apiServiceFactory.authFileService().renameFile(id, RenameFileRequest(name, backupSource)) }

    open suspend fun createFolder(parentId: Long, name: String, backupSource: String): BackupResult<FolderDto> =
        request { apiServiceFactory.authFolderService().createFolder(CreateFolderRequest(parentId, name, backupSource)) }

    open suspend fun upload(folderId: Long, name: String, body: RequestBody, backupSource: String): BackupResult<FileItemDto> {
        val file = MultipartBody.Part.createFormData("file", name, body)
        val text = "text/plain".toMediaType()
        return request {
            apiServiceFactory.authFileService()
                .uploadBackupFile(file, folderId.toString().toRequestBody(text), backupSource.toRequestBody(text))
        }
    }

    // awaitResponse при отмене корутины прерывает и сам HTTP-запрос.
    private suspend fun <T : Any> request(call: () -> Call<T>): BackupResult<T> = withContext(Dispatchers.IO) {
        try {
            val response = call().awaitResponse()
            val body = response.body()
            if (response.isSuccessful && body != null) BackupResult.Ok(body) else httpError(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MalformedJsonException) {
            BackupResult.Http(0, null, "Некорректный ответ сервера")
        } catch (e: IOException) {
            BackupResult.Network(e)
        } catch (e: IllegalStateException) {
            // Адрес Server сброшен: работа невозможна, как и без связи.
            BackupResult.Network(IOException(e.message, e))
        } catch (e: RuntimeException) {
            BackupResult.Http(0, null, e.message ?: "Некорректный ответ сервера")
        }
    }

    // Тело ошибки читается один раз: из него нужны и код, и сообщение.
    private fun httpError(response: Response<*>): BackupResult.Http {
        if (response.isSuccessful) return BackupResult.Http(response.code(), null, "Пустой ответ сервера")
        val raw = try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }
        val dto = try {
            raw?.let { gson.fromJson(it, ErrorResponseDto::class.java) }
        } catch (e: JsonSyntaxException) {
            null
        }
        val message = dto?.message?.takeIf { it.isNotBlank() } ?: raw?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code()}"
        return BackupResult.Http(response.code(), dto?.code, message)
    }
}

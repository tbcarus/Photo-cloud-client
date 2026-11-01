package ru.tbcarus.photo_cloud_client.library

import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.awaitResponse
import ru.tbcarus.photo_cloud_client.api.models.FilePageDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.core.network.ApiErrorParser
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Итог запроса к серверной библиотеке. */
sealed interface LibraryResult<out T> {
    data class Success<T>(val value: T) : LibraryResult<T>

    sealed interface Failure : LibraryResult<Nothing>
    data object Unauthorized : Failure
    data object NotFound : Failure
    data class NetworkError(val message: String) : Failure
    data class ServerError(val message: String) : Failure
}

/** Чтение серверной библиотеки: только GET, папки не создаёт. С локальной очередью backup не связан. */
@Singleton
open class LibraryRepository @Inject constructor(
    private val apiServiceFactory: ApiServiceFactory
) {

    companion object {
        const val PAGE_SIZE = 50
        private const val SORT_BY_NAME = "name"
    }

    open suspend fun getRoot(): LibraryResult<FolderDto> =
        request { apiServiceFactory.authFolderService().getRoot() }

    open suspend fun getChildren(folderId: Long): LibraryResult<List<FolderDto>> =
        request { apiServiceFactory.authFolderService().getChildren(folderId) }

    /** Camera остаётся в порядке Server по дате, обычные папки идут по имени. */
    open suspend fun getFiles(folder: FolderDto, page: Int): LibraryResult<FilePageDto> {
        val sort = if (folder.isCamera) null else SORT_BY_NAME
        return request { apiServiceFactory.authFileService().getFiles(folder.id, page, PAGE_SIZE, sort) }
    }

    // awaitResponse при отмене корутины прерывает и сам HTTP-запрос.
    private suspend fun <T : Any> request(call: () -> Call<T>): LibraryResult<T> = withContext(Dispatchers.IO) {
        try {
            val response = call().awaitResponse()
            val body = response.body()
            when {
                response.isSuccessful && body != null -> LibraryResult.Success(body)
                response.isSuccessful -> LibraryResult.ServerError("Пустой ответ сервера")
                response.code() == 401 || response.code() == 403 -> LibraryResult.Unauthorized
                response.code() == 404 -> LibraryResult.NotFound
                else -> LibraryResult.ServerError(ApiErrorParser.parse(response))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MalformedJsonException) {
            // Наследник IOException, но ответ получен: связь исправна.
            LibraryResult.ServerError("Некорректный ответ сервера")
        } catch (e: IOException) {
            LibraryResult.NetworkError(e.message ?: "Сервер не отвечает")
        } catch (e: RuntimeException) {
            LibraryResult.ServerError(e.message ?: "Некорректный ответ сервера")
        }
    }
}

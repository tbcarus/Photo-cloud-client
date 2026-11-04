package ru.tbcarus.photo_cloud_client.library

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Response
import retrofit2.awaitResponse
import ru.tbcarus.photo_cloud_client.api.models.CopyFileRequest
import ru.tbcarus.photo_cloud_client.api.models.CreateFolderRequest
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FilePageDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.api.models.MoveFileRequest
import ru.tbcarus.photo_cloud_client.api.models.MoveFolderRequest
import ru.tbcarus.photo_cloud_client.api.models.RenameFileRequest
import ru.tbcarus.photo_cloud_client.api.models.RenameFolderRequest
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

    // Отказы изменения библиотеки; запросы чтения отвечают ими как ServerError.
    /** 409: имя занято файлом или папкой либо зарезервировано. */
    data object NameTaken : Failure
    /** 409 FOLDER_MANAGED: объект в дереве активного источника. */
    data object Managed : Failure
    /** 400: Server отклонил имя или операцию над объектом. */
    data class Rejected(val message: String) : Failure
    /** Server ответил 2xx, но тело не разобрано: операция могла выполниться. Только для move и copy. */
    data class UnknownResult(val message: String) : Failure
}

/**
 * Серверная библиотека: чтение и ручные изменения пользователя (создание папки, переименование, перемещение,
 * копирование файла).
 * backupSource не передаётся: это запросы обычного пользователя, а не резервирования. С локальной очередью backup не связан.
 */
@Singleton
open class LibraryRepository @Inject constructor(
    private val apiServiceFactory: ApiServiceFactory
) {

    companion object {
        const val PAGE_SIZE = 50
        private const val SORT_BY_NAME = "name"
        private const val FOLDER_MANAGED = "FOLDER_MANAGED"
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

    open suspend fun createFolder(parentId: Long, name: String): LibraryResult<FolderDto> =
        request(change = true) { apiServiceFactory.authFolderService().createFolder(CreateFolderRequest(parentId, name)) }

    open suspend fun renameFolder(folderId: Long, name: String): LibraryResult<FolderDto> =
        request(change = true) { apiServiceFactory.authFolderService().renameFolder(folderId, RenameFolderRequest(name)) }

    open suspend fun renameFile(fileId: Long, name: String): LibraryResult<FileItemDto> =
        request(change = true) { apiServiceFactory.authFileService().renameFile(fileId, RenameFileRequest(name)) }

    open suspend fun moveFile(fileId: Long, targetFolderId: Long): LibraryResult<FileItemDto> =
        request(change = true, uncertainBody = true) {
            apiServiceFactory.authFileService().moveFile(fileId, MoveFileRequest(targetFolderId))
        }

    open suspend fun copyFile(fileId: Long, targetFolderId: Long, name: String): LibraryResult<FileItemDto> =
        request(change = true, uncertainBody = true) {
            apiServiceFactory.authFileService().copyFile(fileId, CopyFileRequest(targetFolderId, name))
        }

    open suspend fun moveFolder(folderId: Long, targetParentId: Long): LibraryResult<FolderDto> =
        request(change = true, uncertainBody = true) {
            apiServiceFactory.authFolderService().moveFolder(folderId, MoveFolderRequest(targetParentId))
        }

    // awaitResponse при отмене корутины прерывает и сам HTTP-запрос.
    // uncertainBody: Retrofit разбирает только тело 2xx, поэтому ошибка разбора означает, что Server операцию принял.
    private suspend fun <T : Any> request(
        change: Boolean = false,
        uncertainBody: Boolean = false,
        call: () -> Call<T>
    ): LibraryResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val response = call().awaitResponse()
                val body = response.body()
                when {
                    response.isSuccessful && body != null -> LibraryResult.Success(body)
                    response.isSuccessful -> unreadable(uncertainBody, "Пустой ответ сервера")
                    response.code() == 401 || response.code() == 403 -> LibraryResult.Unauthorized
                    response.code() == 404 -> LibraryResult.NotFound
                    else -> failure(response, change)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MalformedJsonException) {
                // Наследник IOException, но ответ получен: связь исправна.
                unreadable(uncertainBody, "Некорректный ответ сервера")
            } catch (e: JsonParseException) {
                unreadable(uncertainBody, e.message ?: "Некорректный ответ сервера")
            } catch (e: IOException) {
                LibraryResult.NetworkError(e.message ?: "Сервер не отвечает")
            } catch (e: RuntimeException) {
                LibraryResult.ServerError(e.message ?: "Некорректный ответ сервера")
            }
        }

    private fun unreadable(uncertainBody: Boolean, message: String): LibraryResult.Failure =
        if (uncertainBody) LibraryResult.UnknownResult(message) else LibraryResult.ServerError(message)

    // Код и текст ошибки берутся из одного чтения тела.
    private fun failure(response: Response<*>, change: Boolean): LibraryResult.Failure {
        val error = ApiErrorParser.read(response)
        return when {
            change && response.code() == 409 && error.code == FOLDER_MANAGED -> LibraryResult.Managed
            change && response.code() == 409 -> LibraryResult.NameTaken
            change && response.code() == 400 -> LibraryResult.Rejected(error.message)
            else -> LibraryResult.ServerError(error.message)
        }
    }
}

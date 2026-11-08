package ru.tbcarus.photo_cloud_client.library

import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.awaitResponse
import ru.tbcarus.photo_cloud_client.api.models.CopyFileRequest
import ru.tbcarus.photo_cloud_client.api.models.CreateFolderRequest
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FilePageDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.api.models.GrantShareRequest
import ru.tbcarus.photo_cloud_client.api.models.ShareDto
import ru.tbcarus.photo_cloud_client.api.models.SharePageDto
import ru.tbcarus.photo_cloud_client.api.models.MoveFileRequest
import ru.tbcarus.photo_cloud_client.api.models.MoveFolderRequest
import ru.tbcarus.photo_cloud_client.api.models.RenameFileRequest
import ru.tbcarus.photo_cloud_client.api.models.RenameFolderRequest
import ru.tbcarus.photo_cloud_client.api.models.SharedPageDto
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
    /** 400 SHARE_RECIPIENT_UNAVAILABLE: выдача отклонена целиком; адрес → причина Server. */
    data class RecipientsRejected(val rejected: Map<String, String>) : Failure
}

/**
 * Серверная библиотека: чтение и ручные изменения пользователя (создание папки, переименование, перемещение,
 * копирование файла, удаление).
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
        private const val SHARE_RECIPIENT_UNAVAILABLE = "SHARE_RECIPIENT_UNAVAILABLE"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        // Только для тела изменения срока: явный null снимает срок. Общий Gson Retrofit null-поля не пишет.
        private val nullAwareGson = GsonBuilder().serializeNulls().create()
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

    // Успех — любой 2xx: тело 204 не разбирается. 409 без FOLDER_MANAGED у удаления — не имя, а занятый объект.
    open suspend fun deleteFile(fileId: Long): LibraryResult<Unit> =
        send(change = true, nameConflict = false, call = { apiServiceFactory.authFileService().deleteFile(fileId) }) {
            LibraryResult.Success(Unit)
        }

    open suspend fun deleteFolder(folderId: Long): LibraryResult<Unit> =
        send(change = true, nameConflict = false, call = { apiServiceFactory.authFolderService().deleteFolder(folderId) }) {
            LibraryResult.Success(Unit)
        }

    /** «Доступно мне»: страница прямых USER-доступов текущего пользователя, тем же размером страницы, что у файлов. */
    open suspend fun getSharedWithMe(page: Int): LibraryResult<SharedPageDto> =
        request { apiServiceFactory.authShareService().getSharedWithMe(page, PAGE_SIZE) }

    /** Отказ от своего доступа: 404 — доступа уже нет (отозван, истёк или удалён другим запросом). */
    open suspend fun removeShared(shareId: Long): LibraryResult<Unit> =
        send(call = { apiServiceFactory.authShareService().removeShared(shareId) }) { LibraryResult.Success(Unit) }

    /** Выдача USER-доступа владельцем: все адреса или никто. 400 с отклонёнными адресами — [LibraryResult.RecipientsRejected]. */
    open suspend fun grantShare(request: GrantShareRequest): LibraryResult<List<ShareDto>> =
        request(change = true) { apiServiceFactory.authShareService().grant(request) }

    /** Выданные текущим пользователем доступы, с фильтром по одному ресурсу или без него. */
    open suspend fun getShares(fileId: Long?, folderId: Long?, page: Int): LibraryResult<SharePageDto> =
        request { apiServiceFactory.authShareService().getShares(fileId, folderId, page, PAGE_SIZE) }

    /** [expiresAt] — ISO-8601 со смещением; null отправляется явным {"expiresAt":null} и делает доступ бессрочным. */
    open suspend fun updateShareExpiry(shareId: Long, expiresAt: String?): LibraryResult<ShareDto> {
        val body = JsonObject().apply {
            if (expiresAt == null) add("expiresAt", JsonNull.INSTANCE) else addProperty("expiresAt", expiresAt)
        }
        val json = nullAwareGson.toJson(body).toRequestBody(JSON)
        return request(change = true) { apiServiceFactory.authShareService().updateExpiry(shareId, json) }
    }

    /** Отзыв одного доступа; 404 — его уже нет. */
    open suspend fun revokeShare(shareId: Long): LibraryResult<Unit> =
        send(change = true, call = { apiServiceFactory.authShareService().revoke(shareId) }) { LibraryResult.Success(Unit) }

    /** Отзыв всех USER-доступов одного ресурса; ресурс без доступов — тоже успех. */
    open suspend fun revokeAllShares(fileId: Long?, folderId: Long?): LibraryResult<Unit> =
        send(change = true, call = { apiServiceFactory.authShareService().revokeAll(fileId, folderId) }) {
            LibraryResult.Success(Unit)
        }

    // uncertainBody: Retrofit разбирает только тело 2xx, поэтому ошибка разбора означает, что Server операцию принял.
    private suspend fun <T : Any> request(
        change: Boolean = false,
        uncertainBody: Boolean = false,
        call: () -> Call<T>
    ): LibraryResult<T> = send(change, uncertainBody, call = call) { body ->
        if (body != null) LibraryResult.Success(body) else unreadable(uncertainBody, "Пустой ответ сервера")
    }

    // awaitResponse при отмене корутины прерывает и сам HTTP-запрос.
    private suspend fun <T, R> send(
        change: Boolean = false,
        uncertainBody: Boolean = false,
        nameConflict: Boolean = true,
        call: () -> Call<T>,
        success: (T?) -> LibraryResult<R>
    ): LibraryResult<R> =
        withContext(Dispatchers.IO) {
            try {
                val response = call().awaitResponse()
                when {
                    response.isSuccessful -> success(response.body())
                    response.code() == 401 || response.code() == 403 -> LibraryResult.Unauthorized
                    response.code() == 404 -> LibraryResult.NotFound
                    else -> failure(response, change, nameConflict)
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
    private fun failure(response: Response<*>, change: Boolean, nameConflict: Boolean): LibraryResult.Failure {
        val error = ApiErrorParser.read(response)
        return when {
            change && response.code() == 400 && error.code == SHARE_RECIPIENT_UNAVAILABLE ->
                LibraryResult.RecipientsRejected(error.fieldErrors.orEmpty())
            change && response.code() == 409 && error.code == FOLDER_MANAGED -> LibraryResult.Managed
            change && response.code() == 409 && nameConflict -> LibraryResult.NameTaken
            change && response.code() == 409 -> LibraryResult.Rejected(error.message)
            change && response.code() == 400 -> LibraryResult.Rejected(error.message)
            else -> LibraryResult.ServerError(error.message)
        }
    }
}

package ru.tbcarus.photo_cloud_client.backup

import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.awaitResponse
import ru.tbcarus.photo_cloud_client.api.models.CreateBackupSourceRequest
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.core.network.ApiErrorParser
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Итог запроса регистрации: различает окончательный отказ и неизвестный исход. */
sealed interface RegistrationOutcome {
    data class Registered(val folder: FolderDto) : RegistrationOutcome

    /** Server ответил 400 или 409: источник с этим UUID и именем не создан. */
    data class Rejected(val code: Int, val message: String) : RegistrationOutcome

    /** 401/403 после refresh: запрос не выполнен от имени пользователя. */
    data object Unauthorized : RegistrationOutcome

    /** Ответа нет либо он непригоден: Server мог успеть создать папку. */
    data class Unknown(val message: String) : RegistrationOutcome
}

/** Итог DELETE /folders/{id}/backup: отключённым источник считается только по подтверждению Server. */
sealed interface DisableOutcome {
    data object Disabled : DisableOutcome

    /** 404: папки нет в аккаунте — отключение не подтверждено. */
    data object NotFound : DisableOutcome

    /** 400 и прочие 4xx: папка не корень источника либо запрос отклонён. */
    data class Rejected(val message: String) : DisableOutcome

    data object Unauthorized : DisableOutcome

    /** Нет ответа или 5xx: Server мог успеть отключить, повтор того же DELETE безопасен. */
    data class Unknown(val message: String) : DisableOutcome
}

@Singleton
open class BackupSourceRemote @Inject constructor(
    private val apiServiceFactory: ApiServiceFactory
) {

    open suspend fun disable(folderId: Long): DisableOutcome = withContext(Dispatchers.IO) {
        try {
            val response = apiServiceFactory.authFolderService().disableBackupSource(folderId).awaitResponse()
            when {
                response.isSuccessful -> DisableOutcome.Disabled
                response.code() == 401 || response.code() == 403 -> DisableOutcome.Unauthorized
                response.code() == 404 -> DisableOutcome.NotFound
                response.code() in 400..499 -> DisableOutcome.Rejected(ApiErrorParser.parse(response))
                else -> DisableOutcome.Unknown("Ошибка сервера: ${ApiErrorParser.parse(response)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DisableOutcome.Unknown("Нет связи с сервером")
        } catch (e: IllegalStateException) {
            DisableOutcome.Unknown(e.message ?: "Адрес сервера не задан")
        } catch (e: RuntimeException) {
            DisableOutcome.Unknown(e.message ?: "Некорректный ответ сервера")
        }
    }

    open suspend fun register(name: String, sourceUuid: String): RegistrationOutcome = withContext(Dispatchers.IO) {
        try {
            val response = apiServiceFactory.authFolderService()
                .registerBackupSource(CreateBackupSourceRequest(name, sourceUuid))
                .awaitResponse()
            val body = response.body()
            when {
                response.isSuccessful && body != null -> RegistrationOutcome.Registered(body)
                response.isSuccessful -> RegistrationOutcome.Unknown("Пустой ответ сервера")
                response.code() == 400 || response.code() == 409 ->
                    RegistrationOutcome.Rejected(response.code(), ApiErrorParser.parse(response))
                response.code() == 401 || response.code() == 403 -> RegistrationOutcome.Unauthorized
                else -> RegistrationOutcome.Unknown("Ошибка сервера: ${ApiErrorParser.parse(response)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MalformedJsonException) {
            RegistrationOutcome.Unknown("Некорректный ответ сервера")
        } catch (e: IOException) {
            RegistrationOutcome.Unknown("Нет связи с сервером")
        } catch (e: IllegalStateException) {
            // Адрес Server сброшен до отправки.
            RegistrationOutcome.Unknown(e.message ?: "Адрес сервера не задан")
        } catch (e: RuntimeException) {
            RegistrationOutcome.Unknown(e.message ?: "Некорректный ответ сервера")
        }
    }
}

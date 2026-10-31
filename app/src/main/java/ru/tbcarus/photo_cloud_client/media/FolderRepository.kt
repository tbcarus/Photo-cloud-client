package ru.tbcarus.photo_cloud_client.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.core.network.ApiErrorParser
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import javax.inject.Inject
import javax.inject.Singleton

/** Результат получения системной папки CAMERA на сервере. */
sealed interface CameraFolderOutcome {
    data class Success(val folderId: Long) : CameraFolderOutcome
    data class Error(val message: String) : CameraFolderOutcome
}

@Singleton
class FolderRepository @Inject constructor(
    private val apiServiceFactory: ApiServiceFactory
) {

    /**
     * Получает ID CAMERA; сервер создаёт папку, если её ещё нет. Запрос идемпотентен.
     * ID живёт только в пределах прогона: устаревший заменит запрос следующего прогона.
     */
    suspend fun resolveCameraFolderId(): CameraFolderOutcome = withContext(Dispatchers.IO) {
        try {
            val response = apiServiceFactory.authFolderService().getOrCreateCamera().execute()
            if (!response.isSuccessful) {
                return@withContext CameraFolderOutcome.Error(ApiErrorParser.parse(response))
            }
            val folderId = response.body()?.id
                ?: return@withContext CameraFolderOutcome.Error("Empty Camera folder response")
            CameraFolderOutcome.Success(folderId)
        } catch (e: Exception) {
            CameraFolderOutcome.Error(e.message ?: "Ошибка при получении папки CAMERA")
        }
    }
}

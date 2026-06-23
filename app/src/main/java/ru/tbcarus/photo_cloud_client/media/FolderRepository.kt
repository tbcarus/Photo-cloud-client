package ru.tbcarus.photo_cloud_client.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.core.network.ApiErrorParser
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Результат поиска системной папки CAMERA на сервере.
 * Отсутствие CAMERA — не ошибка: сервер создаёт её лениво при первом upload.
 */
sealed interface CameraFolderOutcome {
    data class Success(val folderId: Long) : CameraFolderOutcome
    data object NotCreatedYet : CameraFolderOutcome
    data class Error(val message: String) : CameraFolderOutcome
}

@Singleton
class FolderRepository @Inject constructor(
    private val apiServiceFactory: ApiServiceFactory
) {

    private companion object {
        const val CAMERA_FOLDER_TYPE = "CAMERA"
    }

    /**
     * Резолвит folderId системной папки CAMERA через folder API:
     * root → children → поиск по folderType == "CAMERA".
     */
    suspend fun resolveCameraFolderId(): CameraFolderOutcome = withContext(Dispatchers.IO) {
        try {
            val rootResp = apiServiceFactory.authFolderService().getRoot().execute()
            if (!rootResp.isSuccessful) {
                return@withContext CameraFolderOutcome.Error(ApiErrorParser.parse(rootResp))
            }
            val rootId = rootResp.body()?.id
                ?: return@withContext CameraFolderOutcome.Error("Empty root folder response")

            val childrenResp = apiServiceFactory.authFolderService().getChildren(rootId).execute()
            if (!childrenResp.isSuccessful) {
                return@withContext CameraFolderOutcome.Error(ApiErrorParser.parse(childrenResp))
            }
            val children = childrenResp.body() ?: emptyList()

            // Ищем CAMERA только по folderType, не по имени папки.
            val camera = children.firstOrNull { it.folderType == CAMERA_FOLDER_TYPE }
            if (camera != null) {
                CameraFolderOutcome.Success(camera.id)
            } else {
                // TODO: если сервер добавит endpoint получения/создания системных папок,
                // заменить эту ветку на явное получение CAMERA folderId.
                CameraFolderOutcome.NotCreatedYet
            }
        } catch (e: Exception) {
            CameraFolderOutcome.Error(e.message ?: "Ошибка при получении папки CAMERA")
        }
    }
}

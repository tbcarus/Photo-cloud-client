package ru.tbcarus.photo_cloud_client.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Читает все фото из MediaStore.Images и возвращает список [MediaStoreImage].
     * Синхронизацию с Room выполняет [MediaFileRepository].
     *
     * При отсутствии разрешения возвращает [MediaStoreScanOutcome.PermissionDenied], не кидает исключение.
     * Строки с некорректными данными пропускаются — одна плохая запись не останавливает scan.
     *
     * // TODO: runtime-запрос разрешений будет добавлен на UI-этапе.
     * // Сейчас repository только сообщает, что доступа к MediaStore нет.
     */
    suspend fun loadImages(): MediaStoreScanOutcome = withContext(Dispatchers.IO) {

        // Нужное разрешение зависит от версии Android
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            return@withContext MediaStoreScanOutcome.PermissionDenied
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED
        )

        // Сканируем только папку DCIM/ (снимки/видео устройства), исключая Download, Pictures,
        // Pictures/Screenshots и т.п. Широкий фильтр DCIM/% переносим между вендорами (имя подпапки
        // камеры отличается). Фильтр применяется в запросе MediaStore, а не пост-фильтром.
        // TODO: При необходимости уточнить фильтр до конкретных подпапок камеры, если DCIM/ будет захватывать лишнее.
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("DCIM/%")

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        ) ?: return@withContext MediaStoreScanOutcome.Error("MediaStore query вернул null cursor")

        return@withContext try {
            cursor.use { c ->
                val idCol           = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val displayNameCol  = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val relativePathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val mimeTypeCol     = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol         = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateTakenCol    = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateModifiedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val dateAddedCol    = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                val images = mutableListOf<MediaStoreImage>()

                while (c.moveToNext()) {
                    try {
                        val id = c.getLong(idCol)
                        if (id <= 0L) continue

                        val displayName = c.getString(displayNameCol)
                            ?.takeIf { it.isNotBlank() }
                            ?: "image_$id"
                        val relativePath = c.getString(relativePathCol)
                            ?.takeIf { it.isNotBlank() }
                        val mimeType = c.getString(mimeTypeCol)
                            ?.takeIf { it.isNotBlank() }
                            ?: "image/*"
                        val size = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol)

                        val dateTaken    = if (c.isNull(dateTakenCol))    0L else c.getLong(dateTakenCol)
                        val dateModified = if (c.isNull(dateModifiedCol)) 0L else c.getLong(dateModifiedCol)
                        val dateAdded    = if (c.isNull(dateAddedCol))    0L else c.getLong(dateAddedCol)

                        // DATE_MODIFIED и DATE_ADDED в MediaStore возвращают seconds, в БД храним milliseconds
                        val dateModifiedMs = dateModified.takeIf { it > 0L }?.times(1000L)
                        val dateAddedMs    = dateAdded.takeIf    { it > 0L }?.times(1000L)

                        // DATE_TAKEN уже в ms; если отсутствует — fallback по цепочке
                        val createdAt = dateTaken.takeIf { it > 0L }
                            ?: dateModifiedMs
                            ?: dateAddedMs
                            ?: System.currentTimeMillis()
                        val lastModified = dateModifiedMs ?: createdAt

                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        ).toString()

                        images.add(
                            MediaStoreImage(
                                mediaStoreId = id,
                                uri          = uri,
                                displayName  = displayName,
                                relativePath = relativePath,
                                mimeType     = mimeType,
                                size         = size,
                                createdAt    = createdAt,
                                lastModified = lastModified
                            )
                        )
                    } catch (e: Exception) {
                        // пропускаем строку с неполными или некорректными данными
                    }
                }

                MediaStoreScanOutcome.Success(images)
            }
        } catch (e: Exception) {
            MediaStoreScanOutcome.Error(e.message ?: "Неизвестная ошибка при чтении MediaStore")
        }
    }
}

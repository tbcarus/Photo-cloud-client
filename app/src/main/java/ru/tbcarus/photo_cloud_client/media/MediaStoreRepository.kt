package ru.tbcarus.photo_cloud_client.media

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private companion object {
        const val TAG = "MediaStoreRepository"
    }

    /**
     * Читает все фото из MediaStore.Images и возвращает список [MediaStoreImage].
     * Синхронизацию с Room выполняет [MediaFileRepository].
     *
     * При отсутствии полного доступа возвращает [MediaStoreScanOutcome.PermissionDenied], не кидает исключение.
     * Строки с некорректными данными пропускаются — одна плохая запись не останавливает scan,
     * но такой снимок помечается неполным и не доказывает удаления отсутствующих записей.
     * Неполным считается и снимок, полученный при неподключённом основном хранилище.
     *
     * // TODO: runtime-запрос разрешений будет добавлен на UI-этапе.
     * // Сейчас repository только сообщает, что доступа к MediaStore нет.
     */
    suspend fun loadImages(): MediaStoreScanOutcome = withContext(Dispatchers.IO) {

        if (!context.hasMediaReadPermission()) {
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

        // Ошибка самого запроса — тоже Error, а не исключение: повторная проверка снимка обрабатывает её как сбой.
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )
        } catch (e: Exception) {
            return@withContext MediaStoreScanOutcome.Error(e.message ?: "Ошибка запроса MediaStore")
        } ?: return@withContext MediaStoreScanOutcome.Error("MediaStore query вернул null cursor")

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
                var skippedRows = 0

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
                        skippedRows++
                    }
                }

                if (skippedRows > 0) Log.w(TAG, "Пропущено строк MediaStore: $skippedRows — снимок неполный")
                // Пока том недоступен, MediaStore скрывает его файлы без ошибки запроса.
                val storageMounted = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
                if (!storageMounted) Log.w(TAG, "Основное хранилище не подключено — снимок неполный")
                MediaStoreScanOutcome.Success(images, isComplete = skippedRows == 0 && storageMounted)
            }
        } catch (e: Exception) {
            MediaStoreScanOutcome.Error(e.message ?: "Неизвестная ошибка при чтении MediaStore")
        }
    }
}

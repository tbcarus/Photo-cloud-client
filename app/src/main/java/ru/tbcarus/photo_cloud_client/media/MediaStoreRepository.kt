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

        /**
         * Каталоги сканирования по RELATIVE_PATH, вместе с вложенными, — единственное место их задания.
         * DCIM/ — снимки камеры: имя подпапки камеры у вендоров разное, поэтому берётся весь DCIM/.
         */
        // TODO A4.3: удалить Pictures/ после испытаний на эмуляторе
        val SCAN_DIRECTORIES = listOf("DCIM/", "Pictures/")
        val VIDEO_DIRECTORY = "DCIM/Camera/"
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
    suspend fun loadImages(): MediaStoreScanOutcome = load(MediaCollection.IMAGE)

    suspend fun loadVideos(): MediaStoreScanOutcome = load(MediaCollection.VIDEO)

    private suspend fun load(collection: MediaCollection): MediaStoreScanOutcome = withContext(Dispatchers.IO) {

        if (!context.hasMediaReadPermission(collection)) {
            return@withContext MediaStoreScanOutcome.PermissionDenied
        }

        val baseUri = if (collection == MediaCollection.IMAGE) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                      else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val directories = if (collection == MediaCollection.IMAGE) SCAN_DIRECTORIES else listOf(VIDEO_DIRECTORY)

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED
        )

        // Только каталоги [SCAN_DIRECTORIES]; Download, Movies и прочие пути не читаются.
        // Один запрос по всем каталогам даёт один общий снимок: каждая строка MediaStore (_ID) входит в него
        // один раз. Фильтр применяется в запросе MediaStore, а не пост-фильтром.
        val selection = if (collection == MediaCollection.VIDEO) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        } else directories.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
        val selectionArgs = if (collection == MediaCollection.VIDEO) arrayOf(VIDEO_DIRECTORY)
            else directories.map { "$it%" }.toTypedArray()

        // Ошибка самого запроса — тоже Error, а не исключение: повторная проверка снимка обрабатывает её как сбой.
        val cursor = try {
            context.contentResolver.query(
                baseUri,
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
                val idCol           = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val displayNameCol  = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val relativePathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val mimeTypeCol     = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol         = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateTakenCol    = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val dateModifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dateAddedCol    = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

                val images = mutableListOf<MediaStoreImage>()
                var skippedRows = 0

                while (c.moveToNext()) {
                    try {
                        val id = c.getLong(idCol)
                        if (id <= 0L) {
                            skippedRows++
                            continue
                        }

                        val displayName = c.getString(displayNameCol)
                            ?.takeIf { it.isNotBlank() }
                            ?: "${collection.name.lowercase()}_$id"
                        val relativePath = c.getString(relativePathCol)
                            ?.takeIf { it.isNotBlank() }
                        val mimeType = c.getString(mimeTypeCol)
                            ?.takeIf { it.isNotBlank() }
                            ?: "${collection.name.lowercase()}/*"
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
                            baseUri, id
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
                                lastModified = lastModified,
                                collection = collection
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

package ru.tbcarus.photo_cloud_client.library.original

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import javax.inject.Inject
import javax.inject.Singleton

/** Изображение страницы просмотра. */
sealed interface ViewerImage {
    /** Кадр готов; [release] снимает защиту файла кеша от вытеснения, когда страница уходит с экрана. */
    class Shown(val bitmap: Bitmap, val release: () -> Unit) : ViewerImage

    data class Error(val reason: ViewerError) : ViewerImage
}

enum class ViewerError { NO_CONNECTION, NOT_FOUND, UNAUTHORIZED, NO_SPACE, CORRUPTED, FAILED, DECODE }

/** Источник изображений для просмотра: оригинал из кеша или с Server, затем декодирование под экран. */
fun interface ViewerImageSource {
    suspend fun load(file: FileItemDto): ViewerImage
}

@Singleton
class OriginalImages @Inject constructor(private val cache: OriginalCache) : ViewerImageSource {

    override suspend fun load(file: FileItemDto): ViewerImage {
        val id = file.id ?: return ViewerImage.Error(ViewerError.NOT_FOUND)
        val checksum = file.checksum ?: return ViewerImage.Error(ViewerError.NOT_FOUND)
        return when (val result = cache.open(id, checksum, file.size)) {
            is OriginalResult.Ready -> try {
                val bitmap = withContext(Dispatchers.Default) { OriginalDecoder.decode(result.file) }
                ViewerImage.Shown(bitmap, result.lease::release)
            } catch (e: CancellationException) {
                result.lease.release()
                throw e
            } catch (e: Exception) {
                // Содержимое проверено по SHA-256: повторное скачивание не поможет, файл в кеше остаётся.
                result.lease.release()
                ViewerImage.Error(ViewerError.DECODE)
            }
            OriginalResult.NoConnection -> ViewerImage.Error(ViewerError.NO_CONNECTION)
            OriginalResult.NotFound -> ViewerImage.Error(ViewerError.NOT_FOUND)
            OriginalResult.Unauthorized -> ViewerImage.Error(ViewerError.UNAUTHORIZED)
            OriginalResult.NoSpace -> ViewerImage.Error(ViewerError.NO_SPACE)
            OriginalResult.Corrupted -> ViewerImage.Error(ViewerError.CORRUPTED)
            is OriginalResult.Failed -> ViewerImage.Error(ViewerError.FAILED)
        }
    }
}

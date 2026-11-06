package ru.tbcarus.photo_cloud_client.library.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.library.original.OriginalCache
import ru.tbcarus.photo_cloud_client.library.original.OriginalLease
import ru.tbcarus.photo_cloud_client.library.original.OriginalResult
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * PDF открывается встроенным просмотром по MIME Server; общий `application/octet-stream` — по расширению.
 * Нужны ID и checksum: по checksum оригинал хранится в кеше.
 */
fun isPdf(file: FileItemDto): Boolean {
    if ((file.id ?: 0) <= 0 || file.checksum.isNullOrBlank()) return false
    return when (file.mimeType?.lowercase()) {
        "application/pdf" -> true
        "application/octet-stream" -> file.originalFilename?.lowercase()?.endsWith(".pdf") == true
        else -> false
    }
}

enum class PdfError { NO_CONNECTION, NOT_FOUND, UNAUTHORIZED, NO_SPACE, CORRUPTED, FAILED, DAMAGED, PROTECTED, NO_MEMORY }

sealed interface PdfOpenResult {
    class Ready(val document: PdfDocument) : PdfOpenResult
    data class Error(val reason: PdfError) : PdfOpenResult
}

/** Источник документов для просмотра: оригинал из общего кеша оригиналов или с Server, затем PdfRenderer. */
@Singleton
class PdfDocuments @Inject constructor(private val cache: OriginalCache) {

    suspend fun open(file: FileItemDto): PdfOpenResult {
        val id = file.id ?: return PdfOpenResult.Error(PdfError.NOT_FOUND)
        val checksum = file.checksum ?: return PdfOpenResult.Error(PdfError.NOT_FOUND)
        return when (val result = cache.open(id, checksum, file.size)) {
            is OriginalResult.Ready -> openRenderer(result.file, result.lease)
            OriginalResult.NoConnection -> PdfOpenResult.Error(PdfError.NO_CONNECTION)
            OriginalResult.NotFound -> PdfOpenResult.Error(PdfError.NOT_FOUND)
            OriginalResult.Unauthorized -> PdfOpenResult.Error(PdfError.UNAUTHORIZED)
            OriginalResult.NoSpace -> PdfOpenResult.Error(PdfError.NO_SPACE)
            OriginalResult.Corrupted -> PdfOpenResult.Error(PdfError.CORRUPTED)
            is OriginalResult.Failed -> PdfOpenResult.Error(PdfError.FAILED)
        }
    }

    private suspend fun openRenderer(file: File, lease: OriginalLease): PdfOpenResult {
        var descriptor: ParcelFileDescriptor? = null
        try {
            return withContext(Dispatchers.IO) {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(descriptor!!)
                if (renderer.pageCount <= 0) {
                    renderer.close()
                    throw IOException("No pages")
                }
                PdfOpenResult.Ready(PdfDocument(renderer, descriptor!!, lease))
            }
        } catch (e: CancellationException) {
            descriptor?.close()
            lease.release()
            throw e
        } catch (e: Exception) {
            // Содержимое проверено по SHA-256: повторное скачивание не поможет, файл в кеше остаётся.
            descriptor?.close()
            lease.release()
            return PdfOpenResult.Error(if (e is SecurityException) PdfError.PROTECTED else PdfError.DAMAGED)
        }
    }
}

/**
 * Открытый документ. Страницы рендерятся по одной: PdfRenderer не допускает параллельной работы, поэтому
 * рендер идёт под [mutex]. [close] освобождает рендерер, дескриптор и защиту файла кеша от вытеснения;
 * если в этот момент страница ещё рендерится, освобождение выполнит рендер по завершении.
 */
class PdfDocument internal constructor(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val lease: OriginalLease
) {
    val pageCount: Int = renderer.pageCount

    private val mutex = Mutex()
    @Volatile
    private var closed = false
    private var released = false

    /** Рендер страницы [index] в Bitmap ограниченного размера вне главного потока. */
    suspend fun render(index: Int): Bitmap {
        try {
            return mutex.withLock {
                withContext(Dispatchers.IO) {
                    check(!closed) { "Document closed" }
                    renderer.openPage(index).use { page ->
                        val (width, height) = pageBitmapSize(page.width, page.height)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        // У страниц PDF бывает прозрачный фон: подложка белая, как на бумаге.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } finally {
            if (closed) releaseIfIdle()
        }
    }

    fun close() {
        closed = true
        releaseIfIdle()
    }

    private fun releaseIfIdle() {
        if (!mutex.tryLock()) return
        try {
            if (released) return
            released = true
            renderer.close()
            descriptor.close()
            lease.release()
        } finally {
            mutex.unlock()
        }
    }
}

/** Длинная сторона страницы и предел пикселей: около 24 МБ на страницу в ARGB_8888. */
const val PDF_MAX_SIDE = 3072
const val PDF_MAX_PIXELS = 6_000_000L

/** Размер Bitmap страницы [width]×[height] (в пунктах PDF), вписанный в [maxSide] и [maxPixels] с сохранением пропорций. */
fun pageBitmapSize(width: Int, height: Int, maxSide: Int = PDF_MAX_SIDE, maxPixels: Long = PDF_MAX_PIXELS): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    val bySide = maxSide.toDouble() / maxOf(width, height)
    val byPixels = sqrt(maxPixels.toDouble() / (width.toLong() * height))
    val scale = minOf(bySide, byPixels)
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

/** Соседняя страница в пределах документа: переход не выходит за первую и последнюю. */
fun pdfPageAfter(current: Int, delta: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else (current + delta).coerceIn(0, pageCount - 1)

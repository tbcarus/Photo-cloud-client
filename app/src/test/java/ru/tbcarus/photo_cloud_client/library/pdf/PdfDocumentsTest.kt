package ru.tbcarus.photo_cloud_client.library.pdf

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.library.original.OriginalCache
import ru.tbcarus.photo_cloud_client.transfer.DownloadedFile
import ru.tbcarus.photo_cloud_client.transfer.RemoteResult
import java.io.File
import java.security.MessageDigest

/**
 * Получение PDF через общий кеш оригиналов и обработка ошибок. Настоящий рендеринг страниц PdfRenderer
 * проверяется на устройстве: здесь документ не открывается успешно.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PdfDocumentsTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val content = HashMap<Long, ByteArray>()
    private val calls = mutableListOf<Long>()

    private fun cache(maxBytes: Long = 1_000_000) = OriginalCache(
        temporary.newFolder("originals"),
        temporary.newFolder("originals-tmp"),
        maxBytes,
        { fileId, target ->
            calls += fileId
            val bytes = content[fileId] ?: return@OriginalCache RemoteResult.HttpError(404, "FILE_ITEM_NOT_FOUND")
            target.writeBytes(bytes)
            RemoteResult.Success(DownloadedFile(bytes.size.toLong(), sha256(bytes)))
        },
        { Long.MAX_VALUE }
    )

    private fun pdf(id: Long, bytes: ByteArray, mime: String = "application/pdf"): FileItemDto {
        content[id] = bytes
        return FileItemDto(id = id, originalFilename = "doc$id.pdf", mimeType = mime, size = bytes.size.toLong(), checksum = sha256(bytes))
    }

    @Test
    fun pdfIsRecognizedByServerMimeOrPdfNameForGenericMime() {
        val checksum = "a".repeat(64)
        assertTrue(isPdf(FileItemDto(id = 1, mimeType = "application/pdf", originalFilename = "scan", checksum = checksum)))
        assertTrue(isPdf(FileItemDto(id = 2, mimeType = "application/octet-stream", originalFilename = "Report.PDF", checksum = checksum)))
        assertFalse(isPdf(FileItemDto(id = 3, mimeType = "application/octet-stream", originalFilename = "data.bin", checksum = checksum)))
        assertFalse(isPdf(FileItemDto(id = 4, mimeType = "image/jpeg", originalFilename = "photo.pdf", checksum = checksum)))
        assertFalse(isPdf(FileItemDto(id = 5, mimeType = "application/pdf", checksum = null)))
    }

    @Test
    fun damagedDocumentIsReportedReusesCacheAndIsNotPinned() = runBlocking {
        val cache = cache(maxBytes = 1_500)
        val documents = PdfDocuments(cache)
        val broken = pdf(1, ByteArray(1_000) { (it % 7).toByte() })

        assertEquals(PdfOpenResult.Error(PdfError.DAMAGED), documents.open(broken))
        assertEquals(PdfOpenResult.Error(PdfError.DAMAGED), documents.open(broken))
        // Повторное открытие берёт проверенную копию из кеша оригиналов, без второго скачивания.
        assertEquals(listOf(1L), calls)

        // Неудачное открытие сняло защиту файла: новый оригинал вытесняет его из кеша.
        val next = pdf(2, ByteArray(1_000) { 1 })
        documents.open(next)
        assertEquals(listOf(1L, 2L), calls)
        assertFalse(File(temporary.root, "originals/${broken.checksum}").exists())
    }

    @Test
    fun downloadFailureIsReportedWithoutOpeningDocument() = runBlocking {
        val missing = FileItemDto(id = 9, mimeType = "application/pdf", size = 10, checksum = "b".repeat(64))

        assertEquals(PdfOpenResult.Error(PdfError.NOT_FOUND), PdfDocuments(cache()).open(missing))
    }

    @Test
    fun pageSizeIsBoundedAndNavigationStaysInsideDocument() {
        // A4 в пунктах: длинная сторона и пиксели не выходят за пределы, пропорции сохраняются.
        val (w, h) = pageBitmapSize(595, 842)
        assertTrue(maxOf(w, h) <= PDF_MAX_SIDE)
        assertTrue(w.toLong() * h <= PDF_MAX_PIXELS)
        assertEquals(595.0 / 842, w.toDouble() / h, 0.01)
        // Узкая длинная страница ограничена стороной.
        assertEquals(PDF_MAX_SIDE, pageBitmapSize(100, 4000).second)

        assertEquals(0, pdfPageAfter(0, -1, 3))
        assertEquals(1, pdfPageAfter(0, 1, 3))
        assertEquals(2, pdfPageAfter(2, 1, 3))
        assertEquals(2, pdfPageAfter(7, 0, 3))
        assertEquals(0, pdfPageAfter(0, 1, 0))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

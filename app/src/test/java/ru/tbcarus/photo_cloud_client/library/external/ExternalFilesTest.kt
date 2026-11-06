package ru.tbcarus.photo_cloud_client.library.external

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto

/** Тип, имя и права, с которыми файл передаётся внешнему приложению. Запуск приложений проверяется на устройстве. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExternalFilesTest {

    // В Robolectric таблица расширений пуста; на устройстве она системная.
    @Before
    fun setUp() {
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("txt", "text/plain")
            addExtensionMimeTypeMapping("zip", "application/zip")
            addExtensionMimeTypeMapping("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        }
    }

    @Test
    fun mimeComesFromServerOrFromOriginalName() {
        assertEquals("text/plain", externalMimeType("readme", "text/plain"))
        assertEquals("application/pdf", externalMimeType("scan.bin", "Application/PDF"))
        // Общий тип Server уточняется по расширению исходного имени.
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            externalMimeType("Договор.DOCX", "application/octet-stream")
        )
        assertEquals("application/zip", externalMimeType("archive.zip", null))
        // Внутренний тип Tika для OOXML приложениям не понятен: тип берётся по расширению.
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            externalMimeType("Отчёт.docx", "application/x-tika-ooxml")
        )
        assertEquals("application/octet-stream", externalMimeType("unknown.bin2", "application/x-tika-msoffice"))
        assertEquals("application/octet-stream", externalMimeType("part.step", "application/octet-stream"))
        assertEquals("application/octet-stream", externalMimeType("noextension", null))

        assertEquals("Отчёт.xlsx", externalDisplayName(FileItemDto(id = 5, originalFilename = "Отчёт.xlsx")))
        assertEquals("file-5", externalDisplayName(FileItemDto(id = 5, originalFilename = " ")))
        assertTrue(canOpenExternally(FileItemDto(id = 5, checksum = "A".repeat(64))))
        assertFalse(canOpenExternally(FileItemDto(id = 5, checksum = "c5")))
    }

    @Test
    fun intentGrantsOnlyTemporaryReadOfContentUri() {
        val uri = Uri.parse("content://ru.tbcarus.photo_cloud_client.originals/originals/${"a".repeat(64)}?displayName=notes.txt")

        val intent = externalViewIntent(uri, "notes.txt", "text/plain")

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("text/plain", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun providerTypeFollowsDisplayNameNotChecksumFileName() {
        val provider = OriginalsFileProvider()
        val base = "content://ru.tbcarus.photo_cloud_client.originals/originals/${"a".repeat(64)}"

        assertEquals("text/plain", provider.getType(Uri.parse("$base?displayName=notes.txt")))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            provider.getType(Uri.parse("$base?displayName=%D0%94%D0%BE%D0%B3%D0%BE%D0%B2%D0%BE%D1%80.docx"))
        )
    }
}

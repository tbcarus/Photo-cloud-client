package ru.tbcarus.photo_cloud_client.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto

class LibraryFormatTest {

    private fun file(name: String, type: String? = "OTHER") =
        FileItemDto(
            id = 1,
            originalFilename = name,
            fileType = type,
            size = 2_516_582,
            capturedAt = "2020-02-03T04:05:06",
            uploadedAt = "2026-05-17T10:16:00.123456"
        )

    @Test
    fun pathStartsFromRootLabel() {
        val path = listOf(
            FolderDto(1, null, "root", "ROOT"),
            FolderDto(2, 1, "Проекты", "USER"),
            FolderDto(3, 2, "Светильники", "USER")
        )

        assertEquals("Корень / Проекты / Светильники", libraryPathLabel(path))
    }

    @Test
    fun typeIsShortExtensionOrKnownCategory() {
        assertEquals("STEP", fileTypeLabel(file("Сборка.step")))
        assertEquals("PDF", fileTypeLabel(file("Чертёж.final.pdf", "DOCUMENT")))
        assertEquals("Документ", fileTypeLabel(file("README", "DOCUMENT")))
        assertEquals("Изображение", fileTypeLabel(file(".nomedia", "IMAGE")))
        assertEquals("Файл", fileTypeLabel(file("data.very-long-extension", "OTHER")))
        assertEquals("Файл", fileTypeLabel(FileItemDto(id = 1)))
    }

    @Test
    fun sizeUsesReadableUnits() {
        assertEquals("120 Б", formatLibrarySize(120))
        assertEquals("850 КБ", formatLibrarySize(870_400))
        assertEquals("2,4 МБ", formatLibrarySize(2_516_582))
        assertEquals("1,5 ГБ", formatLibrarySize(1_610_612_736))
    }

    @Test
    fun cameraShowsCapturedDateAndOtherFoldersUploadDate() {
        assertEquals("STEP · 2,4 МБ · 03.02.2020 04:05", librarySubtitle(file("a.step"), inCamera = true))
        assertEquals("STEP · 2,4 МБ · 17.05.2026 10:16", librarySubtitle(file("a.step"), inCamera = false))
    }

    @Test
    fun missingPropertiesAreSkipped() {
        assertEquals("TXT", librarySubtitle(FileItemDto(id = 1, originalFilename = "a.txt"), inCamera = false))
        assertNull(formatLibraryDate("not-a-date"))
    }
}

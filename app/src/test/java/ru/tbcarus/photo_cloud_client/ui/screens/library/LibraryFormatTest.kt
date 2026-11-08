package ru.tbcarus.photo_cloud_client.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.api.models.ShareUserDto
import ru.tbcarus.photo_cloud_client.api.models.SharedResourceDto
import java.time.ZoneId

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
    fun sharedEntryShowsOwnerTypeSizeAndExpiryInDeviceZone() {
        val entry = SharedResourceDto(
            shareId = 1, resourceType = "FILE", resourceId = 1, name = "plan.pdf",
            file = file("plan.pdf"), owner = ShareUserDto(2, "owner@test", " "), expiresAt = "2031-05-01T09:00:00Z"
        )
        val folder = SharedResourceDto(2, "FOLDER", 5, "Trips", owner = ShareUserDto(2, "owner@test", "Анна"))

        // Пустое имя заменяется email; срок — момент UTC в часовом поясе устройства.
        assertEquals(
            "Владелец: owner@test · PDF · 2,4 МБ · доступ до 01.05.2031 12:00",
            sharedSubtitle(entry, ZoneId.of("Europe/Moscow"))
        )
        assertEquals("Владелец: Анна", sharedSubtitle(folder))
        assertEquals("Доступно мне / Trips / Day1",
            sharedPathLabel(listOf(FolderDto(5, null, "Trips", SHARED_ROOT_TYPE), FolderDto(6, 5, "Day1", "USER"))))
    }

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

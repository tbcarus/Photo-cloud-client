package ru.tbcarus.photo_cloud_client.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tbcarus.photo_cloud_client.api.models.FolderDto

/** Ручная загрузка недоступна в AutoUploads и во всём дереве активного источника, но не в отключённом. */
class LibraryManagedTreeTest {

    private val root = FolderDto(1, null, "root", "ROOT")
    private val camera = FolderDto(2, 1, "Camera", "CAMERA")
    private val work = FolderDto(3, 1, "Работа", "USER")
    private val autoUploads = FolderDto(4, 1, "AutoUploads", "AUTO_UPLOADS")
    private val active = FolderDto(5, 4, "Documents", "USER", backupSource = "550e8400-e29b-41d4-a716-446655440000")
    private val nested = FolderDto(6, 5, "Проекты", "USER")
    private val deep = FolderDto(7, 6, "Чертежи", "USER")
    private val disabled = FolderDto(8, 4, "OldDocuments", "USER")
    private val disabledNested = FolderDto(9, 8, "Архив", "USER")

    private fun at(vararg path: FolderDto) = LibraryUiState(status = LibraryStatus.Content, path = path.toList())

    @Test
    fun uploadIsHiddenInsideManagedTree() {
        assertFalse(at(root, autoUploads).canUpload)
        assertFalse(at(root, autoUploads, active).canUpload)
        // У вложенных папок backupSource нет: защиту определяет корень на открытом пути.
        assertFalse(at(root, autoUploads, active, nested).canUpload)
        assertFalse(at(root, autoUploads, active, nested, deep).canUpload)
        assertTrue(at(root, autoUploads, active, nested, deep).inManagedTree)
    }

    @Test
    fun ordinaryLibraryAndDisabledSourcesKeepUpload() {
        assertTrue(at(root).canUpload)
        assertTrue(at(root, work).canUpload)
        assertTrue(at(root, autoUploads, disabled).canUpload)
        assertTrue(at(root, autoUploads, disabled, disabledNested).canUpload)
        assertFalse(at(root, camera).canUpload)
    }

    @Test
    fun containerAndActiveSourceAreMarked() {
        assertEquals("Автоматическая загрузка папок", folderSubtitle(autoUploads))
        assertEquals("Источник автоматической загрузки", folderSubtitle(active))
        assertNull(folderSubtitle(nested))
        assertNull(folderSubtitle(disabled))
        assertNull(folderSubtitle(work))
    }
}

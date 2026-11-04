package ru.tbcarus.photo_cloud_client.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto

/** Ручные изменения недоступны в системных папках, в AutoUploads и во всём дереве активного источника, но не в отключённом. */
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
    fun disableIsOfferedOnlyOnActiveSourceRoot() {
        assertTrue(at(root, autoUploads, active).isSourceRoot)
        assertFalse(at(root).isSourceRoot)
        assertFalse(at(root, camera).isSourceRoot)
        assertFalse(at(root, autoUploads).isSourceRoot)
        assertFalse(at(root, autoUploads, active, nested).isSourceRoot)
        assertFalse(at(root, work).isSourceRoot)
        assertFalse(at(root, autoUploads, disabled).isSourceRoot)
        // Пока каталог не загружен, действия нет.
        assertFalse(LibraryUiState(status = LibraryStatus.Loading, path = listOf(root, autoUploads, active)).isSourceRoot)
    }

    @Test
    fun containerAndActiveSourceAreMarked() {
        assertEquals("Резервные копии фото", folderSubtitle(camera))
        assertEquals("Автоматическая загрузка папок", folderSubtitle(autoUploads))
        assertEquals("Источник автоматической загрузки", folderSubtitle(active))
        assertNull(folderSubtitle(nested))
        assertNull(folderSubtitle(disabled))
        assertNull(folderSubtitle(work))
    }

    @Test
    fun folderIconsDistinguishSystemFoldersAndActiveSourceRoot() {
        assertEquals(FolderKind.CAMERA, folderKind(camera))
        assertEquals(FolderKind.AUTO_UPLOADS, folderKind(autoUploads))
        assertEquals(FolderKind.ACTIVE_SOURCE, folderKind(active))
        assertEquals(FolderKind.FOLDER, folderKind(work))
        // Вложенные папки источника отдельного значка не имеют.
        assertEquals(FolderKind.FOLDER, folderKind(nested))
        assertEquals(FolderKind.FOLDER, folderKind(deep))
    }

    @Test
    fun disabledSourceLooksAndActsLikeOrdinaryFolder() {
        assertEquals(folderKind(work), folderKind(disabled))
        assertEquals(folderSubtitle(work), folderSubtitle(disabled))
        // В AutoUploads отключённый источник переименовывается, активный — нет.
        val inContainer = at(root, autoUploads)
        assertTrue(inContainer.canRename(disabled))
        assertFalse(inContainer.canRename(active))
        assertTrue(at(root).canRename(work))
        assertTrue(at(root, autoUploads, disabled).canRename(disabledNested))
        assertTrue(at(root, autoUploads, disabled).canRename(file))
        assertTrue(at(root, autoUploads, disabled).canCreateFolder)
    }

    @Test
    fun systemFoldersAreNotRenamed() {
        assertFalse(at(root).canRename(camera))
        assertFalse(at(root).canRename(autoUploads))
        assertFalse(at(root).canRename(root))
    }

    @Test
    fun activeTreeIsProtectedByPathNotByOwnBackupSource() {
        // У nested и deep backupSource нет: защиту даёт корень источника на пути.
        assertFalse(at(root, autoUploads, active).canRename(nested))
        assertFalse(at(root, autoUploads, active).canRename(file))
        assertFalse(at(root, autoUploads, active, nested).canRename(deep))
        assertFalse(at(root, autoUploads, active, nested, deep).canRename(file))
        assertFalse(at(root, autoUploads, active, nested).canCreateFolder)
        assertFalse(at(root, autoUploads).canCreateFolder)
        assertTrue(at(root, autoUploads, active, nested).inActiveSource)
        assertFalse(at(root, autoUploads, disabled, disabledNested).inActiveSource)
    }

    @Test
    fun createFolderOnlyInRootAndUserFolders() {
        assertTrue(at(root).canCreateFolder)
        assertTrue(at(root, work).canCreateFolder)
        assertFalse(at(root, camera).canCreateFolder)
    }

    @Test
    fun cameraFilesFollowServerRulesForRename() {
        assertTrue(at(root, camera).canRename(file))
        assertFalse(at(root, camera).canRename(file.copy(id = null)))
    }

    @Test
    fun nothingIsChangedBeforeFolderIsLoaded() {
        val loading = LibraryUiState(status = LibraryStatus.Loading, path = listOf(root))
        assertFalse(loading.canRename(work))
        assertFalse(loading.canRename(file))
        assertFalse(loading.canCreateFolder)
    }

    private val file = FileItemDto(id = 10, originalFilename = "Чертёж.pdf")
}

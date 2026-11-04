package ru.tbcarus.photo_cloud_client.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto

/** Выделение по ID и правила назначения группы: каждое ограничение одиночной операции действует для группы. */
class LibraryBatchTest {

    private val root = FolderDto(1, null, "root", "ROOT")
    private val camera = FolderDto(2, 1, "Camera", "CAMERA")
    private val work = FolderDto(3, 1, "Работа", "USER")
    private val archive = FolderDto(4, 1, "Архив", "USER")
    private val workNested = FolderDto(5, 3, "Проекты", "USER")
    private val autoUploads = FolderDto(7, 1, "AutoUploads", "AUTO_UPLOADS")
    private val active = FolderDto(8, 7, "Documents", "USER", backupSource = "550e8400-e29b-41d4-a716-446655440000")
    private val disabled = FolderDto(10, 7, "OldPhone", "USER")
    private val a = FileItemDto(id = 20, originalFilename = "a.pdf")
    private val b = FileItemDto(id = 21, originalFilename = "b.pdf")

    private fun group(copy: Boolean, vararg items: LibraryItem, from: List<FolderDto> = listOf(root)) =
        Relocation(1, Relocated.Group(items.toList(), copy), from)

    @Test
    fun selectionCountsEachIdOnce() {
        val selection = Selection(root.id).toggle(a).toggle(work).plus(listOf(work, archive), listOf(a, b, a.copy()))
        assertEquals(4, selection.size)
        assertEquals(setOf(20L, 21L), selection.files.keys)
        assertTrue(selection.contains(a.copy(originalFilename = "переименован.pdf")))
        // Повторное переключение снимает отметку.
        assertEquals(3, selection.toggle(b).size)
        assertFalse(selection.toggle(work).contains(work))
        // Файл без ID не выделяется.
        assertEquals(4, selection.toggle(FileItemDto(id = null)).size)
        assertEquals(listOf(LibraryItem.Folder(work), LibraryItem.Folder(archive), LibraryItem.File(a), LibraryItem.File(b)), selection.items)
    }

    @Test
    fun countsAreDeclined() {
        assertEquals("1 объект", objectsCount(1))
        assertEquals("3 объекта", objectsCount(3))
        assertEquals("7 объектов", objectsCount(7))
        assertEquals("11 объектов", objectsCount(11))
        assertEquals("21 объект", objectsCount(21))
        assertEquals("Удалить 7 выбранных объектов?", "Удалить ${selectedObjectsCount(7)}?")
        assertEquals("1 выбранный объект", selectedObjectsCount(1))
        assertEquals("3 выбранных объекта", selectedObjectsCount(3))
    }

    @Test
    fun groupDestinationMustSuitEveryItem() {
        val mixed = group(false, LibraryItem.Folder(work), LibraryItem.File(a))
        assertNull(destinationProblem(mixed, listOf(root, archive)))
        assertNull(destinationProblem(mixed, listOf(root, autoUploads, disabled)))
        // Перемещение папки в саму себя запрещено, хотя файлу это назначение подходит.
        assertEquals(
            "«Работа»: Нельзя переместить папку в саму себя или во вложенную папку",
            destinationProblem(mixed, listOf(root, work, workNested))
        )
        assertNotNull(destinationProblem(mixed, listOf(root)))
        assertNotNull(destinationProblem(mixed, listOf(root, camera)))
        assertNotNull(destinationProblem(mixed, listOf(root, autoUploads)))
        assertNotNull(destinationProblem(mixed, listOf(root, autoUploads, active)))
        // Перемещаемая папка в списке не открывается.
        assertNotNull(blockedFolderProblem(mixed, listOf(root), work))
        assertNull(blockedFolderProblem(mixed, listOf(root), archive))
    }

    @Test
    fun groupCopyNeverTargetsSourceOrCamera() {
        val copy = group(true, LibraryItem.File(a), LibraryItem.File(b), from = listOf(root, work))
        assertEquals(
            "Копии в той же папке совпадут по именам: используйте одиночное копирование с другим именем",
            destinationProblem(copy, listOf(root, work))
        )
        assertNull(destinationProblem(copy, listOf(root, archive)))
        assertNotNull(destinationProblem(copy, listOf(root, camera)))
        // При копировании файлов в другие папки, включая соседние, входить можно.
        assertNull(blockedFolderProblem(copy, listOf(root), work))
        assertNull(copyNameProblem(copy, archive))
    }

    @Test
    fun onlyChangeableObjectsAreSelectable() {
        fun at(vararg path: FolderDto) = LibraryUiState(status = LibraryStatus.Content, path = path.toList())
        assertTrue(at(root).canSelect(work))
        assertTrue(at(root, work).canSelect(a))
        assertTrue(at(root, autoUploads).canSelect(disabled))
        assertTrue(at(root, camera).canSelect(a))
        assertFalse(at(root).canSelect(camera))
        assertFalse(at(root).canSelect(autoUploads))
        assertFalse(at(root, autoUploads).canSelect(active))
        assertFalse(at(root, autoUploads, active).canSelect(a))
        // В режиме выделения одиночные меню скрыты, выделение продолжается.
        val selecting = at(root, work).copy(selection = Selection(work.id))
        assertTrue(selecting.canSelect(a))
        assertFalse(selecting.canRename(a))
        assertFalse(selecting.canCreateFolder)
        assertEquals("Ничего не выбрано", selecting.groupMoveProblem)
        // Группу с папкой копировать нельзя; в Camera перемещать нельзя.
        val withFolder = at(root).copy(selection = Selection(root.id).toggle(work).toggle(a))
        assertEquals("Копировать можно только файлы: копирования папок на Server нет", withFolder.groupCopyProblem)
        assertNull(withFolder.groupMoveProblem)
        val inCamera = at(root, camera).copy(selection = Selection(camera.id).toggle(a))
        assertEquals("Файлы Camera не перемещаются в библиотеку", inCamera.groupMoveProblem)
        assertNull(inCamera.groupCopyProblem)
    }
}

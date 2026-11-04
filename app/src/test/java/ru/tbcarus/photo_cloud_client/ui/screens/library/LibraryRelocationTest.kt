package ru.tbcarus.photo_cloud_client.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto

/** Правила назначения повторяют проверенные ограничения Server для move/copy. */
class LibraryRelocationTest {

    private val root = FolderDto(1, null, "root", "ROOT")
    private val camera = FolderDto(2, 1, "Camera", "CAMERA")
    private val work = FolderDto(3, 1, "Работа", "USER")
    private val archive = FolderDto(4, 1, "Архив", "USER")
    private val workNested = FolderDto(5, 3, "Проекты", "USER")
    private val workDeep = FolderDto(6, 5, "2026", "USER")
    private val autoUploads = FolderDto(7, 1, "AutoUploads", "AUTO_UPLOADS")
    private val active = FolderDto(8, 7, "Documents", "USER", backupSource = "550e8400-e29b-41d4-a716-446655440000")
    private val activeNested = FolderDto(9, 8, "Вложенная", "USER")
    private val disabled = FolderDto(10, 7, "OldPhone", "USER")
    private val file = FileItemDto(id = 20, folderId = 3, originalFilename = "Чертёж.pdf")
    private val photo = FileItemDto(id = 21, folderId = 2, originalFilename = "IMG_0001.jpg")

    private fun moveFile(vararg from: FolderDto) = Relocation(1, Relocated.MoveFile(file), from.toList())
    private fun copyFile(vararg from: FolderDto, name: String = "Чертёж.pdf") =
        Relocation(1, Relocated.CopyFile(file), from.toList(), copyName = name)
    private fun moveFolder(folder: FolderDto, vararg from: FolderDto) =
        Relocation(1, Relocated.MoveFolder(folder), from.toList())

    @Test
    fun fileMovesBetweenLibraryFoldersAndRoot() {
        val relocation = moveFile(root, work)
        assertNull(destinationProblem(relocation, listOf(root, archive)))
        assertNull(destinationProblem(relocation, listOf(root)))
        assertNull(destinationProblem(relocation, listOf(root, autoUploads, disabled)))
        assertEquals("Файл уже находится здесь", destinationProblem(relocation, listOf(root, work)))
    }

    @Test
    fun fileDoesNotCrossCameraBoundary() {
        assertEquals(
            "Файлы не переносятся между Camera и библиотекой",
            destinationProblem(moveFile(root, work), listOf(root, camera))
        )
        val fromCamera = Relocation(1, Relocated.MoveFile(photo), listOf(root, camera))
        assertNotNull(destinationProblem(fromCamera, listOf(root, work)))
    }

    @Test
    fun cameraCopyRules() {
        // Из библиотеки в Camera копировать нельзя, внутри Camera копия всегда конфликтует по содержимому.
        assertEquals(
            "В Camera файлы попадают только через резервирование",
            destinationProblem(copyFile(root, work), listOf(root, camera))
        )
        val fromCamera = Relocation(1, Relocated.CopyFile(photo), listOf(root, camera), copyName = "IMG_0001.jpg")
        assertNotNull(destinationProblem(fromCamera, listOf(root, camera)))
        // Копия файла Camera в обычную библиотеку допустима.
        assertNull(destinationProblem(fromCamera, listOf(root, work)))
    }

    @Test
    fun copyIntoSameFolderNeedsAnotherName() {
        val sameName = copyFile(root, work)
        assertNull(destinationProblem(sameName, listOf(root, work)))
        assertEquals("Копия в той же папке должна называться иначе", copyNameProblem(sameName, work))
        assertEquals("Копия в той же папке должна называться иначе", copyNameProblem(copyFile(root, work, name = "чертёж.PDF"), work))
        assertNull(copyNameProblem(copyFile(root, work, name = "Чертёж (2).pdf"), work))
        // В другой папке исходное имя допустимо: занятость проверяет Server.
        assertNull(copyNameProblem(sameName, archive))
    }

    @Test
    fun copyNameFollowsLibraryRules() {
        assertEquals("Пустое имя", copyNameProblem(copyFile(root, work, name = ""), archive))
        assertTrue(copyNameProblem(copyFile(root, work, name = "a:b.pdf"), archive)!!.startsWith("Имя содержит"))
        assertEquals("Имя Camera в корне зарезервировано", copyNameProblem(copyFile(root, work, name = "camera"), root))
    }

    @Test
    fun folderCannotMoveIntoItselfOrDescendant() {
        val relocation = moveFolder(work, root)
        assertEquals(
            "Нельзя переместить папку в саму себя или во вложенную папку",
            destinationProblem(relocation, listOf(root, work))
        )
        assertNotNull(destinationProblem(relocation, listOf(root, work, workNested, workDeep)))
        assertEquals("Папка уже находится здесь", destinationProblem(relocation, listOf(root)))
        assertNull(destinationProblem(relocation, listOf(root, archive)))
        // Саму папку в списке не открыть.
        assertNotNull(blockedFolderProblem(relocation, listOf(root), work))
        assertNull(blockedFolderProblem(relocation, listOf(root), archive))
    }

    @Test
    fun folderMovesOnlyIntoRootAndUser() {
        val relocation = moveFolder(workNested, root, work)
        assertNull(destinationProblem(relocation, listOf(root)))
        assertEquals("В Camera нельзя поместить папку", destinationProblem(relocation, listOf(root, camera)))
        assertEquals(
            "В AutoUploads размещаются только источники резервирования",
            destinationProblem(relocation, listOf(root, autoUploads))
        )
        assertNotNull(blockedFolderProblem(relocation, listOf(root), camera))
    }

    @Test
    fun disabledSourceLeavesAutoUploadsForLibrary() {
        val relocation = moveFolder(disabled, root, autoUploads)
        assertNull(destinationProblem(relocation, listOf(root)))
        assertNull(destinationProblem(relocation, listOf(root, work)))
        assertNotNull(destinationProblem(relocation, listOf(root, autoUploads)))
        // В AutoUploads можно войти: среди отключённых источников есть допустимые назначения.
        assertNull(blockedFolderProblem(moveFile(root, work), listOf(root), autoUploads))
    }

    @Test
    fun activeTreeIsNeverDestination() {
        val managed = "Папку изменяет только автоматическое резервирование"
        listOf(moveFile(root, work), copyFile(root, work), moveFolder(archive, root)).forEach { relocation ->
            assertEquals(managed, destinationProblem(relocation, listOf(root, autoUploads, active)))
            assertEquals(managed, destinationProblem(relocation, listOf(root, autoUploads, active, activeNested)))
            assertEquals(managed, blockedFolderProblem(relocation, listOf(root, autoUploads), active))
        }
    }

    @Test
    fun menuOffersOnlyServerAllowedOperations() {
        fun at(vararg path: FolderDto) = LibraryUiState(status = LibraryStatus.Content, path = path.toList())
        assertTrue(at(root, work).canMove(file))
        assertTrue(at(root, work).canCopy(file))
        assertTrue(at(root).canMove(work))
        assertTrue(at(root, autoUploads).canMove(disabled))
        // Файл Camera можно только скопировать в библиотеку.
        assertFalse(at(root, camera).canMove(photo))
        assertTrue(at(root, camera).canCopy(photo))
        // Системные папки и активное дерево.
        assertFalse(at(root).canMove(camera))
        assertFalse(at(root).canMove(autoUploads))
        assertFalse(at(root, autoUploads).canMove(active))
        assertFalse(at(root, autoUploads, active).canMove(activeNested))
        assertFalse(at(root, autoUploads, active).canMove(file))
        assertFalse(at(root, autoUploads, active).canCopy(file))
        // Во время выбора назначения меню объектов недоступно.
        val picking = at(root, work).copy(relocation = moveFile(root, work))
        assertFalse(picking.canMove(file))
        assertFalse(picking.canCopy(file))
        assertFalse(picking.canRename(file))
        assertFalse(picking.canCreateFolder)
    }
}

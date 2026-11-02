package ru.tbcarus.photo_cloud_client.transfer

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LocalTreeAndNamesTest {

    private fun dir(id: String, name: String) = DocumentRow(id, name, Document.MIME_TYPE_DIR, null, 0)

    private fun file(id: String, name: String, size: Long? = 1, flags: Int = 0) = DocumentRow(id, name, "text/plain", size, flags)

    private fun walk(children: Map<String, List<DocumentRow>?>): LocalDir =
        walkDocumentTree("root", "Документы", { children[it] }) { Uri.parse("content://test/$it") }

    @Test
    fun treeKeepsNestingFilesAndEmptyFolders() {
        val tree = walk(
            mapOf(
                "root" to listOf(dir("p", "Проекты"), file("n", "Заметки.txt"), dir("e", "Пустая")),
                "p" to listOf(file("c", "Чертеж.pdf"), file("m", "Модель.step", size = null)),
                "e" to emptyList()
            )
        )

        assertEquals("Документы", tree.name)
        assertEquals(listOf("Заметки.txt"), tree.files.map { it.name })
        assertEquals(listOf("Проекты", "Пустая"), tree.dirs.map { it.name })
        assertEquals(listOf("Чертеж.pdf", "Модель.step"), tree.dirs[0].files.map { it.name })
        assertNull(tree.dirs[0].files[1].size)
        assertEquals(Uri.parse("content://test/c"), tree.dirs[0].files[0].uri)
        assertEquals(3, tree.fileCount)
    }

    @Test
    fun cycleOfProviderIsWalkedOnce() {
        val tree = walk(
            mapOf(
                "root" to listOf(dir("a", "A")),
                "a" to listOf(dir("root", "Ссылка на корень"), dir("a", "Ссылка на себя"), file("f", "f.txt"))
            )
        )

        val a = tree.dirs.single()
        assertEquals(listOf(REPEATED_FOLDER, REPEATED_FOLDER), a.dirs.map { it.problem })
        assertEquals(1, tree.fileCount)
    }

    @Test
    fun endlessChainOfNewFoldersIsLimited() {
        val tree = walkDocumentTree("0", "root", { id -> listOf(dir((id.toInt() + 1).toString(), "d$id")) }) {
            Uri.parse("content://test/$it")
        }

        var depth = 0
        var current = tree
        while (current.dirs.isNotEmpty()) {
            current = current.dirs.single()
            depth++
        }
        assertEquals(TOO_DEEP, current.problem)
        assertEquals(MAX_TREE_DEPTH + 1, depth)
    }

    @Test
    fun unreadableFolderAndVirtualDocumentAreMarked() {
        val tree = walk(
            mapOf(
                "root" to listOf(dir("locked", "Закрытая"), file("v", "Таблица", flags = Document.FLAG_VIRTUAL_DOCUMENT)),
                "locked" to null
            )
        )

        assertEquals(UNREADABLE_FOLDER, tree.dirs.single().problem)
        assertEquals(VIRTUAL_DOCUMENT, tree.files.single().problem)
    }

    @Test
    fun namesFollowServerRulesWithoutCorrection() {
        for (valid in listOf("Отчёт.pdf", ".nomedia", "a b.txt", "CONSOLE.txt", "x".repeat(255))) {
            assertNull(valid, libraryNameProblem(valid))
        }
        for (invalid in listOf(
            "", "   ", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\u0001b",
            ".", "..", "name.", "name ", "CON", "con.txt", "COM1.log", "LPT¹", "áb".let { "é.txt" },
            "я".repeat(128)
        )) {
            assertEquals(invalid, true, libraryNameProblem(invalid) != null)
        }
    }

    @Test
    fun cameraIsReservedIgnoringCase() {
        assertEquals(true, isReservedRootName("camera"))
        assertEquals(true, isReservedRootName("CAMERA"))
        assertEquals(false, isReservedRootName("Camera2"))
    }
}

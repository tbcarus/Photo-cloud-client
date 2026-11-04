package ru.tbcarus.photo_cloud_client.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tbcarus.photo_cloud_client.transfer.libraryNameProblem
import java.time.Instant

/** Имя архивной версии: отметка UTC до расширения, суффикс номера, ограничения имён Server. */
class ArchiveNamesTest {

    private val stamp = ArchiveNames.stamp(Instant.parse("2026-11-04T12:00:00Z").toEpochMilli())

    @Test
    fun stampIsUtcToTheSecond() {
        assertEquals("20261104-120000", stamp)
        assertEquals("20261104-120000", ArchiveNames.stamp(Instant.parse("2026-11-04T12:00:00.999Z").toEpochMilli()))
    }

    @Test
    fun suffixGoesBeforeExtension() {
        assertEquals("Чертёж_20261104-120000.pdf", ArchiveNames.name("Чертёж.pdf", stamp, 1))
        assertEquals("archive.tar_20261104-120000.gz", ArchiveNames.name("archive.tar.gz", stamp, 1))
        assertEquals("README_20261104-120000", ArchiveNames.name("README", stamp, 1))
        // Имя с точкой в начале не имеет расширения.
        assertEquals(".secret_20261104-120000", ArchiveNames.name(".secret", stamp, 1))
    }

    @Test
    fun nextNameAddsNumberToSameStamp() {
        val first = ArchiveNames.name("Чертёж.pdf", stamp, 1)!!
        val second = ArchiveNames.next("Чертёж.pdf", first)!!
        assertEquals("Чертёж_20261104-120000_2.pdf", second)
        assertEquals("Чертёж_20261104-120000_3.pdf", ArchiveNames.next("Чертёж.pdf", second))
        // Отметка в исходном имени не путается с отметкой операции.
        val dated = "отчёт_20200101-000000_3.txt"
        assertEquals("отчёт_20200101-000000_3_20261104-120000_2.txt", ArchiveNames.next(dated, ArchiveNames.name(dated, stamp, 1)!!))
    }

    @Test
    fun attemptsAreLimited() {
        var name = ArchiveNames.name("a.txt", stamp, 1)
        repeat(ArchiveNames.MAX_ATTEMPTS - 1) { name = ArchiveNames.next("a.txt", name!!) }
        assertEquals("a_20261104-120000_${ArchiveNames.MAX_ATTEMPTS}.txt", name)
        assertNull(ArchiveNames.next("a.txt", name!!))
    }

    @Test
    fun longNameIsShortenedToServerLimitKeepingSuffixAndExtension() {
        val original = "Я".repeat(120) + "😀" + ".pdf"
        assertTrue(original.toByteArray(Charsets.UTF_8).size <= 255)
        val name = ArchiveNames.name(original, stamp, 12)!!
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(name.endsWith("_20261104-120000_12.pdf"))
        assertNull(libraryNameProblem(name))
        // Половина суррогатной пары не остаётся.
        assertTrue(name.none { it.isSurrogate() })
    }

    @Test
    fun extensionTooLongForAnyNameGivesNull() {
        assertNull(ArchiveNames.name("a." + "x".repeat(250), stamp, 1))
    }
}

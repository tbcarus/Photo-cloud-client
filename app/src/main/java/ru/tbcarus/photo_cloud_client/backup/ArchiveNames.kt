package ru.tbcarus.photo_cloud_client.backup

import ru.tbcarus.photo_cloud_client.transfer.MAX_NAME_BYTES
import ru.tbcarus.photo_cloud_client.transfer.libraryNameProblem
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Имя архивной версии: отметка начала архивирования в UTC до расширения, при занятом имени — числовой суффикс.
 * "Чертёж.pdf" → "Чертёж_20261104-120000.pdf", затем "Чертёж_20261104-120000_2.pdf".
 */
internal object ArchiveNames {

    /** Сколько имён одной операции пробуется, прежде чем зафиксировать конфликт. */
    const val MAX_ATTEMPTS = 20

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    private val ARCHIVE_STEM = Regex("""_(\d{8}-\d{6})(?:_(\d+))?$""")

    fun stamp(epochMs: Long): String = STAMP.format(Instant.ofEpochMilli(epochMs))

    /** null — допустимого имени нет: расширение слишком длинное или номер попытки исчерпан. */
    fun name(original: String, stamp: String, attempt: Int): String? {
        if (attempt < 1 || attempt > MAX_ATTEMPTS) return null
        val (stem, ext) = split(original)
        val suffix = "_" + stamp + if (attempt > 1) "_$attempt" else ""
        var base = stem
        // Основа укорачивается по кодовым точкам, суффикс и расширение сохраняются.
        while (base.isNotEmpty() && bytes(base + suffix + ext) > MAX_NAME_BYTES) {
            base = base.dropLast(if (base.length >= 2 && base.last().isLowSurrogate()) 2 else 1)
        }
        val name = base + suffix + ext
        return name.takeIf { libraryNameProblem(it) == null }
    }

    /** Следующее имя той же операции: отметка времени из текущего имени, номер на единицу больше. */
    fun next(original: String, current: String): String? {
        val ext = split(original).second
        val match = ARCHIVE_STEM.find(current.removeSuffix(ext)) ?: return null
        val attempt = match.groupValues[2].toIntOrNull() ?: 1
        return name(original, match.groupValues[1], attempt + 1)
    }

    // Расширение — от последней точки; у имени, начинающегося с точки и без других точек, его нет.
    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size
}

package ru.tbcarus.photo_cloud_client.ui.screens.library

import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private val russian = Locale("ru")
private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private val shortExtension = Regex("[\\p{L}\\p{N}]{1,8}")

/** Путь от ROOT: имя системного корня пользователю ничего не говорит. */
fun libraryPathLabel(path: List<FolderDto>): String =
    path.mapIndexed { index, folder -> if (index == 0) "Корень" else folder.name }.joinToString(" / ")

/** Пояснение к системному контейнеру и активному источнику; у обычных папок его нет. */
fun folderSubtitle(folder: FolderDto): String? = when {
    folder.isAutoUploads -> "Автоматическая загрузка папок"
    folder.isActiveBackupSource -> "Источник автоматической загрузки"
    else -> null
}

/** Вторая строка файла: тип, размер и доступная дата. */
fun librarySubtitle(file: FileItemDto, inCamera: Boolean): String =
    listOfNotNull(
        fileTypeLabel(file),
        file.size?.let(::formatLibrarySize),
        formatLibraryDate(if (inCamera) file.capturedAt else file.uploadedAt)
    ).joinToString(" · ")

fun fileTypeLabel(file: FileItemDto): String {
    val extension = file.originalFilename?.substringAfterLast('.', "").orEmpty()
    // Начальная точка — часть имени (.nomedia), а не расширение.
    val hasExtension = file.originalFilename?.startsWith(".$extension") == false && shortExtension.matches(extension)
    if (hasExtension) return extension.uppercase(russian)
    return when (file.fileType) {
        "IMAGE" -> "Изображение"
        "VIDEO" -> "Видео"
        "AUDIO" -> "Аудио"
        "DOCUMENT" -> "Документ"
        "ARCHIVE" -> "Архив"
        else -> "Файл"
    }
}

fun formatLibrarySize(bytes: Long): String = when {
    bytes >= GIB -> "%.1f ГБ".format(russian, bytes.toDouble() / GIB)
    bytes >= MIB -> "%.1f МБ".format(russian, bytes.toDouble() / MIB)
    bytes >= KIB -> "%.0f КБ".format(russian, bytes.toDouble() / KIB)
    else -> "$bytes Б"
}

/** Дата без пересчёта пояса: Server хранит её без смещения. */
fun formatLibraryDate(value: String?): String? = value?.let {
    try {
        LocalDateTime.parse(it).format(dateFormat)
    } catch (_: DateTimeParseException) {
        null
    }
}

private const val KIB = 1024L
private const val MIB = 1024L * KIB
private const val GIB = 1024L * MIB

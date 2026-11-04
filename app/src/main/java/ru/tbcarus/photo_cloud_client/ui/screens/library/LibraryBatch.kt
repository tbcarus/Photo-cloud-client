package ru.tbcarus.photo_cloud_client.ui.screens.library

import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto

/**
 * Выделение объектов открытой папки [folderId]. Объекты хранятся по типу и ID, а не по позиции: подгрузка страниц
 * их не меняет, один ID учитывается один раз. Пустое выделение оставляет режим открытым до явного выхода.
 */
data class Selection(
    val folderId: Long,
    val folders: Map<Long, FolderDto> = emptyMap(),
    val files: Map<Long, FileItemDto> = emptyMap(),
    // Открыто подтверждение группового удаления.
    val confirmingDelete: Boolean = false
) {
    val size: Int get() = folders.size + files.size
    val isEmpty: Boolean get() = size == 0
    val hasFolders: Boolean get() = folders.isNotEmpty()

    /** Папки, затем файлы, в порядке выделения. */
    val items: List<LibraryItem>
        get() = folders.values.map { LibraryItem.Folder(it) } + files.values.map { LibraryItem.File(it) }

    fun contains(folder: FolderDto): Boolean = folders.containsKey(folder.id)
    fun contains(file: FileItemDto): Boolean = file.id != null && files.containsKey(file.id)

    fun toggle(folder: FolderDto): Selection =
        copy(folders = if (contains(folder)) folders - folder.id else folders + (folder.id to folder))

    fun toggle(file: FileItemDto): Selection {
        val id = file.id ?: return this
        return copy(files = if (files.containsKey(id)) files - id else files + (id to file))
    }

    fun plus(folders: List<FolderDto>, files: List<FileItemDto>): Selection = copy(
        folders = this.folders + folders.associateBy { it.id },
        files = this.files + files.filter { it.id != null }.associateBy { it.id!! }
    )
}

enum class BatchKind { MOVE, COPY, DELETE }

/** Итог одного объекта пакета. */
enum class BatchOutcome {
    /** Server подтвердил операцию. */
    SUCCESS,

    /** Server определённо отказал, объект не изменён. */
    FAILED,

    /** Ответ потерян или не разобран: Server мог выполнить операцию. */
    UNKNOWN,

    /** Запрос по объекту не отправлялся. */
    NOT_PROCESSED
}

data class BatchEntry(val item: LibraryItem, val outcome: BatchOutcome = BatchOutcome.NOT_PROCESSED, val reason: String? = null)

/**
 * Групповая операция: последовательность одиночных запросов Server без атомарности и отката. Состояние только
 * в памяти экрана; после гибели процесса итог не восстанавливается.
 */
data class Batch(
    val id: Int,
    val kind: BatchKind,
    val sourcePath: List<FolderDto>,
    // Путь к папке назначения перемещения и копирования; у удаления null.
    val targetPath: List<FolderDto>?,
    val entries: List<BatchEntry>,
    val running: Boolean = true,
    val stopRequested: Boolean = false,
    // Пакет остановлен самим выполнением: неизвестный результат или отказ авторизации.
    val haltReason: String? = null,
    // Server отказал в доступе: после закрытия итога Library предлагает вход.
    val sessionLost: Boolean = false
) {
    val total: Int get() = entries.size
    val processed: Int get() = entries.count { it.outcome != BatchOutcome.NOT_PROCESSED }
    fun count(outcome: BatchOutcome): Int = entries.count { it.outcome == outcome }
    val current: LibraryItem? get() = if (running) entries.firstOrNull { it.outcome == BatchOutcome.NOT_PROCESSED }?.item else null
}

/** «1 объект», «3 объекта», «7 объектов». */
fun objectsCount(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "объектов"
        mod10 == 1 -> "объект"
        mod10 in 2..4 -> "объекта"
        else -> "объектов"
    }
    return "$count $word"
}

/** «1 выбранный объект», «3 выбранных объекта», «7 выбранных объектов». */
fun selectedObjectsCount(count: Int): String {
    val adjective = if (count % 10 == 1 && count % 100 != 11) "выбранный" else "выбранных"
    return objectsCount(count).replaceFirst(" ", " $adjective ")
}

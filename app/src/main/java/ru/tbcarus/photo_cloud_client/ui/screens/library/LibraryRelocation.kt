package ru.tbcarus.photo_cloud_client.ui.screens.library

import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.transfer.isReservedRootName
import ru.tbcarus.photo_cloud_client.transfer.libraryNameProblem

/** Объект и вид серверной операции. Копирования папки на Server нет. */
sealed interface Relocated {
    val name: String

    data class MoveFile(val file: FileItemDto) : Relocated {
        override val name: String get() = file.originalFilename.orEmpty()
    }

    data class CopyFile(val file: FileItemDto) : Relocated {
        override val name: String get() = file.originalFilename.orEmpty()
    }

    data class MoveFolder(val folder: FolderDto) : Relocated {
        override val name: String get() = folder.name
    }

    /** Групповое перемещение или копирование выделенных объектов одной папки; копируются только файлы. */
    data class Group(val items: List<LibraryItem>, val copy: Boolean) : Relocated {
        override val name: String get() = objectsCount(items.size)
    }
}

/** Одиночная операция того же вида для объекта группы: правила назначения у группы те же, что у каждого объекта. */
private fun LibraryItem.relocated(copy: Boolean): Relocated = when (this) {
    is LibraryItem.File -> if (copy) Relocated.CopyFile(file) else Relocated.MoveFile(file)
    is LibraryItem.Folder -> Relocated.MoveFolder(folder)
}

/**
 * Выбор назначения для перемещения или копирования. Назначение выбирается обычной навигацией Library: путь,
 * список и пагинация общие; [sourcePath] — папка объекта, куда Library возвращается после операции.
 */
data class Relocation(
    val id: Int,
    val item: Relocated,
    val sourcePath: List<FolderDto>,
    val copyName: String = "",
    val confirming: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    // Связь прервалась после отправки: Server мог выполнить операцию. Повтор — только после обновления папки.
    val unknownResult: Boolean = false,
    // Запрос уже отправлялся: при отмене исходная папка перечитывается, а не восстанавливается из памяти.
    val attempted: Boolean = false
) {
    val sourceFolder: FolderDto get() = sourcePath.last()
}

/**
 * Причина, по которой последняя папка [targetPath] не подходит как назначение; null — подходит.
 * Повторяет проверенные правила Server, окончательно решает Server.
 */
fun destinationProblem(relocation: Relocation, targetPath: List<FolderDto>): String? {
    val group = relocation.item as? Relocated.Group
    if (group != null) {
        // Копия группы в исходную папку совпала бы по именам: другое имя задаёт только одиночное копирование.
        if (group.copy && targetPath.last().id == relocation.sourceFolder.id) {
            return "Копии в той же папке совпадут по именам: используйте одиночное копирование с другим именем"
        }
        // Назначение должно подходить каждому объекту, иначе пакет не начинается.
        return group.items.firstNotNullOfOrNull { item ->
            destinationProblem(relocation.copy(item = item.relocated(group.copy)), targetPath)
                ?.let { problem -> if (group.items.size > 1) "«${item.name}»: $problem" else problem }
        }
    }
    val target = targetPath.last()
    val source = relocation.sourceFolder
    if (targetPath.any { it.isActiveBackupSource }) return "Папку изменяет только автоматическое резервирование"
    if (target.isAutoUploads) return "В AutoUploads размещаются только источники резервирования"
    return when (val item = relocation.item) {
        is Relocated.MoveFolder -> when {
            target.isCamera -> "В Camera нельзя поместить папку"
            targetPath.any { it.id == item.folder.id } -> "Нельзя переместить папку в саму себя или во вложенную папку"
            target.id == source.id -> "Папка уже находится здесь"
            else -> null
        }
        is Relocated.MoveFile -> when {
            target.isCamera != source.isCamera -> "Файлы не переносятся между Camera и библиотекой"
            target.id == source.id -> "Файл уже находится здесь"
            else -> null
        }
        is Relocated.CopyFile -> when {
            target.isCamera && !source.isCamera -> "В Camera файлы попадают только через резервирование"
            // Server сравнивает файлы Camera по содержимому: копия всегда совпадёт с исходным.
            target.isCamera -> "Копия в Camera совпадёт с исходным файлом по содержимому"
            else -> null
        }
        is Relocated.Group -> null
    }
}

/**
 * Папка списка, в которую нет смысла входить: она и всё её поддерево не подходят. Остальные папки открываются,
 * даже если сами не подходят (например, AutoUploads с отключёнными источниками).
 */
fun blockedFolderProblem(relocation: Relocation, path: List<FolderDto>, folder: FolderDto): String? {
    val item = relocation.item
    val moved = when (item) {
        is Relocated.MoveFolder -> item.folder.id == folder.id
        is Relocated.Group -> !item.copy && item.items.any { it is LibraryItem.Folder && it.folder.id == folder.id }
        else -> false
    }
    val closed = folder.isCamera || folder.isActiveBackupSource || moved
    return if (closed) destinationProblem(relocation, path + folder) else null
}

/** Имя копии проверяется до запроса; совпадение с другими объектами назначения определяет Server (409). */
fun copyNameProblem(relocation: Relocation, target: FolderDto): String? {
    val name = relocation.copyName
    val original = (relocation.item as? Relocated.CopyFile)?.name ?: return null
    return libraryNameProblem(name)
        ?: "Имя Camera в корне зарезервировано".takeIf { target.isRoot && isReservedRootName(name) }
        ?: "Копия в той же папке должна называться иначе".takeIf {
            target.id == relocation.sourceFolder.id && name.equals(original, ignoreCase = true)
        }
}

/** Имя папки для сообщений: имя системного корня пользователю ничего не говорит. */
fun folderLabel(folder: FolderDto): String = if (folder.isRoot) "Корень" else folder.name

package ru.tbcarus.photo_cloud_client.ui.screens.library

import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.library.thumbnail.Thumbnail

sealed interface LibraryStatus {
    /** Контекст пригоден, загрузка начнётся при открытии экрана. */
    data object Idle : LibraryStatus
    data object Loading : LibraryStatus
    data object Content : LibraryStatus
    data object NoServer : LibraryStatus
    data object NoSession : LibraryStatus
    data class Error(val message: String) : LibraryStatus
}

/** Объект диалога ввода имени. */
sealed interface NameTarget {
    data object NewFolder : NameTarget
    data class Folder(val folder: FolderDto) : NameTarget
    data class File(val file: FileItemDto) : NameTarget
}

/**
 * Открытый диалог создания или переименования в папке [parent]. Введённое имя хранится здесь: после ошибки оно
 * остаётся для правки и повтора. [id] отличает диалоги: ответ на запрос закрытого диалога не применяется к новому.
 */
data class NameEdit(
    val id: Int,
    val target: NameTarget,
    val parent: FolderDto,
    val name: String,
    val saving: Boolean = false,
    val error: String? = null
) {
    val currentName: String?
        get() = when (target) {
            NameTarget.NewFolder -> null
            is NameTarget.Folder -> target.folder.name
            is NameTarget.File -> target.file.originalFilename
        }
}

/** Файл или папка открытого каталога: объект удаления, выделения и групповой операции. */
sealed interface LibraryItem {
    val name: String

    data class File(val file: FileItemDto) : LibraryItem {
        override val name: String get() = file.originalFilename.orEmpty()
    }

    data class Folder(val folder: FolderDto) : LibraryItem {
        override val name: String get() = folder.name
    }
}

/**
 * Подтверждение удаления объекта папки [parent]. [unknownResult] — ответ потерян: Server мог удалить объект,
 * повторное удаление доступно только после обновления каталога.
 */
data class Deletion(
    val id: Int,
    val target: LibraryItem,
    val parent: FolderDto,
    val deleting: Boolean = false,
    val error: String? = null,
    val unknownResult: Boolean = false
)

data class LibraryUiState(
    val status: LibraryStatus = LibraryStatus.Idle,
    // Открытые папки от ROOT до текущей: API не отдаёт путь и не ищет папку по ID.
    val path: List<FolderDto> = emptyList(),
    val folders: List<FolderDto> = emptyList(),
    val files: List<FileItemDto> = emptyList(),
    val hasNext: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val notice: String? = null,
    // Идёт отключение источника открытой папки: повторное нажатие не отправит второй запрос.
    val disabling: Boolean = false,
    // Меняется с каждой загрузкой каталога: новый список начинается с начала.
    val contentVersion: Int = 0,
    val edit: NameEdit? = null,
    // Выбор назначения: живёт поверх навигации и переживает переходы между папками.
    val relocation: Relocation? = null,
    val deletion: Deletion? = null,
    // Режим множественного выделения открытой папки.
    val selection: Selection? = null,
    // Групповая операция: прогресс, затем итог. Переживает перечитывание папки.
    val batch: Batch? = null,
    // Итог успешной операции; ошибки показывает notice.
    val info: String? = null
) {
    val currentFolder: FolderDto? get() = path.lastOrNull()
    val canGoUp: Boolean get() = path.size > 1
    /**
     * AutoUploads и дерево активного источника меняет только автоматическое резервирование. Вложенные папки
     * backupSource не имеют: принадлежность дереву определяет корень источника на открытом пути.
     */
    val inManagedTree: Boolean get() = currentFolder?.isAutoUploads == true || inActiveSource
    /** На открытом пути есть корень активного источника: всё ниже него меняет только резервирование. */
    val inActiveSource: Boolean get() = path.any { it.isActiveBackupSource }
    /** Открыта сама корневая папка активного источника: только у неё Server хранит backupSource. */
    val isSourceRoot: Boolean get() = status == LibraryStatus.Content && currentFolder?.isActiveBackupSource == true
    val picking: Boolean get() = relocation != null
    val selecting: Boolean get() = selection != null
    // Выбор назначения, выделение и групповая операция исключают одиночные изменения.
    private val busy: Boolean get() = picking || selecting || batch != null
    // В Camera файлы попадают только через backup Android.
    val canUpload: Boolean get() =
        status == LibraryStatus.Content && currentFolder?.isCamera == false && !inManagedTree && !busy
    /** Server создаёт папки только в ROOT и USER вне активного дерева: те же папки, что принимают ручную загрузку. */
    val canCreateFolder: Boolean get() = canUpload
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()
    /** Последовательность полноэкранного просмотра: загруженные файлы папки, которые можно показать, в порядке Library. */
    val viewerImages: List<FileItemDto> get() = files.filter(::isViewable)

    /**
     * Системные папки (ROOT, Camera, AutoUploads) и корень активного источника не переименовываются, как и всё
     * внутри активного дерева. Отключённый источник в AutoUploads — обычная USER-папка. Окончательно решает Server.
     */
    fun canRename(folder: FolderDto): Boolean = !busy && changeable(folder)

    /** Файл активного дерева меняет только резервирование; файлы Camera Server переименовывает по своим правилам. */
    fun canRename(file: FileItemDto): Boolean = !busy && changeable(file)

    private fun changeable(folder: FolderDto): Boolean =
        status == LibraryStatus.Content && folder.isUser && !folder.isActiveBackupSource && !inActiveSource

    private fun changeable(file: FileItemDto): Boolean = status == LibraryStatus.Content && file.id != null && !inActiveSource

    /**
     * Выделить можно только то, что допускает групповые операции: системные папки, корень активного источника
     * и всё в активном дереве не выделяются.
     */
    fun canSelect(folder: FolderDto): Boolean = !picking && batch == null && changeable(folder)

    fun canSelect(file: FileItemDto): Boolean = !picking && batch == null && changeable(file)

    /** Перемещается только USER-папка вне активного дерева, в том числе отключённый источник из AutoUploads. */
    fun canMove(folder: FolderDto): Boolean = canRename(folder)

    /** Файл Camera перемещать некуда: Server не переносит файлы между Camera и библиотекой. */
    fun canMove(file: FileItemDto): Boolean = canRename(file) && currentFolder?.isCamera == false

    /** Копию файла Camera можно положить в библиотеку; из активного дерева копирование запрещено. */
    fun canCopy(file: FileItemDto): Boolean = canRename(file)

    /**
     * Удаление разрешено там же, где переименование: Server не удаляет ROOT, Camera, AutoUploads, корень активного
     * источника и всё в его дереве. Файлы Camera и отключённые источники удаляются как обычные объекты.
     */
    fun canDelete(folder: FolderDto): Boolean = canRename(folder)

    fun canDelete(file: FileItemDto): Boolean = canRename(file)

    /** Причина, по которой выделение нельзя переместить; null — можно выбирать назначение. */
    val groupMoveProblem: String?
        get() = when {
            selection?.isEmpty != false -> "Ничего не выбрано"
            currentFolder?.isCamera == true -> "Файлы Camera не перемещаются в библиотеку"
            else -> null
        }

    /** Копирование папок на Server нет: группа копируется, только если в ней одни файлы. */
    val groupCopyProblem: String?
        get() = when {
            selection?.isEmpty != false -> "Ничего не выбрано"
            selection?.hasFolders == true -> "Копировать можно только файлы: копирования папок на Server нет"
            else -> null
        }

    /** Причина, по которой открытая папка не подходит как назначение; null — подтверждение доступно. */
    val pickProblem: String?
        get() {
            val relocation = relocation ?: return null
            if (status != LibraryStatus.Content || path.isEmpty()) return "Папка загружается"
            return destinationProblem(relocation, path)
        }
}

/** Просматриваются JPEG, PNG и WebP (MIME Server по содержимому) с ID и checksum: по нему кешируется оригинал. */
fun isViewable(file: FileItemDto): Boolean =
    Thumbnail.isSupported(file.mimeType) && (file.id ?: 0) > 0 && !file.checksum.isNullOrBlank()

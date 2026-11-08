package ru.tbcarus.photo_cloud_client.ui.screens.library

import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.api.models.SharedResourceDto
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

/**
 * Раздел «Доступно мне» — виртуальный, папкой Server не является. Пока путь пуст, показывается список прямых
 * доступов [entries]; внутри предоставленного дерева путь начинается с его корня и выше него не поднимается.
 */
data class SharedBrowse(
    val entries: List<SharedResourceDto> = emptyList(),
    val removal: SharedRemoval? = null
)

/** Подтверждение «Убрать из моей библиотеки»: удаляется только доступ текущего пользователя. */
data class SharedRemoval(
    val id: Int,
    val entry: SharedResourceDto,
    val removing: Boolean = false,
    val error: String? = null
)

/**
 * Корень предоставленного дерева. Тип папки Server в списке доступов не передаёт: метка отличает её от системных
 * папок, файлы в ней сортируются по имени.
 */
fun sharedRootFolder(entry: SharedResourceDto): FolderDto =
    FolderDto(entry.resourceId, parentId = null, name = entry.name.orEmpty(), folderType = SHARED_ROOT_TYPE)

const val SHARED_ROOT_TYPE = "SHARED"

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
    val info: String? = null,
    // Открыт раздел «Доступно мне»; null — собственная библиотека.
    val shared: SharedBrowse? = null,
    // Открыт раздел «Предоставлено мной»: его список хранит LibrarySharing, папок и файлов в нём нет.
    val granted: Boolean = false
) {
    val currentFolder: FolderDto? get() = path.lastOrNull()
    /** Предоставленные ресурсы только читаются: режим задаёт контекст Library, а не тип или поля папки. */
    val inShared: Boolean get() = shared != null
    /** Открыт сам список «Доступно мне», а не предоставленная папка. */
    val atSharedList: Boolean get() = inShared && path.isEmpty()
    val inGranted: Boolean get() = granted
    // В разделе «Доступно мне» Back ведёт вверх по предоставленному дереву, затем к списку, затем в библиотеку;
    // из «Предоставлено мной» — сразу в библиотеку.
    val canGoUp: Boolean get() = inShared || inGranted || path.size > 1
    /** Вход в «Доступно мне» — в корне собственной библиотеки, вне выбора назначения, выделения и пакета. */
    val showSharedEntry: Boolean get() =
        !inShared && !inGranted && status == LibraryStatus.Content && path.size == 1 && !picking && !selecting && batch == null
    /**
     * AutoUploads и дерево активного источника меняет только автоматическое резервирование. Вложенные папки
     * backupSource не имеют: принадлежность дереву определяет корень источника на открытом пути.
     */
    val inManagedTree: Boolean get() = currentFolder?.isAutoUploads == true || inActiveSource
    /** На открытом пути есть корень активного источника: всё ниже него меняет только резервирование. */
    val inActiveSource: Boolean get() = path.any { it.isActiveBackupSource }
    /** Открыта сама корневая папка активного источника: только у неё Server хранит backupSource. */
    val isSourceRoot: Boolean get() = !inShared && status == LibraryStatus.Content && currentFolder?.isActiveBackupSource == true
    val picking: Boolean get() = relocation != null
    val selecting: Boolean get() = selection != null
    // Выбор назначения, выделение и групповая операция исключают одиночные изменения.
    private val busy: Boolean get() = picking || selecting || batch != null
    // В Camera файлы попадают только через backup Android.
    val canUpload: Boolean get() =
        !inShared && status == LibraryStatus.Content && currentFolder?.isCamera == false && !inManagedTree && !busy
    /** Server создаёт папки только в ROOT и USER вне активного дерева: те же папки, что принимают ручную загрузку. */
    val canCreateFolder: Boolean get() = canUpload
    val isEmpty: Boolean get() = if (atSharedList) shared?.entries.isNullOrEmpty() else folders.isEmpty() && files.isEmpty()
    /** Последовательность полноэкранного просмотра: загруженные файлы папки, которые можно показать, в порядке Library. */
    val viewerImages: List<FileItemDto> get() = files.filter(::isViewable)

    /**
     * Системные папки (ROOT, Camera, AutoUploads) и корень активного источника не переименовываются, как и всё
     * внутри активного дерева. Отключённый источник в AutoUploads — обычная USER-папка. Окончательно решает Server.
     */
    fun canRename(folder: FolderDto): Boolean = !busy && changeable(folder)

    /** Файл активного дерева меняет только резервирование; файлы Camera Server переименовывает по своим правилам. */
    fun canRename(file: FileItemDto): Boolean = !busy && changeable(file)

    // Чужие объекты в «Доступно мне» не меняются и не выделяются: переименование, перемещение, копирование, удаление.
    private fun changeable(folder: FolderDto): Boolean =
        !inShared && !inGranted && status == LibraryStatus.Content && folder.isUser && !folder.isActiveBackupSource && !inActiveSource

    private fun changeable(file: FileItemDto): Boolean =
        !inShared && !inGranted && status == LibraryStatus.Content && file.id != null && !inActiveSource

    /**
     * Выделить можно только то, что допускает групповые операции: системные папки, корень активного источника
     * и всё в активном дереве не выделяются.
     */
    fun canSelect(folder: FolderDto): Boolean = !picking && batch == null && changeable(folder)

    fun canSelect(file: FileItemDto): Boolean = !picking && batch == null && changeable(file)

    /**
     * «Поделиться» — только собственные объекты: USER-папки (в том числе корни и вложенные папки источников) и Camera.
     * ROOT и AUTO_UPLOADS Server не предоставляет; в «Доступно мне» объекты чужие.
     */
    fun canShare(folder: FolderDto): Boolean =
        !inShared && !inGranted && status == LibraryStatus.Content && !busy && (folder.isUser || folder.isCamera)

    fun canShare(file: FileItemDto): Boolean =
        !inShared && !inGranted && status == LibraryStatus.Content && !busy && file.id != null

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

package ru.tbcarus.photo_cloud_client.ui.screens.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.backup.BackupAccount
import ru.tbcarus.photo_cloud_client.backup.BackupSourceRepository
import ru.tbcarus.photo_cloud_client.backup.SourceActionResult
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.library.LibraryRepository
import ru.tbcarus.photo_cloud_client.library.LibraryResult
import ru.tbcarus.photo_cloud_client.library.LibrarySession
import ru.tbcarus.photo_cloud_client.library.librarySessionFlow
import ru.tbcarus.photo_cloud_client.transfer.isReservedRootName
import ru.tbcarus.photo_cloud_client.transfer.libraryNameProblem
import ru.tbcarus.photo_cloud_client.transfer.TransferKind
import ru.tbcarus.photo_cloud_client.transfer.TransferManager
import ru.tbcarus.photo_cloud_client.transfer.TransferState
import ru.tbcarus.photo_cloud_client.transfer.UploadDestination
import javax.inject.Inject

/**
 * Просмотр серверной библиотеки. Живёт на уровне Activity: нижняя панель пересоздаёт записи маршрутов,
 * а открытая папка и загруженный список должны пережить переключение вкладок.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    baseUrlProvider: BaseUrlProvider,
    tokenStorage: TokenStorage,
    private val transfers: TransferManager,
    private val sources: BackupSourceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Вид — состояние экрана, а не каталога: загрузка папки и смена контекста его не сбрасывают. Только в памяти процесса.
    private val _gridView = MutableStateFlow(false)
    /** true — плитка, false — список (по умолчанию). */
    val gridView: StateFlow<Boolean> = _gridView.asStateFlow()

    /**
     * Переключает вид. Индексы элементов в списке и плитке совпадают (папки, затем файлы), поэтому видимая строка
     * сохраняется; смещение внутри строки у видов разное и сбрасывается. Каталог заново не запрашивается.
     */
    fun toggleView(firstVisibleIndex: Int) {
        saveScroll(firstVisibleIndex, 0)
        _gridView.value = !_gridView.value
    }

    // Открытый в полноэкранном просмотре файл; null — просмотр закрыт. Library под ним сохраняет папку и прокрутку.
    private val _viewerFileId = MutableStateFlow<Long?>(null)
    val viewerFileId: StateFlow<Long?> = _viewerFileId.asStateFlow()

    /** Открывает просмотр изображения. В режиме выделения и при выборе назначения нажатие на файл работает по-прежнему. */
    fun openViewer(file: FileItemDto) {
        val state = _uiState.value
        if (state.selecting || state.picking || !isViewable(file)) return
        _viewerFileId.value = file.id
    }

    /**
     * Просмотр перешёл к [fileId]. Вблизи конца загруженной части подгружается следующая страница тем же механизмом,
     * что у списка; ошибка подгрузки повторяется только кнопкой Library.
     */
    fun onViewerPage(fileId: Long) {
        if (_viewerFileId.value == null) return
        _viewerFileId.value = fileId
        val images = _uiState.value.viewerImages
        val index = images.indexOfFirst { it.id == fileId }
        if (index >= 0 && images.size - index <= VIEWER_PREFETCH) onListEndReached()
    }

    fun closeViewer() {
        _viewerFileId.value = null
    }

    /** Ручная передача живёт дольше экрана: состояние берётся у [TransferManager]. */
    val transferState: StateFlow<TransferState> = transfers.state

    private var context: LibrarySession? = null
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var nextPage = 0

    // Номер текущей загрузки: ответ с другим номером устарел, даже если запрос не удалось прервать.
    private var generation = 0

    // Итог передачи, по которому папка уже обновлена.
    private var handledTransfer = -1L

    private var editSerial = 0
    private var relocationSerial = 0
    private var deletionSerial = 0
    private var batchSerial = 0

    // Исходная папка выбора назначения: отмена возвращает её список и прокрутку без запросов.
    private var relocationSource: RelocationSource? = null

    private class RelocationSource(val state: LibraryUiState, val nextPage: Int, val scrollIndex: Int, val scrollOffset: Int)

    var scrollIndex = 0
        private set
    var scrollOffset = 0
        private set

    init {
        // Контекст задают адрес и subject: refresh меняет токены, но не пользователя.
        viewModelScope.launch {
            librarySessionFlow(baseUrlProvider, tokenStorage).collect(::onContextChanged)
        }
        viewModelScope.launch { transfers.state.collect(::onTransferState) }
    }

    private fun onContextChanged(newContext: LibrarySession) {
        // Отказ Server при том же пользователе: после нового входа каталог снова можно запросить.
        if (newContext == context && _uiState.value.status != LibraryStatus.NoSession) return
        context = newContext
        // Просмотр относится к прежнему серверу и пользователю: закрывается вместе с отменой его загрузки.
        closeViewer()
        cancelLoads()
        relocationSource = null
        resetScroll()
        val status = when {
            newContext.baseUrl.isBlank() -> LibraryStatus.NoServer
            newContext.user == null -> LibraryStatus.NoSession
            else -> LibraryStatus.Idle
        }
        _uiState.update { LibraryUiState(status = status, contentVersion = it.contentVersion + 1) }
    }

    /** Открытие экрана: загружает корень только в начале работы или после смены контекста. */
    fun ensureLoaded() {
        if (_uiState.value.status == LibraryStatus.Idle) loadRoot()
    }

    fun openFolder(folder: FolderDto) = loadFolder(_uiState.value.path + folder)

    fun navigateUp() {
        val path = _uiState.value.path
        if (path.size > 1) loadFolder(path.dropLast(1))
    }

    /** Обновление и повтор: каталог и пагинация начинаются заново. */
    fun refresh() {
        val state = _uiState.value
        if (state.status == LibraryStatus.NoServer || state.status == LibraryStatus.NoSession) return
        // После неизвестного результата повтор доступен только с обновлённым каталогом перед глазами.
        updateRelocation {
            if (it.unknownResult) it.copy(unknownResult = false, error = "Проверьте, выполнена ли операция, прежде чем повторять") else it
        }
        if (state.path.isEmpty()) loadRoot() else loadFolder(state.path)
    }

    /** Прокрутка к концу списка. После ошибки новая попытка — только по кнопке. */
    fun onListEndReached() {
        if (_uiState.value.loadMoreError == null) loadNextPage()
    }

    fun loadNextPage() {
        val state = _uiState.value
        val folder = state.currentFolder ?: return
        if (state.status != LibraryStatus.Content || !state.hasNext || state.isLoadingMore) return

        val page = nextPage
        val current = generation
        _uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
        loadMoreJob = viewModelScope.launch {
            val result = repository.getFiles(folder, page)
            if (current != generation) return@launch
            when (result) {
                is LibraryResult.Success -> {
                    nextPage = page + 1
                    _uiState.update {
                        it.copy(
                            files = mergeFiles(it.files, result.value.items),
                            hasNext = result.value.hasNext,
                            isLoadingMore = false
                        )
                    }
                }
                is LibraryResult.NetworkError, is LibraryResult.ServerError -> _uiState.update {
                    it.copy(isLoadingMore = false, loadMoreError = failureText(result as LibraryResult.Failure))
                }
                is LibraryResult.Failure -> applyFailure(result, state.path)
            }
        }
    }

    /** Папка для ручной загрузки: текущая, кроме Camera. */
    fun uploadDestination(): UploadDestination? {
        val state = _uiState.value
        val folder = state.currentFolder ?: return null
        return if (state.canUpload) UploadDestination(folder.id, folder.isRoot) else null
    }

    fun uploadFiles(destination: UploadDestination, uris: List<Uri>) {
        transfers.uploadFiles(destination, uris)
    }

    fun uploadFolder(destination: UploadDestination, treeUri: Uri) {
        transfers.uploadFolder(destination, treeUri)
    }

    /**
     * Отключение источника открытой папки тем же механизмом, что в Settings: достаточно ID папки и текущей сессии,
     * локальная запись источника не нужна. После 204 папка остаётся открытой уже как обычная.
     */
    fun disableSource() {
        val state = _uiState.value
        val folder = state.currentFolder ?: return
        val session = context ?: return
        val user = session.user ?: return
        if (!state.isSourceRoot || state.disabling) return
        val current = generation
        _uiState.update { it.copy(disabling = true, notice = null) }
        viewModelScope.launch {
            val result = sources.disable(BackupAccount(session.baseUrl, user), folder.id, folder.backupSource)
            // Пользователь ушёл из папки или сменилась сессия: Room уже обновлён, экран показывает другое.
            if (current != generation) return@launch
            when (result) {
                SourceActionResult.Done -> {
                    val path = _uiState.value.path
                    loadFolder(path.dropLast(1) + folder.copy(backupSource = null))
                }
                is SourceActionResult.Failed -> _uiState.update { it.copy(disabling = false, notice = result.message) }
                is SourceActionResult.NotConnected -> _uiState.update { it.copy(disabling = false, notice = result.message) }
            }
        }
    }

    /** «Создать папку» в открытой папке: только там, где Server принимает обычное создание. */
    fun startCreateFolder() {
        val state = _uiState.value
        val folder = state.currentFolder ?: return
        if (state.canCreateFolder && state.edit == null) openEdit(NameTarget.NewFolder, folder, name = "")
    }

    fun startRename(folder: FolderDto) {
        val state = _uiState.value
        val parent = state.currentFolder ?: return
        if (state.canRename(folder) && state.edit == null) openEdit(NameTarget.Folder(folder), parent, folder.name)
    }

    fun startRename(file: FileItemDto) {
        val state = _uiState.value
        val parent = state.currentFolder ?: return
        if (state.canRename(file) && state.edit == null) {
            openEdit(NameTarget.File(file), parent, file.originalFilename.orEmpty())
        }
    }

    fun onEditNameChange(name: String) = updateEdit { if (it.saving) it else it.copy(name = name, error = null) }

    /** Пока запрос выполняется, диалог не закрывается: его итог должен дойти до списка. */
    fun dismissEdit() {
        _uiState.update { if (it.edit?.saving == true) it else it.copy(edit = null) }
    }

    /**
     * Имя отправляется как введено: Server не исправляет его, совпадение не разрешается автоматически.
     * Объект появляется в списке только после ответа Server, перечитыванием открытой папки.
     */
    fun submitEdit() {
        val edit = _uiState.value.edit ?: return
        if (edit.saving) return
        if (edit.name == edit.currentName) {
            dismissEdit()
            return
        }
        val problem = libraryNameProblem(edit.name)
            ?: "Имя Camera в корне зарезервировано".takeIf { edit.parent.isRoot && isReservedRootName(edit.name) }
        if (problem != null) {
            updateEdit { it.copy(error = problem) }
            return
        }
        val target = edit.target
        val fileId = (target as? NameTarget.File)?.file?.id
        if (target is NameTarget.File && fileId == null) return
        updateEdit { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = when (target) {
                NameTarget.NewFolder -> repository.createFolder(edit.parent.id, edit.name)
                is NameTarget.Folder -> repository.renameFolder(target.folder.id, edit.name)
                is NameTarget.File -> repository.renameFile(fileId!!, edit.name)
            }
            // Сменилась сессия: состояние уже сброшено, диалога нет.
            if (_uiState.value.edit?.id != edit.id) return@launch
            when (result) {
                is LibraryResult.Success -> {
                    _uiState.update { it.copy(edit = null) }
                    reloadParent(edit.parent, notice = null)
                }
                LibraryResult.Unauthorized -> applyFailure(LibraryResult.Unauthorized, _uiState.value.path)
                // Объекта или папки уже нет: список устарел, перечитывается.
                LibraryResult.NotFound -> {
                    _uiState.update { it.copy(edit = null) }
                    reloadParent(edit.parent, notFoundText(target))
                }
                is LibraryResult.Failure -> updateEdit { it.copy(saving = false, error = failureText(result)) }
            }
        }
    }

    private fun openEdit(target: NameTarget, parent: FolderDto, name: String) {
        _uiState.update { it.copy(edit = NameEdit(++editSerial, target, parent, name)) }
    }

    private fun updateEdit(change: (NameEdit) -> NameEdit) {
        _uiState.update { state -> state.edit?.let { state.copy(edit = change(it)) } ?: state }
    }

    // Только первая страница с начала: новое место объекта задаёт сортировка Server.
    private fun reloadParent(parent: FolderDto, notice: String?, info: String? = null) {
        val state = _uiState.value
        if (state.currentFolder?.id == parent.id) loadFolder(state.path, notice, info)
    }

    private fun notFoundText(target: NameTarget): String = when (target) {
        NameTarget.NewFolder -> "Папка не найдена"
        is NameTarget.Folder -> "Папка «${target.folder.name}» не найдена: возможно, она удалена или перемещена"
        is NameTarget.File -> "Файл «${target.file.originalFilename.orEmpty()}» не найден: возможно, он удалён или перемещён"
    }

    fun startMove(file: FileItemDto) {
        if (_uiState.value.canMove(file)) startRelocation(Relocated.MoveFile(file))
    }

    fun startCopy(file: FileItemDto) {
        if (_uiState.value.canCopy(file)) startRelocation(Relocated.CopyFile(file))
    }

    fun startMove(folder: FolderDto) {
        if (_uiState.value.canMove(folder)) startRelocation(Relocated.MoveFolder(folder))
    }

    /**
     * Выбор назначения начинается в папке объекта: соседние и родительские папки рядом, корень — по пути вверх.
     * Список исходной папки запоминается, чтобы отмена вернула его без запросов.
     */
    private fun startRelocation(item: Relocated) {
        val state = _uiState.value
        if (state.edit != null || state.deletion != null || state.picking || state.batch != null) return
        // Выделение сохраняется вместе с исходным списком: отмена выбора назначения вернёт и его.
        relocationSource = RelocationSource(state, nextPage, scrollIndex, scrollOffset)
        val copyName = (item as? Relocated.CopyFile)?.name.orEmpty()
        _uiState.update {
            it.copy(
                relocation = Relocation(++relocationSerial, item, state.path, copyName),
                selection = null,
                notice = null,
                info = null
            )
        }
    }

    /** Отмена ничего не меняет на Server; если запрос уже отправлялся, исходная папка перечитывается. */
    fun cancelRelocation() {
        val relocation = _uiState.value.relocation ?: return
        if (relocation.saving) return
        returnToSource(relocation, reload = relocation.attempted, info = null)
    }

    /** «Переместить сюда» / «Копировать сюда»: итог показывается до отправки. */
    fun requestRelocationConfirm() {
        val state = _uiState.value
        val relocation = state.relocation ?: return
        if (relocation.saving || relocation.unknownResult || state.pickProblem != null) return
        updateRelocation { it.copy(confirming = true, error = null) }
    }

    fun dismissRelocationConfirm() = updateRelocation { if (it.saving) it else it.copy(confirming = false, error = null) }

    fun onCopyNameChange(name: String) = updateRelocation { if (it.saving) it else it.copy(copyName = name, error = null) }

    /** Один запрос за подтверждение; при неизвестном результате автоматического повтора нет. */
    fun confirmRelocation() {
        val state = _uiState.value
        val relocation = state.relocation ?: return
        val target = state.currentFolder ?: return
        if (!relocation.confirming || relocation.saving || relocation.unknownResult || state.pickProblem != null) return
        copyNameProblem(relocation, target)?.let { problem ->
            updateRelocation { it.copy(error = problem) }
            return
        }
        val item = relocation.item
        if (item is Relocated.Group) {
            // Группа выполняется пакетом из исходной папки: назначение уже проверено для каждого объекта.
            val targetPath = state.path
            returnToSource(relocation, reload = false, info = null)
            startBatch(if (item.copy) BatchKind.COPY else BatchKind.MOVE, item.items, relocation.sourcePath, targetPath)
            return
        }
        val fileId = when (item) {
            is Relocated.MoveFile -> item.file.id
            is Relocated.CopyFile -> item.file.id
            is Relocated.MoveFolder -> item.folder.id
            is Relocated.Group -> null
        } ?: return
        updateRelocation { it.copy(saving = true, attempted = true, error = null) }
        viewModelScope.launch {
            val result = when (item) {
                is Relocated.MoveFile -> repository.moveFile(fileId, target.id)
                is Relocated.CopyFile -> repository.copyFile(fileId, target.id, relocation.copyName)
                is Relocated.MoveFolder -> repository.moveFolder(fileId, target.id)
                is Relocated.Group -> return@launch
            }
            // Сменилась сессия: состояние уже сброшено.
            if (_uiState.value.relocation?.id != relocation.id) return@launch
            when (result) {
                is LibraryResult.Success -> {
                    // Копия в другую папку исходную не меняет: её список возвращается без запроса.
                    val changesSource = item !is Relocated.CopyFile || target.id == relocation.sourceFolder.id
                    returnToSource(relocation, reload = changesSource, info = successText(item, relocation.copyName, target))
                }
                LibraryResult.Unauthorized -> applyFailure(LibraryResult.Unauthorized, _uiState.value.path)
                LibraryResult.NotFound -> returnToSource(relocation, reload = true, info = null, notice = NOT_FOUND_TEXT)
                is LibraryResult.NetworkError -> markUnknown("Связь прервалась (${result.message})")
                is LibraryResult.UnknownResult -> markUnknown("Ответ сервера не разобран (${result.message})")
                is LibraryResult.Failure -> updateRelocation { it.copy(saving = false, error = failureText(result)) }
            }
        }
    }

    private fun returnToSource(relocation: Relocation, reload: Boolean, info: String?, notice: String? = null) {
        val saved = relocationSource
        relocationSource = null
        if (reload || saved == null) {
            loadFolder(relocation.sourcePath, notice, info, keepRelocation = false)
            return
        }
        cancelLoads()
        nextPage = saved.nextPage
        saveScroll(saved.scrollIndex, saved.scrollOffset)
        _uiState.update {
            saved.state.copy(relocation = null, info = info, notice = notice, contentVersion = it.contentVersion + 1)
        }
    }

    private fun updateRelocation(change: (Relocation) -> Relocation) {
        _uiState.update { state -> state.relocation?.let { state.copy(relocation = change(it)) } ?: state }
    }

    private fun successText(item: Relocated, copyName: String, target: FolderDto): String = when (item) {
        is Relocated.MoveFile -> "Файл «${item.name}» перемещён в «${folderLabel(target)}»"
        is Relocated.CopyFile -> "Копия «$copyName» создана в «${folderLabel(target)}»"
        is Relocated.MoveFolder -> "Папка «${item.name}» перемещена в «${folderLabel(target)}»"
        is Relocated.Group -> ""
    }

    // Server мог выполнить операцию: выбор назначения сохраняется, повтор — только после обновления папки.
    private fun markUnknown(reason: String) = updateRelocation {
        it.copy(
            saving = false,
            confirming = false,
            unknownResult = true,
            error = "$reason, результат неизвестен. Обновите папку и проверьте, выполнена ли операция, прежде чем повторять."
        )
    }

    fun startDelete(file: FileItemDto) {
        val state = _uiState.value
        if (state.canDelete(file) && file.id != null) openDeletion(LibraryItem.File(file))
    }

    fun startDelete(folder: FolderDto) {
        if (_uiState.value.canDelete(folder)) openDeletion(LibraryItem.Folder(folder))
    }

    /** Пока запрос выполняется, диалог не закрывается: его итог должен дойти до списка. */
    fun dismissDelete() {
        _uiState.update { if (it.deletion?.deleting == true) it else it.copy(deletion = null) }
    }

    /**
     * Один DELETE на подтверждение. Строка исчезает только после ответа Server, перечитыванием открытой папки.
     * При неизвестном результате запрос не повторяется: сначала обновление каталога.
     */
    fun confirmDelete() {
        val deletion = _uiState.value.deletion ?: return
        if (deletion.deleting || deletion.unknownResult) return
        updateDeletion { it.copy(deleting = true, error = null) }
        viewModelScope.launch {
            val result = when (val target = deletion.target) {
                is LibraryItem.File -> repository.deleteFile(target.file.id ?: return@launch)
                is LibraryItem.Folder -> repository.deleteFolder(target.folder.id)
            }
            // Сменилась сессия: состояние уже сброшено, интерфейс другого аккаунта не меняется.
            if (_uiState.value.deletion?.id != deletion.id) return@launch
            when (result) {
                is LibraryResult.Success -> {
                    _uiState.update { it.copy(deletion = null) }
                    reloadParent(deletion.parent, notice = null, info = deletedText(deletion.target))
                }
                LibraryResult.Unauthorized -> applyFailure(LibraryResult.Unauthorized, _uiState.value.path)
                LibraryResult.NotFound -> {
                    _uiState.update { it.copy(deletion = null) }
                    reloadParent(deletion.parent, notice = "«${deletion.target.name}» не найден: возможно, он уже удалён")
                }
                is LibraryResult.NetworkError -> markDeleteUnknown("Связь прервалась (${result.message})")
                is LibraryResult.UnknownResult -> markDeleteUnknown("Ответ сервера не разобран (${result.message})")
                is LibraryResult.Failure -> updateDeletion { it.copy(deleting = false, error = failureText(result)) }
            }
        }
    }

    /** «Обновить» после неизвестного результата: диалог закрывается, каталог показывает фактическое состояние. */
    fun refreshAfterDelete() {
        if (_uiState.value.deletion?.deleting == true) return
        _uiState.update { it.copy(deletion = null) }
        refresh()
    }

    private fun openDeletion(target: LibraryItem) {
        val state = _uiState.value
        val parent = state.currentFolder ?: return
        if (state.edit != null || state.deletion != null || state.picking) return
        _uiState.update { it.copy(deletion = Deletion(++deletionSerial, target, parent), notice = null, info = null) }
    }

    private fun updateDeletion(change: (Deletion) -> Deletion) {
        _uiState.update { state -> state.deletion?.let { state.copy(deletion = change(it)) } ?: state }
    }

    private fun markDeleteUnknown(reason: String) = updateDeletion {
        it.copy(
            deleting = false,
            unknownResult = true,
            error = "$reason, результат неизвестен. Обновите папку, чтобы увидеть, удалён ли объект."
        )
    }

    private fun deletedText(target: LibraryItem): String = when (target) {
        is LibraryItem.File -> "Файл «${target.name}» удалён"
        is LibraryItem.Folder -> "Папка «${target.name}» удалена"
    }

    // --- Множественное выделение и групповые операции ---

    /**
     * Долгое нажатие и нажатие в режиме выделения: первый объект открывает режим, следующие переключаются.
     * Недоступные групповым операциям объекты не выделяются.
     */
    fun toggleSelection(folder: FolderDto) {
        val state = _uiState.value
        if (state.canSelect(folder)) updateSelection(state) { it.toggle(folder) }
    }

    fun toggleSelection(file: FileItemDto) {
        val state = _uiState.value
        if (state.canSelect(file)) updateSelection(state) { it.toggle(file) }
    }

    /** Только уже загруженные строки: каталог ради выделения не догружается. */
    fun selectLoaded() {
        val state = _uiState.value
        if (state.selection == null) return
        updateSelection(state) { selection ->
            selection.plus(state.folders.filter(state::canSelect), state.files.filter(state::canSelect))
        }
    }

    fun clearSelection() {
        _uiState.update { state -> state.selection?.let { state.copy(selection = Selection(it.folderId)) } ?: state }
    }

    fun exitSelection() {
        _uiState.update { it.copy(selection = null) }
    }

    fun startGroupMove() {
        val state = _uiState.value
        val selection = state.selection ?: return
        if (state.groupMoveProblem == null) startRelocation(Relocated.Group(selection.items, copy = false))
    }

    fun startGroupCopy() {
        val state = _uiState.value
        val selection = state.selection ?: return
        if (state.groupCopyProblem == null) startRelocation(Relocated.Group(selection.items, copy = true))
    }

    fun requestGroupDelete() {
        _uiState.update { state ->
            val selection = state.selection?.takeIf { !it.isEmpty } ?: return@update state
            state.copy(selection = selection.copy(confirmingDelete = true))
        }
    }

    fun dismissGroupDelete() {
        _uiState.update { state -> state.selection?.let { state.copy(selection = it.copy(confirmingDelete = false)) } ?: state }
    }

    /** До подтверждения ничего не отправляется; число в подтверждении — выделенные объекты, без их содержимого. */
    fun confirmGroupDelete() {
        val state = _uiState.value
        val selection = state.selection ?: return
        if (!selection.confirmingDelete || selection.isEmpty || state.batch != null) return
        startBatch(BatchKind.DELETE, selection.items, state.path, targetPath = null)
    }

    /** «Остановить»: следующие объекты не отправляются, уже отправленный запрос завершается и учитывается. */
    fun stopBatch() = updateBatch { if (it.running) it.copy(stopRequested = true) else it }

    /** Закрытие итога. Если Server отказал в доступе, Library предлагает вход. */
    fun dismissBatch() {
        val batch = _uiState.value.batch ?: return
        if (batch.running) return
        _uiState.update { it.copy(batch = null) }
        if (batch.sessionLost) applyFailure(LibraryResult.Unauthorized, _uiState.value.path)
    }

    /** Проверка назначения после неизвестного результата: папка открывается заново с первой страницы. */
    fun openBatchTarget() {
        val batch = _uiState.value.batch ?: return
        val target = batch.targetPath ?: return
        if (batch.running || batch.sessionLost) return
        _uiState.update { it.copy(batch = null) }
        loadFolder(target)
    }

    private fun updateSelection(state: LibraryUiState, change: (Selection) -> Selection) {
        val folderId = state.currentFolder?.id ?: return
        if (state.edit != null || state.deletion != null) return
        val selection = state.selection ?: Selection(folderId)
        _uiState.update { it.copy(selection = change(selection), notice = null, info = null) }
    }

    private fun updateBatch(change: (Batch) -> Batch) {
        _uiState.update { state -> state.batch?.let { state.copy(batch = change(it)) } ?: state }
    }

    /** Набор и сессия фиксируются до первого запроса; выделение закрывается, итог показывается до закрытия. */
    private fun startBatch(kind: BatchKind, items: List<LibraryItem>, sourcePath: List<FolderDto>, targetPath: List<FolderDto>?) {
        val session = context ?: return
        val batch = Batch(++batchSerial, kind, sourcePath, targetPath, items.map { BatchEntry(it) })
        _uiState.update { it.copy(batch = batch, selection = null, notice = null, info = null) }
        viewModelScope.launch { runBatch(batch.id, session) }
    }

    /**
     * Последовательные одиночные запросы. Определённый отказ объекта не мешает остальным; неизвестный результат
     * и отказ в доступе останавливают пакет. Повторов и отката нет.
     */
    private suspend fun runBatch(id: Int, session: LibrarySession) {
        val count = _uiState.value.batch?.takeIf { it.id == id }?.total ?: return
        for (index in 0 until count) {
            val batch = _uiState.value.batch?.takeIf { it.id == id } ?: return
            // Сессия сменилась: состояние сброшено, запросы старой сессии не отправляются.
            if (context != session) return
            if (batch.stopRequested) break
            val entry = batch.entries[index]
            val result = perform(batch, entry.item)
            if (_uiState.value.batch?.id != id) return
            val (outcome, reason) = batchOutcome(batch.kind, result)
            updateBatch { current ->
                current.copy(entries = current.entries.toMutableList().also { it[index] = entry.copy(outcome = outcome, reason = reason) })
            }
            when (result) {
                // 5xx не гарантирует, что операция не выполнена: как и потерянный ответ, останавливает пакет.
                is LibraryResult.NetworkError, is LibraryResult.UnknownResult, is LibraryResult.ServerError -> {
                    updateBatch { it.copy(haltReason = "Результат последней операции неизвестен: пакет остановлен") }
                    break
                }
                LibraryResult.Unauthorized -> {
                    updateBatch { it.copy(haltReason = "Server отказал в доступе: пакет остановлен", sessionLost = true) }
                    break
                }
                else -> Unit
            }
        }
        val batch = _uiState.value.batch?.takeIf { it.id == id } ?: return
        updateBatch { it.copy(running = false) }
        // Копия создаётся в другой папке: исходная не изменилась.
        if (!batch.sessionLost && batch.kind != BatchKind.COPY && batch.processed > 0) loadFolder(batch.sourcePath)
    }

    private suspend fun perform(batch: Batch, item: LibraryItem): LibraryResult<*> {
        val target = batch.targetPath?.last()
        return when (item) {
            is LibraryItem.File -> {
                val id = item.file.id ?: return LibraryResult.Rejected("Нет ID файла")
                when (batch.kind) {
                    BatchKind.DELETE -> repository.deleteFile(id)
                    BatchKind.MOVE -> repository.moveFile(id, target!!.id)
                    // Копия получает исходное имя; совпадение — отказ этого объекта, без суффиксов.
                    BatchKind.COPY -> repository.copyFile(id, target!!.id, item.file.originalFilename.orEmpty())
                }
            }
            is LibraryItem.Folder -> when (batch.kind) {
                BatchKind.DELETE -> repository.deleteFolder(item.folder.id)
                BatchKind.MOVE -> repository.moveFolder(item.folder.id, target!!.id)
                BatchKind.COPY -> LibraryResult.Rejected("Копирования папок на Server нет")
            }
        }
    }

    private fun batchOutcome(kind: BatchKind, result: LibraryResult<*>): Pair<BatchOutcome, String?> = when (result) {
        is LibraryResult.Success -> BatchOutcome.SUCCESS to null
        is LibraryResult.NetworkError -> BatchOutcome.UNKNOWN to "Связь прервалась (${result.message})"
        is LibraryResult.UnknownResult -> BatchOutcome.UNKNOWN to "Ответ сервера не разобран (${result.message})"
        is LibraryResult.ServerError -> BatchOutcome.UNKNOWN to "Ошибка сервера (${result.message})"
        LibraryResult.Unauthorized -> BatchOutcome.FAILED to "Сервер отказал в доступе"
        LibraryResult.NotFound -> BatchOutcome.FAILED to
            if (kind == BatchKind.DELETE) "Не найден: возможно, уже удалён" else "Объект или папка назначения не найдены"
        is LibraryResult.Failure -> BatchOutcome.FAILED to failureText(result)
    }

    fun download(file: FileItemDto) {
        transfers.download(file)
    }

    fun saveDownload(id: Long, uri: Uri?) = transfers.saveDownload(id, uri)

    fun cancelTransfer() = transfers.cancel()

    fun dismissTransfer() = transfers.dismiss()

    // Загруженные файлы видны сразу, если пользователь остался в той же папке той же сессии.
    private fun onTransferState(transfer: TransferState) {
        if (transfer !is TransferState.Finished || transfer.kind != TransferKind.UPLOAD) return
        if (transfer.id == handledTransfer) return
        handledTransfer = transfer.id
        val state = _uiState.value
        if (transfer.session == context && state.status == LibraryStatus.Content &&
            state.currentFolder?.id == transfer.destinationFolderId
        ) {
            refresh()
        }
    }

    fun saveScroll(index: Int, offset: Int) {
        scrollIndex = index
        scrollOffset = offset
    }

    private fun loadRoot(notice: String? = null) {
        val current = beginLoad(emptyList(), notice)
        loadJob = viewModelScope.launch {
            val root = repository.getRoot()
            if (current != generation) return@launch
            when (root) {
                is LibraryResult.Success -> {
                    val path = listOf(root.value)
                    _uiState.update { it.copy(path = path) }
                    loadContent(current, path)
                }
                is LibraryResult.Failure -> applyFailure(root, emptyList())
            }
        }
    }

    private fun loadFolder(
        path: List<FolderDto>,
        notice: String? = null,
        info: String? = null,
        keepRelocation: Boolean = true
    ) {
        val current = beginLoad(path, notice, info, keepRelocation)
        loadJob = viewModelScope.launch { loadContent(current, path) }
    }

    private suspend fun loadContent(current: Int, path: List<FolderDto>) {
        val folder = path.last()
        val (children, firstPage) = coroutineScope {
            val children = async { repository.getChildren(folder.id) }
            val firstPage = async { repository.getFiles(folder, 0) }
            children.await() to firstPage.await()
        }
        if (current != generation) return

        // Неполный каталог не показывается: достаточно отказа одного из запросов.
        val failure = children as? LibraryResult.Failure ?: firstPage as? LibraryResult.Failure
        if (failure != null) {
            applyFailure(failure, path)
            return
        }
        val folders = (children as LibraryResult.Success).value
        val page = (firstPage as LibraryResult.Success).value
        nextPage = 1
        _uiState.update {
            it.copy(
                status = LibraryStatus.Content,
                folders = folders.sortedBy { child -> !child.isCamera },
                files = mergeFiles(emptyList(), page.items),
                hasNext = page.hasNext
            )
        }
    }

    private fun beginLoad(path: List<FolderDto>, notice: String?, info: String? = null, keepRelocation: Boolean = true): Int {
        cancelLoads()
        resetScroll()
        _uiState.update {
            LibraryUiState(
                status = LibraryStatus.Loading,
                path = path,
                notice = notice,
                info = info,
                contentVersion = it.contentVersion + 1,
                relocation = it.relocation.takeIf { keepRelocation },
                // Обновление той же папки (например, после ручной загрузки) не закрывает открытый диалог.
                edit = it.edit?.takeIf { edit -> edit.parent.id == path.lastOrNull()?.id },
                deletion = it.deletion?.takeIf { deletion -> deletion.parent.id == path.lastOrNull()?.id },
                // Переход в другую папку сбрасывает выделение, обновление той же — нет.
                selection = it.selection?.takeIf { selection -> selection.folderId == path.lastOrNull()?.id },
                batch = it.batch
            )
        }
        return generation
    }

    private fun cancelLoads() {
        generation++
        loadJob?.cancel()
        loadMoreJob?.cancel()
        nextPage = 0
    }

    private fun resetScroll() = saveScroll(0, 0)

    private fun applyFailure(failure: LibraryResult.Failure, path: List<FolderDto>) {
        when {
            failure is LibraryResult.Unauthorized -> {
                cancelLoads()
                relocationSource = null
                _uiState.update {
                    LibraryUiState(status = LibraryStatus.NoSession, contentVersion = it.contentVersion + 1)
                }
            }
            failure is LibraryResult.NotFound && path.size > 1 -> loadRoot(FOLDER_GONE_NOTICE)
            else -> _uiState.update {
                it.copy(status = LibraryStatus.Error(failureText(failure)), isLoadingMore = false)
            }
        }
    }

    private fun failureText(failure: LibraryResult.Failure): String = when (failure) {
        is LibraryResult.NetworkError -> "Нет связи с сервером: ${failure.message}"
        is LibraryResult.ServerError -> "Ошибка сервера: ${failure.message}"
        LibraryResult.NotFound -> "Папка не найдена"
        LibraryResult.Unauthorized -> "Требуется вход"
        LibraryResult.NameTaken -> "Имя уже занято файлом или папкой"
        LibraryResult.Managed -> "Папку изменяет только автоматическое резервирование"
        is LibraryResult.Rejected -> "Сервер отклонил операцию: ${failure.message}"
        is LibraryResult.UnknownResult -> "Результат операции неизвестен: ${failure.message}"
    }

    // Offset-пагинация при изменении каталога может вернуть файл повторно, а ключ строки уникален.
    private fun mergeFiles(loaded: List<FileItemDto>, page: List<FileItemDto>?): List<FileItemDto> =
        (loaded + page.orEmpty()).filter { it.id != null }.distinctBy { it.id }

    private companion object {
        // За сколько изображений до конца загруженной части просмотр запрашивает следующую страницу.
        const val VIEWER_PREFETCH = 3
        const val FOLDER_GONE_NOTICE = "Папка удалена или недоступна. Открыт корень."
        const val NOT_FOUND_TEXT = "Объект или папка назначения не найдены: возможно, они удалены или перемещены"
    }
}

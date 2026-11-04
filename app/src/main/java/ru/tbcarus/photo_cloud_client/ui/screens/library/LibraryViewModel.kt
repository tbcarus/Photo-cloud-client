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
        cancelLoads()
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
    private fun reloadParent(parent: FolderDto, notice: String?) {
        val state = _uiState.value
        if (state.currentFolder?.id == parent.id) loadFolder(state.path, notice)
    }

    private fun notFoundText(target: NameTarget): String = when (target) {
        NameTarget.NewFolder -> "Папка не найдена"
        is NameTarget.Folder -> "Папка «${target.folder.name}» не найдена: возможно, она удалена или перемещена"
        is NameTarget.File -> "Файл «${target.file.originalFilename.orEmpty()}» не найден: возможно, он удалён или перемещён"
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

    private fun loadFolder(path: List<FolderDto>, notice: String? = null) {
        val current = beginLoad(path, notice)
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

    private fun beginLoad(path: List<FolderDto>, notice: String?): Int {
        cancelLoads()
        resetScroll()
        _uiState.update {
            LibraryUiState(
                status = LibraryStatus.Loading,
                path = path,
                notice = notice,
                contentVersion = it.contentVersion + 1,
                // Обновление той же папки (например, после ручной загрузки) не закрывает открытый диалог.
                edit = it.edit?.takeIf { edit -> edit.parent.id == path.lastOrNull()?.id }
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
    }

    // Offset-пагинация при изменении каталога может вернуть файл повторно, а ключ строки уникален.
    private fun mergeFiles(loaded: List<FileItemDto>, page: List<FileItemDto>?): List<FileItemDto> =
        (loaded + page.orEmpty()).filter { it.id != null }.distinctBy { it.id }

    private companion object {
        const val FOLDER_GONE_NOTICE = "Папка удалена или недоступна. Открыт корень."
    }
}

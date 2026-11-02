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
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.library.LibraryRepository
import ru.tbcarus.photo_cloud_client.library.LibraryResult
import ru.tbcarus.photo_cloud_client.library.LibrarySession
import ru.tbcarus.photo_cloud_client.library.librarySessionFlow
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
    private val transfers: TransferManager
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

    private fun loadFolder(path: List<FolderDto>) {
        val current = beginLoad(path, notice = null)
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
                contentVersion = it.contentVersion + 1
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
    }

    // Offset-пагинация при изменении каталога может вернуть файл повторно, а ключ строки уникален.
    private fun mergeFiles(loaded: List<FileItemDto>, page: List<FileItemDto>?): List<FileItemDto> =
        (loaded + page.orEmpty()).filter { it.id != null }.distinctBy { it.id }

    private companion object {
        const val FOLDER_GONE_NOTICE = "Папка удалена или недоступна. Открыт корень."
    }
}

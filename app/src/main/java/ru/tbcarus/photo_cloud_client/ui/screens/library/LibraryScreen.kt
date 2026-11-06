package ru.tbcarus.photo_cloud_client.ui.screens.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.library.thumbnail.LibraryThumbnails
import ru.tbcarus.photo_cloud_client.library.thumbnail.Thumbnail
import ru.tbcarus.photo_cloud_client.library.thumbnail.ThumbnailScope
import ru.tbcarus.photo_cloud_client.library.thumbnail.ThumbnailsEntryPoint
import ru.tbcarus.photo_cloud_client.library.pdf.isPdf
import ru.tbcarus.photo_cloud_client.library.video.isPlayableVideo
import ru.tbcarus.photo_cloud_client.transfer.TransferState
import ru.tbcarus.photo_cloud_client.transfer.UploadDestination
import ru.tbcarus.photo_cloud_client.ui.components.DisableSourceDialog

// Следующая страница запрашивается заранее, до появления последней строки.
private const val LOAD_MORE_THRESHOLD = 10
private const val DISABLED_ALPHA = 0.38f
private val THUMBNAIL_SIZE = 48.dp
private val ICON_SIZE = 24.dp
private val TILE_MIN_WIDTH = 120.dp
private val TILE_ICON_SIZE = 48.dp

// Метки для UI-тестов: по ним проверяются вид, значки и миниатюры.
internal const val GRID_TAG = "library-grid"
internal const val LIST_TAG = "library-list"
internal const val TILE_TAG_PREFIX = "tile-"
internal const val THUMBNAIL_TAG_PREFIX = "thumbnail-"
internal const val FILE_ICON_TAG_PREFIX = "file-icon-"
internal const val FOLDER_ICON_TAG_PREFIX = "folder-icon-"
private const val ITEM_NAMES_SHOWN = 5

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenNetwork: () -> Unit,
    onOpenLogin: () -> Unit,
    thumbnails: LibraryThumbnails = rememberLibraryThumbnails()
) {
    val state by viewModel.uiState.collectAsState()
    val transfer by viewModel.transferState.collectAsState()
    val gridView by viewModel.gridView.collectAsState()
    // Выбор назначения всегда показывается списком; выбранный вид возвращается после него.
    val showGrid = gridView && !state.picking

    // Назначение запоминается до системного выбора: после гибели процесса открытая папка была бы уже другой.
    var destinationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var destinationIsRoot by rememberSaveable { mutableStateOf(false) }
    fun takeDestination(): UploadDestination? =
        destinationId?.let { UploadDestination(it, destinationIsRoot) }.also { destinationId = null }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        takeDestination()?.let { if (uris.isNotEmpty()) viewModel.uploadFiles(it, uris) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        takeDestination()?.let { destination -> uri?.let { viewModel.uploadFolder(destination, it) } }
    }
    fun startUpload(folder: Boolean) {
        val destination = viewModel.uploadDestination() ?: return
        destinationId = destination.folderId
        destinationIsRoot = destination.isRoot
        if (folder) pickFolder.launch(null) else pickFiles.launch(arrayOf("*/*"))
    }

    // octet-stream: с MIME, не совпадающим с расширением, провайдер дописал бы к имени своё расширение.
    var savingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        savingId?.let { viewModel.saveDownload(it, uri) }
    }
    LaunchedEffect(transfer) {
        val awaiting = transfer as? TransferState.AwaitingDestination ?: return@LaunchedEffect
        if (savingId == awaiting.id) return@LaunchedEffect
        savingId = awaiting.id
        createDocument.launch(awaiting.fileName)
    }

    // Статус, а не вход на экран: адрес может восстановиться уже при открытом экране.
    LaunchedEffect(state.status) { viewModel.ensureLoaded() }
    // Back сначала закрывает выделение; при выборе назначения поднимается по папкам, а в корне отменяет выбор.
    BackHandler(enabled = state.canGoUp || state.picking || state.selecting) {
        when {
            state.selecting -> viewModel.exitSelection()
            state.canGoUp -> viewModel.navigateUp()
            else -> viewModel.cancelRelocation()
        }
    }

    // Вне LazyColumn: при загрузке списка нет, а положение нужно сохранить при смене вкладки.
    // Состояние прокрутки своё у каждого вида; при переключении позицию передаёт toggleView.
    val listState = remember(state.contentVersion, showGrid) { LazyListState(viewModel.scrollIndex, viewModel.scrollOffset) }
    val gridState = remember(state.contentVersion, showGrid) { LazyGridState(viewModel.scrollIndex, viewModel.scrollOffset) }
    DisposableEffect(listState, gridState) {
        onDispose {
            if (showGrid) {
                viewModel.saveScroll(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            } else {
                viewModel.saveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        LibraryHeader(
            state = state,
            transferActive = transfer.isActive,
            gridView = gridView,
            onToggleView = {
                viewModel.toggleView(if (showGrid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex)
            },
            onRefresh = viewModel::refresh,
            onUp = viewModel::navigateUp,
            onUpload = ::startUpload,
            onCreateFolder = viewModel::startCreateFolder
        )
        state.deletion?.let { deletion ->
            DeleteDialog(
                deletion = deletion,
                onConfirm = viewModel::confirmDelete,
                onRefresh = viewModel::refreshAfterDelete,
                onDismiss = viewModel::dismissDelete
            )
        }
        state.edit?.let { edit ->
            NameDialog(
                edit = edit,
                onNameChange = viewModel::onEditNameChange,
                onSubmit = viewModel::submitEdit,
                onDismiss = viewModel::dismissEdit
            )
        }
        state.relocation?.let { relocation ->
            RelocationPanel(
                state = state,
                relocation = relocation,
                onConfirm = viewModel::requestRelocationConfirm,
                onCancel = viewModel::cancelRelocation,
                onRefresh = viewModel::refresh
            )
            if (relocation.confirming) {
                RelocationDialog(
                    relocation = relocation,
                    target = state.path,
                    onNameChange = viewModel::onCopyNameChange,
                    onConfirm = viewModel::confirmRelocation,
                    onDismiss = viewModel::dismissRelocationConfirm
                )
            }
        }
        state.selection?.let { selection ->
            SelectionPanel(state, selection, viewModel)
            if (selection.confirmingDelete) {
                GroupDeleteDialog(selection, onConfirm = viewModel::confirmGroupDelete, onDismiss = viewModel::dismissGroupDelete)
            }
        }
        state.batch?.let { batch ->
            BatchDialog(
                batch = batch,
                onStop = viewModel::stopBatch,
                onClose = viewModel::dismissBatch,
                onOpenTarget = viewModel::openBatchTarget
            )
        }
        if (state.isSourceRoot && !state.picking && !state.selecting) {
            SourceRootPanel(state, onDisable = viewModel::disableSource)
        }
        state.notice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        state.info?.let { info ->
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        TransferPanel(transfer, onCancel = viewModel::cancelTransfer, onDismiss = viewModel::dismissTransfer)
        HorizontalDivider()

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (val status = state.status) {
                LibraryStatus.Idle, LibraryStatus.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                LibraryStatus.NoServer -> StateMessage(
                    text = "Сервер не настроен. Укажите адрес на экране Network.",
                    action = "Открыть Network",
                    onAction = onOpenNetwork
                )
                LibraryStatus.NoSession -> StateMessage(
                    text = "Войдите, чтобы увидеть файлы на сервере.",
                    action = "Выполнить вход",
                    onAction = onOpenLogin
                )
                is LibraryStatus.Error -> StateMessage(
                    text = status.message,
                    action = "Повторить",
                    onAction = viewModel::refresh
                )
                LibraryStatus.Content ->
                    if (state.isEmpty) {
                        StateMessage(text = "Папка пуста")
                    } else {
                        if (showGrid) {
                            LibraryGrid(state, gridState, viewModel, downloadEnabled = !transfer.isActive, thumbnails = thumbnails)
                        } else {
                            LibraryList(state, listState, viewModel, downloadEnabled = !transfer.isActive, thumbnails = thumbnails)
                        }
                    }
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    transferActive: Boolean,
    gridView: Boolean,
    onToggleView: () -> Unit,
    onRefresh: () -> Unit,
    onUp: () -> Unit,
    onUpload: (folder: Boolean) -> Unit,
    onCreateFolder: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Библиотека",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        if (state.canCreateFolder) AddMenu(uploadEnabled = state.canUpload && !transferActive, onUpload, onCreateFolder)
        // Значок показывает вид, в который переключит кнопка. Во время выбора назначения вид не меняется.
        IconButton(onClick = onToggleView, enabled = !state.picking) {
            if (gridView) {
                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "Показать списком")
            } else {
                Icon(Icons.Default.GridView, contentDescription = "Показать плиткой")
            }
        }
        val canRefresh = state.status != LibraryStatus.NoServer && state.status != LibraryStatus.NoSession
        IconButton(onClick = onRefresh, enabled = canRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
        }
    }
    if (state.path.isEmpty()) return

    // Длинный путь прокручивается: конец с текущей папкой виден сразу.
    val pathScroll = rememberScrollState()
    LaunchedEffect(state.path, pathScroll.maxValue) { pathScroll.scrollTo(pathScroll.maxValue) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.canGoUp) {
            IconButton(onClick = onUp) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К родительской папке")
            }
        }
        Text(
            text = libraryPathLabel(state.path),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .horizontalScroll(pathScroll)
                .padding(vertical = 4.dp)
        )
    }
}

/** Корень активного источника: отметка и отключение резервирования. */
@Composable
private fun SourceRootPanel(state: LibraryUiState, onDisable: () -> Unit) {
    val folder = state.currentFolder ?: return
    var confirm by rememberSaveable(folder.id) { mutableStateOf(false) }
    if (confirm) DisableSourceDialog(folder.name, onConfirm = onDisable, onDismiss = { confirm = false })
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Источник автоматической загрузки",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = { confirm = true }, enabled = !state.disabling) { Text("Отключить резервирование") }
    }
}

/** Меню «+»: создание папки и ручная загрузка. Загрузка недоступна, пока идёт другая передача. */
@Composable
private fun AddMenu(uploadEnabled: Boolean, onUpload: (folder: Boolean) -> Unit, onCreateFolder: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Add, contentDescription = "Добавить")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Создать папку") }, onClick = {
                expanded = false
                onCreateFolder()
            })
            DropdownMenuItem(text = { Text("Загрузить файлы") }, enabled = uploadEnabled, onClick = {
                expanded = false
                onUpload(false)
            })
            DropdownMenuItem(text = { Text("Загрузить папку") }, enabled = uploadEnabled, onClick = {
                expanded = false
                onUpload(true)
            })
        }
    }
}

@Composable
private fun LibraryList(
    state: LibraryUiState,
    listState: LazyListState,
    viewModel: LibraryViewModel,
    downloadEnabled: Boolean,
    thumbnails: LibraryThumbnails
) {
    val thumbnailScope by thumbnails.currentScope.collectAsState()
    val nearEnd by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && last >= layout.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    // isLoadingMore в ключах: страница из одних повторов не удлиняет список, но следующая всё равно нужна.
    LaunchedEffect(nearEnd, state.hasNext, state.isLoadingMore, state.loadMoreError) {
        if (nearEnd) viewModel.onListEndReached()
    }

    // Положение списка запоминается до выбора назначения: отмена вернёт к той же строке.
    fun keepPosition() = viewModel.saveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)

    val inCamera = state.currentFolder?.isCamera == true
    val relocation = state.relocation
    val selection = state.selection
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(LIST_TAG)) {
        items(state.folders, key = { "folder-${it.id}" }) { folder ->
            val actions = folderActions(state, folder, viewModel, ::keepPosition)
            val selectable = state.canSelect(folder)
            FolderRow(
                folder = folder,
                actions = if (selection != null) emptyList() else actions,
                blocked = relocation?.let { blockedFolderProblem(it, state.path, folder) },
                // В режиме выделения нажатие переключает отметку; невыделяемые папки не открываются, чтобы не сбросить выбор.
                selected = selection?.takeIf { selectable }?.contains(folder),
                onClick = {
                    when {
                        selection == null -> viewModel.openFolder(folder)
                        selectable -> viewModel.toggleSelection(folder)
                    }
                },
                onLongClick = if (selectable) {
                    { viewModel.toggleSelection(folder) }
                } else {
                    null
                }
            )
            HorizontalDivider()
        }
        items(state.files, key = { "file-${it.id}" }) { file ->
            val actions = fileActions(state, file, viewModel, downloadEnabled, ::keepPosition)
            val selectable = state.canSelect(file)
            FileRow(
                file = file,
                thumbnail = remember(file.id, file.checksum, file.mimeType, thumbnailScope) {
                    Thumbnail.of(file, thumbnailScope)
                },
                thumbnails = thumbnails,
                inCamera = inCamera,
                actions = if (selection != null) emptyList() else actions,
                dimmed = state.picking,
                selected = selection?.takeIf { selectable }?.contains(file),
                onClick = fileClick(state, file, selectable, viewModel),
                onLongClick = if (selectable) {
                    { viewModel.toggleSelection(file) }
                } else {
                    null
                }
            )
            HorizontalDivider()
        }
        if (state.isLoadingMore) {
            item(key = "loading-more") { LoadingMore() }
        }
        state.loadMoreError?.let { error ->
            item(key = "load-more-error") { LoadMoreError(error, viewModel::loadNextPage) }
        }
    }
}

/**
 * Нажатие на файл: в режиме выделения переключает отметку, иначе открывает просмотр изображения, плеер видео или PDF.
 * При выборе назначения и у файлов, которые нельзя показать, нажатие ничего не делает.
 */
private fun fileClick(state: LibraryUiState, file: FileItemDto, selectable: Boolean, viewModel: LibraryViewModel): (() -> Unit)? =
    when {
        state.selection != null -> if (selectable) {
            { viewModel.toggleSelection(file) }
        } else {
            null
        }
        !state.picking && isViewable(file) -> {
            { viewModel.openViewer(file) }
        }
        !state.picking && isPlayableVideo(file) -> {
            { viewModel.openVideo(file) }
        }
        !state.picking && isPdf(file) -> {
            { viewModel.openPdf(file) }
        }
        else -> null
    }

// Только разрешённые действия: у системных папок и папок активного дерева меню нет.
private fun folderActions(
    state: LibraryUiState,
    folder: FolderDto,
    viewModel: LibraryViewModel,
    keepPosition: () -> Unit
): List<RowAction> = listOfNotNull(
    RowAction("Переименовать", { viewModel.startRename(folder) }).takeIf { state.canRename(folder) },
    RowAction("Переместить", {
        keepPosition()
        viewModel.startMove(folder)
    }).takeIf { state.canMove(folder) },
    RowAction("Удалить", { viewModel.startDelete(folder) }, destructive = true).takeIf { state.canDelete(folder) }
)

// При выборе назначения файлы видны для сверки имён, но действий с ними нет.
private fun fileActions(
    state: LibraryUiState,
    file: FileItemDto,
    viewModel: LibraryViewModel,
    downloadEnabled: Boolean,
    keepPosition: () -> Unit
): List<RowAction> = if (state.picking) emptyList() else listOfNotNull(
    RowAction("Скачать", { viewModel.download(file) }, enabled = downloadEnabled && file.id != null),
    RowAction("Переименовать", { viewModel.startRename(file) }).takeIf { state.canRename(file) },
    RowAction("Переместить", {
        keepPosition()
        viewModel.startMove(file)
    }).takeIf { state.canMove(file) },
    RowAction("Копировать", {
        keepPosition()
        viewModel.startCopy(file)
    }).takeIf { state.canCopy(file) },
    RowAction("Удалить", { viewModel.startDelete(file) }, destructive = true).takeIf { state.canDelete(file) }
)

@Composable
private fun LoadingMore() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun LoadMoreError(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onRetry) { Text("Повторить") }
    }
}

/**
 * Плитка того же каталога: те же элементы, ключи, порядок (папки, затем файлы), действия, выделение и подгрузка
 * страниц, что у списка. Выбор назначения всегда показывается списком, поэтому здесь его нет.
 */
@Composable
private fun LibraryGrid(
    state: LibraryUiState,
    gridState: LazyGridState,
    viewModel: LibraryViewModel,
    downloadEnabled: Boolean,
    thumbnails: LibraryThumbnails
) {
    val thumbnailScope by thumbnails.currentScope.collectAsState()
    val nearEnd by remember(gridState) {
        derivedStateOf {
            val layout = gridState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && last >= layout.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(nearEnd, state.hasNext, state.isLoadingMore, state.loadMoreError) {
        if (nearEnd) viewModel.onListEndReached()
    }

    fun keepPosition() = viewModel.saveScroll(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)

    val selection = state.selection
    LazyVerticalGrid(
        columns = GridCells.Adaptive(TILE_MIN_WIDTH),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(GRID_TAG),
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        gridItems(state.folders, key = { "folder-${it.id}" }) { folder ->
            val selectable = state.canSelect(folder)
            LibraryTile(
                name = folder.name,
                actions = if (selection != null) emptyList() else folderActions(state, folder, viewModel, ::keepPosition),
                selected = selection?.takeIf { selectable }?.contains(folder),
                onClick = {
                    when {
                        selection == null -> viewModel.openFolder(folder)
                        selectable -> viewModel.toggleSelection(folder)
                    }
                },
                onLongClick = if (selectable) {
                    { viewModel.toggleSelection(folder) }
                } else {
                    null
                }
            ) {
                FolderIcon(folderKind(folder), size = TILE_ICON_SIZE)
            }
        }
        gridItems(state.files, key = { "file-${it.id}" }) { file ->
            val selectable = state.canSelect(file)
            LibraryTile(
                name = file.originalFilename.orEmpty(),
                actions = if (selection != null) emptyList() else fileActions(state, file, viewModel, downloadEnabled, ::keepPosition),
                selected = selection?.takeIf { selectable }?.contains(file),
                onClick = fileClick(state, file, selectable, viewModel),
                onLongClick = if (selectable) {
                    { viewModel.toggleSelection(file) }
                } else {
                    null
                }
            ) {
                FileThumbnail(
                    file = file,
                    thumbnail = remember(file.id, file.checksum, file.mimeType, thumbnailScope) {
                        Thumbnail.of(file, thumbnailScope)
                    },
                    thumbnails = thumbnails,
                    modifier = Modifier.fillMaxSize(),
                    iconSize = TILE_ICON_SIZE
                )
            }
        }
        if (state.isLoadingMore) {
            item(key = "loading-more", span = { GridItemSpan(maxLineSpan) }) { LoadingMore() }
        }
        state.loadMoreError?.let { error ->
            item(key = "load-more-error", span = { GridItemSpan(maxLineSpan) }) {
                LoadMoreError(error, viewModel::loadNextPage)
            }
        }
    }
}

/**
 * Плитка объекта: квадратная область превью (миниатюра вписывается без искажения) и имя до двух строк.
 * [selected] — отметка в режиме выделения, null — объект не выделяется; выделенная плитка обведена и подсвечена.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTile(
    name: String,
    actions: List<RowAction>,
    selected: Boolean?,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    preview: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    val border = if (selected == true) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(border, shape)
            .background(
                if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(
                enabled = onClick != null || onLongClick != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick
            )
            .testTag(TILE_TAG_PREFIX + name)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            preview()
            if (selected != null) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick?.invoke() },
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected == null) ActionsMenu(actions) else Spacer(Modifier.height(48.dp))
        }
    }
}

/**
 * [blocked] — причина, по которой при выборе назначения в папку нет смысла входить.
 * [selected] — отметка в режиме выделения; null — строка не выделяется.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: FolderDto,
    actions: List<RowAction>,
    blocked: String?,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = blocked == null, onClick = onClick, onLongClick = onLongClick)
            .alpha(if (blocked == null) 1f else DISABLED_ALPHA)
            .padding(vertical = 4.dp)
    ) {
        Box(Modifier.width(THUMBNAIL_SIZE), contentAlignment = Alignment.Center) { FolderIcon(folderKind(folder)) }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            (blocked ?: folderSubtitle(folder))?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected != null) Checkbox(checked = selected, onCheckedChange = { onClick() }) else ActionsMenu(actions)
    }
}

// Нажатие на строку открывает просмотр изображения, в режиме выделения — переключает отметку; действия — в меню строки.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: FileItemDto,
    thumbnail: Thumbnail?,
    thumbnails: LibraryThumbnails,
    inCamera: Boolean,
    actions: List<RowAction>,
    dimmed: Boolean,
    selected: Boolean?,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = onClick != null || onLongClick != null, onClick = { onClick?.invoke() }, onLongClick = onLongClick)
            .alpha(if (dimmed) DISABLED_ALPHA else 1f)
            .padding(vertical = 4.dp)
    ) {
        FileThumbnail(file, thumbnail, thumbnails)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = file.originalFilename.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = librarySubtitle(file, inCamera),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected != null) Checkbox(checked = selected, onCheckedChange = { onClick?.invoke() }) else ActionsMenu(actions)
    }
}

private data class RowAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    // Удаление выделено значком и цветом ошибки.
    val destructive: Boolean = false
)

/** Кнопка «⋮» строки; без действий на её месте пустое поле той же ширины, чтобы строки не сдвигались. */
@Composable
private fun ActionsMenu(actions: List<RowAction>) {
    if (actions.isEmpty()) {
        Spacer(Modifier.width(48.dp))
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Действия")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                DropdownMenuItem(
                    text = { Text(action.label, color = color) },
                    leadingIcon = if (action.destructive) {
                        { Icon(Icons.Default.Delete, contentDescription = null, tint = color) }
                    } else {
                        null
                    },
                    enabled = action.enabled,
                    onClick = {
                        expanded = false
                        action.onClick()
                    }
                )
            }
        }
    }
}

@Composable
private fun FolderIcon(kind: FolderKind, size: Dp = ICON_SIZE) {
    val tint = MaterialTheme.colorScheme.primary
    val modifier = Modifier
        .size(size)
        .testTag(FOLDER_ICON_TAG_PREFIX + kind.name)
    when (kind) {
        FolderKind.CAMERA -> Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = tint, modifier = modifier)
        FolderKind.AUTO_UPLOADS -> Icon(Icons.Default.CloudUpload, contentDescription = null, tint = tint, modifier = modifier)
        FolderKind.FOLDER -> Icon(Icons.Default.Folder, contentDescription = null, tint = tint, modifier = modifier)
        // В наборе значков Compose нет Folder Sync: папка с отметкой синхронизации.
        FolderKind.ACTIVE_SOURCE -> Box(modifier = modifier) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .size(size * 14 / 24)
                    .align(Alignment.BottomEnd)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, tint = tint, modifier = Modifier.padding(1.dp))
            }
        }
    }
}

/**
 * Ввод имени новой папки или нового имени объекта. У файла редактируется полное имя: выделена основа до расширения,
 * но расширение не защищено и само не меняется.
 */
@Composable
private fun NameDialog(
    edit: NameEdit,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    var field by remember(edit.id) {
        val stem = if (edit.target is NameTarget.File) edit.name.lastIndexOf('.').takeIf { it > 0 } else null
        mutableStateOf(TextFieldValue(edit.name, TextRange(0, stem ?: edit.name.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(edit.id) { focus.requestFocus() }
    val (title, confirm) = when (edit.target) {
        NameTarget.NewFolder -> "Новая папка" to "Создать"
        is NameTarget.Folder -> "Переименовать папку" to "Сохранить"
        is NameTarget.File -> "Переименовать файл" to "Сохранить"
    }
    val error = edit.error
    AlertDialog(
        onDismissRequest = onDismiss,
        // Пока запрос выполняется, диалог не закрывается: результат должен дойти до списка.
        properties = DialogProperties(dismissOnBackPress = !edit.saving, dismissOnClickOutside = !edit.saving),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    onNameChange(it.text)
                },
                label = { Text("Имя") },
                singleLine = true,
                enabled = !edit.saving,
                isError = error != null,
                supportingText = if (error != null) {
                    { Text(error) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
            )
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !edit.saving) {
                if (edit.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(confirm)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !edit.saving) { Text("Отмена") }
        }
    )
}

/** Панель выбора назначения: что выполняется, годится ли открытая папка, подтверждение и отмена. */
@Composable
private fun RelocationPanel(
    state: LibraryUiState,
    relocation: Relocation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit
) {
    val (title, confirm) = when (val item = relocation.item) {
        is Relocated.MoveFile -> "Перемещение файла «${item.name}»" to "Переместить сюда"
        is Relocated.CopyFile -> "Копирование файла «${item.name}»" to "Копировать сюда"
        is Relocated.MoveFolder -> "Перемещение папки «${item.name}»" to "Переместить сюда"
        is Relocated.Group ->
            (if (item.copy) "Копирование: ${item.name}" else "Перемещение: ${item.name}") to
                (if (item.copy) "Копировать сюда" else "Переместить сюда")
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Выберите папку назначения",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            val problem = state.pickProblem.takeIf { state.status == LibraryStatus.Content }
            (relocation.error ?: problem)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (relocation.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (relocation.unknownResult) TextButton(onClick = onRefresh) { Text("Обновить") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel, enabled = !relocation.saving) { Text("Отмена") }
                Button(
                    onClick = onConfirm,
                    enabled = state.pickProblem == null && !relocation.saving && !relocation.unknownResult
                ) { Text(confirm) }
            }
        }
    }
}

/** Итог операции до отправки; для копии файла — имя копии, по умолчанию исходное. */
@Composable
private fun RelocationDialog(
    relocation: Relocation,
    target: List<FolderDto>,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val (title, confirm, subject) = when (val item = relocation.item) {
        is Relocated.MoveFile -> Triple("Переместить файл?", "Переместить", "Файл")
        is Relocated.CopyFile -> Triple("Копировать файл?", "Копировать", "Файл")
        is Relocated.MoveFolder -> Triple("Переместить папку?", "Переместить", "Папка")
        is Relocated.Group ->
            if (item.copy) Triple("Копировать файлы?", "Копировать", "Объекты") else Triple("Переместить объекты?", "Переместить", "Объекты")
    }
    var field by remember(relocation.id) {
        val name = relocation.copyName
        val stem = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        mutableStateOf(TextFieldValue(name, TextRange(0, stem)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Пока запрос выполняется, диалог не закрывается: результат должен дойти до Library.
        properties = DialogProperties(dismissOnBackPress = !relocation.saving, dismissOnClickOutside = !relocation.saving),
        title = { Text(title) },
        text = {
            Column {
                Text("$subject: ${relocation.item.name}", style = MaterialTheme.typography.bodyMedium)
                (relocation.item as? Relocated.Group)?.let { group -> ItemNames(group.items.map { it.name }) }
                Text("Из: ${libraryPathLabel(relocation.sourcePath)}", style = MaterialTheme.typography.bodyMedium)
                Text("В: ${libraryPathLabel(target)}", style = MaterialTheme.typography.bodyMedium)
                if (relocation.item is Relocated.CopyFile) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = field,
                        onValueChange = {
                            field = it
                            onNameChange(it.text)
                        },
                        label = { Text("Имя копии") },
                        singleLine = true,
                        enabled = !relocation.saving,
                        isError = relocation.error != null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                relocation.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !relocation.saving) {
                if (relocation.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(confirm)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !relocation.saving) { Text("Отмена") }
        }
    )
}

/**
 * Подтверждение удаления файла или папки. Корзины нет: удаление необратимо. После неизвестного результата
 * повторного «Удалить» нет — только обновление каталога.
 */
@Composable
private fun DeleteDialog(deletion: Deletion, onConfirm: () -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val (title, question) = when (deletion.target) {
        is LibraryItem.File -> "Удалить файл?" to "Удалить файл «${deletion.target.name}»?"
        is LibraryItem.Folder ->
            "Удалить папку?" to "Удалить папку «${deletion.target.name}» вместе со всеми вложенными папками и файлами?"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = !deletion.deleting, dismissOnClickOutside = !deletion.deleting),
        title = { Text(title) },
        text = {
            Column {
                Text(question, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Восстановление через корзину не предусмотрено.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Server не сообщает Android об удалении: резервирование Camera фото повторно не отправит.
                if (deletion.target is LibraryItem.File && deletion.parent.isCamera) {
                    Text(
                        "Фото на телефоне останется, повторно автоматически оно не загрузится.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                deletion.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (deletion.unknownResult) {
                TextButton(onClick = onRefresh) { Text("Обновить") }
            } else {
                TextButton(onClick = onConfirm, enabled = !deletion.deleting) {
                    if (deletion.deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deletion.deleting) {
                Text(if (deletion.unknownResult) "Закрыть" else "Отмена")
            }
        }
    )
}

/** Панель режима выделения: число выбранных, выход, команды выделения и групповые операции. */
@Composable
private fun SelectionPanel(state: LibraryUiState, selection: Selection, viewModel: LibraryViewModel) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::exitSelection) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть выделение")
                }
                Text("Выбрано: ${selection.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Команды выделения")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Выделить загруженные") }, onClick = {
                            menu = false
                            viewModel.selectLoaded()
                        })
                        DropdownMenuItem(text = { Text("Снять выделение") }, enabled = !selection.isEmpty, onClick = {
                            menu = false
                            viewModel.clearSelection()
                        })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = viewModel::startGroupMove, enabled = state.groupMoveProblem == null) { Text("Переместить") }
                TextButton(onClick = viewModel::startGroupCopy, enabled = state.groupCopyProblem == null) { Text("Копировать") }
                TextButton(onClick = viewModel::requestGroupDelete, enabled = !selection.isEmpty) {
                    Text("Удалить", color = if (selection.isEmpty) Color.Unspecified else MaterialTheme.colorScheme.error)
                }
            }
            // Причина недоступной операции; пустое выделение объяснять не нужно.
            if (!selection.isEmpty) {
                (state.groupCopyProblem ?: state.groupMoveProblem)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/** Подтверждение группового удаления: число — выделенные объекты, без подсчёта содержимого папок. */
@Composable
private fun GroupDeleteDialog(selection: Selection, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить выбранное?") },
        text = {
            Column {
                Text("Удалить ${selectedObjectsCount(selection.size)}?", style = MaterialTheme.typography.bodyMedium)
                ItemNames(selection.items.map { it.name })
                Spacer(Modifier.height(8.dp))
                if (selection.hasFolders) {
                    Text(
                        "Все файлы и вложенные папки внутри выбранных каталогов также будут удалены.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "Восстановление через корзину не предусмотрено.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/** Прогресс пакета, затем итог по объектам. Повторить всё нельзя: сначала сверка состояния Server. */
@Composable
private fun BatchDialog(batch: Batch, onStop: () -> Unit, onClose: () -> Unit, onOpenTarget: () -> Unit) {
    val operation = when (batch.kind) {
        BatchKind.MOVE -> "Перемещение"
        BatchKind.COPY -> "Копирование"
        BatchKind.DELETE -> "Удаление"
    }
    val unknown = batch.count(BatchOutcome.UNKNOWN)
    val title = when {
        batch.running -> operation
        batch.count(BatchOutcome.SUCCESS) == batch.total -> "$operation завершено"
        unknown > 0 -> "$operation: результат неизвестен"
        batch.stopRequested -> "$operation остановлено"
        else -> "$operation: есть ошибки"
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Column {
                Text("Обработано: ${batch.processed} из ${batch.total}", style = MaterialTheme.typography.bodyMedium)
                if (batch.running) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { batch.processed.toFloat() / batch.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    batch.current?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (batch.stopRequested) {
                        Text("Остановка после текущего объекта…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Успешно: ${batch.count(BatchOutcome.SUCCESS)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Ошибок: ${batch.count(BatchOutcome.FAILED)}", style = MaterialTheme.typography.bodyMedium)
                    if (unknown > 0) Text("Результат неизвестен: $unknown", style = MaterialTheme.typography.bodyMedium)
                    val notProcessed = batch.count(BatchOutcome.NOT_PROCESSED)
                    if (notProcessed > 0) Text("Не обработано: $notProcessed", style = MaterialTheme.typography.bodyMedium)
                    batch.haltReason?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    val problems = batch.entries.filter { it.outcome == BatchOutcome.FAILED || it.outcome == BatchOutcome.UNKNOWN }
                    if (problems.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            problems.forEach { entry ->
                                Text(
                                    "${entry.item.name}: ${entry.reason.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    if (unknown > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Server мог выполнить операцию. Проверьте исходную папку и папку назначения, прежде чем повторять.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (batch.running) {
                TextButton(onClick = onStop, enabled = !batch.stopRequested) { Text("Остановить") }
            } else {
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        },
        dismissButton = {
            if (!batch.running && unknown > 0 && batch.targetPath != null && !batch.sessionLost) {
                TextButton(onClick = onOpenTarget) { Text("Открыть назначение") }
            }
        }
    )
}

/** Имена объектов операции: первые несколько, остальные — числом. */
@Composable
private fun ItemNames(names: List<String>) {
    val shown = names.take(ITEM_NAMES_SHOWN)
    shown.forEach {
        Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (names.size > shown.size) {
        Text("и ещё ${names.size - shown.size}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StateMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = text, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
internal fun rememberLibraryThumbnails(): LibraryThumbnails {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, ThumbnailsEntryPoint::class.java).libraryThumbnails()
    }
}

/**
 * Слот 48 dp слева от имени файла: миниатюра JPEG/PNG/WebP с сохранением пропорций, пока она грузится —
 * нейтральная подложка, при ошибке и для остальных типов — значок типа. Нажатия обрабатывает строка.
 */
@Composable
private fun FileThumbnail(
    file: FileItemDto,
    thumbnail: Thumbnail?,
    thumbnails: LibraryThumbnails,
    modifier: Modifier = Modifier.size(THUMBNAIL_SIZE),
    iconSize: Dp = ICON_SIZE
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        // Неудача 404/415/422 запоминается до смены контекста: повторный показ строки не повторяет запрос.
        if (thumbnail == null || thumbnails.isFailed(thumbnail)) {
            FileTypeIcon(file, iconSize)
            return@Box
        }
        val context = LocalContext.current
        val request = remember(thumbnail) { thumbnails.request(context, thumbnail) }
        var loaded by remember(thumbnail) { mutableStateOf<Boolean?>(null) }
        if (loaded == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        if (loaded == false) {
            FileTypeIcon(file, iconSize)
        } else {
            AsyncImage(
                model = request,
                imageLoader = thumbnails.imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .testTag(THUMBNAIL_TAG_PREFIX + file.id),
                onState = { state ->
                    loaded = when (state) {
                        is AsyncImagePainter.State.Success -> true
                        is AsyncImagePainter.State.Error -> false
                        else -> loaded
                    }
                }
            )
        }
    }
}

@Composable
private fun FileTypeIcon(file: FileItemDto, size: Dp = ICON_SIZE) {
    Icon(
        imageVector = fileIcon(file.fileType),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(size)
            .testTag(FILE_ICON_TAG_PREFIX + file.id)
    )
}

private fun fileIcon(fileType: String?): ImageVector = when (fileType) {
    "IMAGE" -> Icons.Default.Image
    "VIDEO" -> Icons.Default.Movie
    "AUDIO" -> Icons.Default.AudioFile
    "DOCUMENT" -> Icons.Default.Description
    "ARCHIVE" -> Icons.Default.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

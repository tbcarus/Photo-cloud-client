package ru.tbcarus.photo_cloud_client.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
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
import ru.tbcarus.photo_cloud_client.transfer.TransferState
import ru.tbcarus.photo_cloud_client.transfer.UploadDestination
import ru.tbcarus.photo_cloud_client.ui.components.DisableSourceDialog

// Следующая страница запрашивается заранее, до появления последней строки.
private const val LOAD_MORE_THRESHOLD = 10
private const val DISABLED_ALPHA = 0.38f

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenNetwork: () -> Unit,
    onOpenLogin: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val transfer by viewModel.transferState.collectAsState()

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
    // При выборе назначения Back поднимается по папкам, а в корне отменяет выбор.
    BackHandler(enabled = state.canGoUp || state.picking) {
        if (state.canGoUp) viewModel.navigateUp() else viewModel.cancelRelocation()
    }

    // Вне LazyColumn: при загрузке списка нет, а положение нужно сохранить при смене вкладки.
    val listState = remember(state.contentVersion) { LazyListState(viewModel.scrollIndex, viewModel.scrollOffset) }
    DisposableEffect(listState) {
        onDispose { viewModel.saveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        LibraryHeader(
            state = state,
            transferActive = transfer.isActive,
            onRefresh = viewModel::refresh,
            onUp = viewModel::navigateUp,
            onUpload = ::startUpload,
            onCreateFolder = viewModel::startCreateFolder
        )
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
        if (state.isSourceRoot && !state.picking) SourceRootPanel(state, onDisable = viewModel::disableSource)
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
                        LibraryList(state, listState, viewModel, downloadEnabled = !transfer.isActive)
                    }
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    transferActive: Boolean,
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
    downloadEnabled: Boolean
) {
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
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.folders, key = { "folder-${it.id}" }) { folder ->
            // Только разрешённые действия: у системных папок и папок активного дерева меню нет.
            val actions = listOfNotNull(
                RowAction("Переименовать", { viewModel.startRename(folder) }).takeIf { state.canRename(folder) },
                RowAction("Переместить", {
                    keepPosition()
                    viewModel.startMove(folder)
                }).takeIf { state.canMove(folder) }
            )
            FolderRow(
                folder = folder,
                actions = actions,
                blocked = relocation?.let { blockedFolderProblem(it, state.path, folder) },
                onClick = { viewModel.openFolder(folder) }
            )
            HorizontalDivider()
        }
        items(state.files, key = { "file-${it.id}" }) { file ->
            // При выборе назначения файлы видны для сверки имён, но действий с ними нет.
            val actions = if (state.picking) emptyList() else listOfNotNull(
                RowAction("Скачать", { viewModel.download(file) }, enabled = downloadEnabled && file.id != null),
                RowAction("Переименовать", { viewModel.startRename(file) }).takeIf { state.canRename(file) },
                RowAction("Переместить", {
                    keepPosition()
                    viewModel.startMove(file)
                }).takeIf { state.canMove(file) },
                RowAction("Копировать", {
                    keepPosition()
                    viewModel.startCopy(file)
                }).takeIf { state.canCopy(file) }
            )
            FileRow(file = file, inCamera = inCamera, actions = actions, dimmed = state.picking)
            HorizontalDivider()
        }
        if (state.isLoadingMore) {
            item(key = "loading-more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        state.loadMoreError?.let { error ->
            item(key = "load-more-error") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = viewModel::loadNextPage) { Text("Повторить") }
                }
            }
        }
    }
}

/** [blocked] — причина, по которой при выборе назначения в папку нет смысла входить. */
@Composable
private fun FolderRow(folder: FolderDto, actions: List<RowAction>, blocked: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = blocked == null, onClick = onClick)
            .alpha(if (blocked == null) 1f else DISABLED_ALPHA)
            .padding(vertical = 4.dp)
    ) {
        FolderIcon(folderKind(folder))
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
        ActionsMenu(actions)
    }
}

// Нажатие на строку оставлено для будущего просмотра; действия — в меню строки.
@Composable
private fun FileRow(file: FileItemDto, inCamera: Boolean, actions: List<RowAction>, dimmed: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) DISABLED_ALPHA else 1f)
            .padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = fileIcon(file.fileType),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
        ActionsMenu(actions)
    }
}

private data class RowAction(val label: String, val onClick: () -> Unit, val enabled: Boolean = true)

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
                DropdownMenuItem(text = { Text(action.label) }, enabled = action.enabled, onClick = {
                    expanded = false
                    action.onClick()
                })
            }
        }
    }
}

@Composable
private fun FolderIcon(kind: FolderKind) {
    val tint = MaterialTheme.colorScheme.primary
    when (kind) {
        FolderKind.CAMERA -> Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = tint)
        FolderKind.AUTO_UPLOADS -> Icon(Icons.Default.CloudUpload, contentDescription = null, tint = tint)
        FolderKind.FOLDER -> Icon(Icons.Default.Folder, contentDescription = null, tint = tint)
        // В наборе значков Compose нет Folder Sync: папка с отметкой синхронизации.
        FolderKind.ACTIVE_SOURCE -> Box(modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = tint)
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .size(14.dp)
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
    val (title, confirm) = when (relocation.item) {
        is Relocated.MoveFile -> "Перемещение файла" to "Переместить сюда"
        is Relocated.CopyFile -> "Копирование файла" to "Копировать сюда"
        is Relocated.MoveFolder -> "Перемещение папки" to "Переместить сюда"
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
                text = "$title «${relocation.item.name}»",
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
    val (title, confirm, subject) = when (relocation.item) {
        is Relocated.MoveFile -> Triple("Переместить файл?", "Переместить", "Файл")
        is Relocated.CopyFile -> Triple("Копировать файл?", "Копировать", "Файл")
        is Relocated.MoveFolder -> Triple("Переместить папку?", "Переместить", "Папка")
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

private fun fileIcon(fileType: String?): ImageVector = when (fileType) {
    "IMAGE" -> Icons.Default.Image
    "VIDEO" -> Icons.Default.Movie
    "AUDIO" -> Icons.Default.AudioFile
    "DOCUMENT" -> Icons.Default.Description
    "ARCHIVE" -> Icons.Default.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

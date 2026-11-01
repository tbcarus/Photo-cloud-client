package ru.tbcarus.photo_cloud_client.ui.screens.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto

// Следующая страница запрашивается заранее, до появления последней строки.
private const val LOAD_MORE_THRESHOLD = 10

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenNetwork: () -> Unit,
    onOpenLogin: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    // Статус, а не вход на экран: адрес может восстановиться уже при открытом экране.
    LaunchedEffect(state.status) { viewModel.ensureLoaded() }
    BackHandler(enabled = state.canGoUp) { viewModel.navigateUp() }

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
        LibraryHeader(state, onRefresh = viewModel::refresh, onUp = viewModel::navigateUp)
        state.notice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
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
                        LibraryList(state, listState, viewModel)
                    }
            }
        }
    }
}

@Composable
private fun LibraryHeader(state: LibraryUiState, onRefresh: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Библиотека",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
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

@Composable
private fun LibraryList(state: LibraryUiState, listState: LazyListState, viewModel: LibraryViewModel) {
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

    val inCamera = state.currentFolder?.isCamera == true
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.folders, key = { "folder-${it.id}" }) { folder ->
            FolderRow(folder, onClick = { viewModel.openFolder(folder) })
            HorizontalDivider()
        }
        items(state.files, key = { "file-${it.id}" }) { file ->
            FileRow(file, inCamera)
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

@Composable
private fun FolderRow(folder: FolderDto, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Icon(
            imageVector = if (folder.isCamera) Icons.Default.PhotoCamera else Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Нажатие не обрабатывается: действия с файлами относятся к следующим этапам.
@Composable
private fun FileRow(file: FileItemDto, inCamera: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = fileIcon(file.fileType),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column {
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

private fun fileIcon(fileType: String?): ImageVector = when (fileType) {
    "IMAGE" -> Icons.Default.Image
    "VIDEO" -> Icons.Default.Movie
    "AUDIO" -> Icons.Default.AudioFile
    "DOCUMENT" -> Icons.Default.Description
    "ARCHIVE" -> Icons.Default.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

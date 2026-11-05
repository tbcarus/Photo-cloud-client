package ru.tbcarus.photo_cloud_client.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dagger.hilt.android.EntryPointAccessors
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.library.original.ViewerError
import ru.tbcarus.photo_cloud_client.library.original.ViewerImage
import ru.tbcarus.photo_cloud_client.library.original.ViewerImageSource
import ru.tbcarus.photo_cloud_client.library.thumbnail.LibraryThumbnails
import ru.tbcarus.photo_cloud_client.library.thumbnail.Thumbnail
import ru.tbcarus.photo_cloud_client.library.thumbnail.ThumbnailsEntryPoint

internal const val VIEWER_TAG = "image-viewer"
internal const val VIEWER_IMAGE_TAG = "viewer-image"
internal const val VIEWER_LOADING_TAG = "viewer-loading"

/** Полноэкранный просмотр поверх всего приложения; открыт, пока LibraryViewModel хранит файл просмотра. */
@Composable
fun LibraryImageViewer(
    viewModel: LibraryViewModel,
    thumbnails: LibraryThumbnails = rememberLibraryThumbnails(),
    source: ViewerImageSource = rememberOriginalImages()
) {
    val fileId by viewModel.viewerFileId.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val current = fileId ?: return
    ImageViewer(
        images = state.viewerImages,
        currentId = current,
        thumbnails = thumbnails,
        source = source,
        onPage = viewModel::onViewerPage,
        onClose = viewModel::closeViewer
    )
}

@Composable
private fun rememberOriginalImages(): ViewerImageSource {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, ThumbnailsEntryPoint::class.java).originalImages()
    }
}

/**
 * Перелистывание изображений папки в порядке Library. В обычном масштабе горизонтальный жест листает,
 * при увеличении листание выключено и жест сдвигает изображение.
 */
@Composable
internal fun ImageViewer(
    images: List<FileItemDto>,
    currentId: Long,
    thumbnails: LibraryThumbnails,
    source: ViewerImageSource,
    onPage: (Long) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val initial = images.indexOfFirst { it.id == currentId }
    if (initial < 0) {
        // Файла больше нет в загруженном списке (папка перечитана): просмотр закрывается.
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val currentImages by rememberUpdatedState(images)
    val pagerState = rememberPagerState(initialPage = initial) { currentImages.size }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            zoomed = false
            currentImages.getOrNull(page)?.id?.let(onPage)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(VIEWER_TAG)
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            key = { page -> currentImages.getOrNull(page)?.id ?: page },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val file = currentImages[page]
            ViewerPage(
                file = file,
                thumbnails = thumbnails,
                source = source,
                onZoomChanged = { if (page == pagerState.currentPage) zoomed = it }
            )
        }
        val page = pagerState.currentPage.coerceIn(0, maxOf(0, images.size - 1))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                // Просмотр рисуется под системными панелями: заголовок не заходит под строку состояния.
                .statusBarsPadding()
                .padding(end = 16.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть просмотр", tint = Color.White)
            }
            Text(
                text = images.getOrNull(page)?.originalFilename.orEmpty(),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(text = "${page + 1} / ${images.size}", color = Color.White)
        }
    }
}

@Composable
private fun ViewerPage(
    file: FileItemDto,
    thumbnails: LibraryThumbnails,
    source: ViewerImageSource,
    onZoomChanged: (Boolean) -> Unit
) {
    var attempt by remember(file.id) { mutableIntStateOf(0) }
    // Загрузка живёт, пока страница на экране: уход со страницы, закрытие просмотра и смена сессии её отменяют.
    val image by produceState<ViewerImage?>(null, file.id, file.checksum, attempt) {
        value = null
        val loaded = source.load(file)
        value = loaded
        awaitDispose { (loaded as? ViewerImage.Shown)?.release?.invoke() }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val current = image) {
            null -> ViewerLoading(file, thumbnails)
            is ViewerImage.Shown -> ZoomableImage(current, onZoomChanged)
            is ViewerImage.Error -> ViewerFailure(current.reason, onRetry = { attempt++ })
        }
    }
}

// Пока грузится оригинал, видна миниатюра из кеша Coil и небольшой индикатор; Back и закрытие доступны.
@Composable
private fun ViewerLoading(file: FileItemDto, thumbnails: LibraryThumbnails) {
    val scope by thumbnails.currentScope.collectAsState()
    val thumbnail = remember(file.id, file.checksum, scope) { Thumbnail.of(file, scope) }
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().testTag(VIEWER_LOADING_TAG), contentAlignment = Alignment.Center) {
        if (thumbnail != null && !thumbnails.isFailed(thumbnail)) {
            val request = remember(thumbnail) { thumbnails.request(context, thumbnail) }
            AsyncImage(
                model = request,
                imageLoader = thumbnails.imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .size(24.dp)
        )
    }
}

@Composable
private fun ViewerFailure(reason: ViewerError, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(32.dp)
    ) {
        Text(text = viewerErrorText(reason), color = Color.White, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text("Повторить") }
    }
}

internal fun viewerErrorText(reason: ViewerError): String = when (reason) {
    ViewerError.NO_CONNECTION -> "Нет связи с сервером"
    ViewerError.NOT_FOUND -> "Файл не найден на сервере"
    ViewerError.UNAUTHORIZED -> "Требуется вход"
    ViewerError.NO_SPACE -> "Недостаточно места на устройстве"
    ViewerError.CORRUPTED -> "Скачанные данные не совпадают с файлом на сервере"
    ViewerError.FAILED -> "Не удалось загрузить изображение"
    ViewerError.DECODE -> "Не удалось показать изображение"
}

/** Двойное нажатие и сведение пальцев масштабируют, перетаскивание сдвигает увеличенное изображение. */
@Composable
private fun ZoomableImage(image: ViewerImage.Shown, onZoomChanged: (Boolean) -> Unit) {
    val bitmap = remember(image) { image.bitmap.asImageBitmap() }
    var transform by remember(image) { mutableStateOf(ViewerTransform()) }
    var container by remember { mutableStateOf(Size.Zero) }
    // Размер контейнера меняется после первой раскладки: вписанный размер считается при каждом жесте.
    fun fitted() = ViewerZoom.fitted(bitmap.width, bitmap.height, container)
    LaunchedEffect(transform.zoomed) { onZoomChanged(transform.zoomed) }

    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { container = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(image) {
                detectTapGestures(onDoubleTap = { tap ->
                    transform = ViewerZoom.doubleTap(transform, tap - container.center(), container, fitted())
                })
            }
            .pointerInput(image) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // Одним пальцем при исходном масштабе жест достаётся листанию. Событие отпускания
                        // без нажатых пальцев пропускается: центра жеста у него нет.
                        if (fingers >= 2 || (fingers == 1 && transform.zoomed)) {
                            transform = ViewerZoom.transform(
                                transform,
                                zoom = event.calculateZoom(),
                                pan = event.calculatePan(),
                                focus = event.calculateCentroid(useCurrent = true) - container.center(),
                                container = container,
                                fitted = fitted()
                            )
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = transform.scale
                scaleY = transform.scale
                translationX = transform.offset.x
                translationY = transform.offset.y
            }
            .testTag(VIEWER_IMAGE_TAG)
    )
}

private fun Size.center(): Offset = Offset(width / 2f, height / 2f)

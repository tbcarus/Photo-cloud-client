package ru.tbcarus.photo_cloud_client.ui.screens.library

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.library.pdf.PdfDocument
import ru.tbcarus.photo_cloud_client.library.pdf.PdfDocuments
import ru.tbcarus.photo_cloud_client.library.pdf.PdfError
import ru.tbcarus.photo_cloud_client.library.pdf.PdfOpenResult
import ru.tbcarus.photo_cloud_client.library.pdf.pdfPageAfter
import ru.tbcarus.photo_cloud_client.library.thumbnail.ThumbnailsEntryPoint
import kotlin.math.abs

/** Просмотр PDF поверх всего приложения; открыт, пока LibraryViewModel хранит документ. */
@Composable
fun LibraryPdfViewer(viewModel: LibraryViewModel) {
    val fileId by viewModel.pdfFileId.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val id = fileId ?: return
    val file = state.files.firstOrNull { it.id == id }
    if (file == null) {
        // Файла больше нет в загруженном списке (папка перечитана): просмотр закрывается.
        LaunchedEffect(id) { viewModel.closePdf() }
        return
    }
    val appContext = LocalContext.current.applicationContext
    val documents = remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, ThumbnailsEntryPoint::class.java).pdfDocuments()
    }
    PdfViewer(file = file, documents = documents, onClose = viewModel::closePdf)
}

/**
 * Постраничный просмотр: на экране одна страница, переход кнопками или горизонтальным жестом в обычном масштабе.
 * Документ живёт, пока открыт просмотр: закрытие, смена файла и смена сессии отменяют загрузку и освобождают его.
 */
@Composable
private fun PdfViewer(file: FileItemDto, documents: PdfDocuments, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var attempt by remember(file.id) { mutableIntStateOf(0) }
    val opened by produceState<PdfOpenResult?>(null, file.id, file.checksum, attempt) {
        value = null
        val result = documents.open(file)
        value = result
        awaitDispose { (result as? PdfOpenResult.Ready)?.document?.close() }
    }
    var page by rememberSaveable(file.id) { mutableIntStateOf(0) }
    val document = (opened as? PdfOpenResult.Ready)?.document
    val count = document?.pageCount ?: 0
    val current = pdfPageAfter(page, 0, count)
    val turn: (Int) -> Unit = { delta -> page = pdfPageAfter(current, delta, count) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (val result = opened) {
            null -> PdfProgress("Загрузка документа")
            is PdfOpenResult.Error -> PdfFailure(result.reason, onRetry = { attempt++ })
            is PdfOpenResult.Ready -> PdfPage(result.document, current, onSwipe = turn)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .statusBarsPadding()
                .padding(end = 16.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть документ", tint = Color.White)
            }
            Text(
                text = file.originalFilename.orEmpty(),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (document != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp)
            ) {
                TextButton(onClick = { turn(-1) }, enabled = current > 0) { Text("Предыдущая") }
                Text(text = "Страница ${current + 1} из $count", color = Color.White)
                TextButton(onClick = { turn(1) }, enabled = current < count - 1) { Text("Следующая") }
            }
        }
    }
}

private sealed interface PageImage {
    class Shown(val bitmap: Bitmap) : PageImage
    data class Failed(val reason: PdfError) : PageImage
}

@Composable
private fun PdfPage(document: PdfDocument, index: Int, onSwipe: (Int) -> Unit) {
    var attempt by remember(document, index) { mutableIntStateOf(0) }
    // Рендерится только текущая страница; уход с неё отменяет ожидание рендера, Bitmap прежней страницы
    // больше не удерживается.
    val image by produceState<PageImage?>(null, document, index, attempt) {
        value = null
        value = try {
            PageImage.Shown(document.render(index))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            PageImage.Failed(PdfError.NO_MEMORY)
        } catch (e: Exception) {
            PageImage.Failed(PdfError.DAMAGED)
        }
    }
    when (val current = image) {
        null -> PdfProgress(null)
        is PageImage.Shown -> ZoomablePage(current.bitmap, onSwipe)
        is PageImage.Failed -> PdfFailure(current.reason, onRetry = { attempt++ })
    }
}

@Composable
private fun PdfProgress(text: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize()
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
        if (text != null) Text(text = text, color = Color.White)
    }
}

@Composable
private fun PdfFailure(reason: PdfError, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize().padding(32.dp)
    ) {
        Text(text = pdfErrorText(reason), color = Color.White, textAlign = TextAlign.Center)
        // Проверенный по SHA-256 документ не изменится от повтора: для повреждённого и защищённого кнопки нет.
        if (reason != PdfError.DAMAGED && reason != PdfError.PROTECTED) {
            Button(onClick = onRetry) { Text("Повторить") }
        }
    }
}

internal fun pdfErrorText(reason: PdfError): String = when (reason) {
    PdfError.NO_CONNECTION -> "Нет связи с сервером"
    PdfError.NOT_FOUND -> "Файл не найден на сервере"
    PdfError.UNAUTHORIZED -> "Требуется вход"
    PdfError.NO_SPACE -> "Недостаточно места на устройстве"
    PdfError.CORRUPTED -> "Скачанные данные не совпадают с файлом на сервере"
    PdfError.FAILED -> "Не удалось загрузить документ"
    PdfError.DAMAGED -> "Документ повреждён или не читается"
    PdfError.PROTECTED -> "Документ защищён паролем: такие PDF не открываются"
    PdfError.NO_MEMORY -> "Недостаточно памяти для показа страницы"
}

/**
 * Масштаб — двойным нажатием и сведением пальцев, перетаскивание сдвигает увеличенную страницу (ViewerZoom 7.3).
 * Горизонтальный жест листает только в обычном масштабе и без второго пальца.
 */
@Composable
private fun ZoomablePage(bitmap: Bitmap, onSwipe: (Int) -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var transform by remember(bitmap) { mutableStateOf(ViewerTransform()) }
    var container by remember { mutableStateOf(Size.Zero) }
    val swipe by rememberUpdatedState(onSwipe)
    fun fitted() = ViewerZoom.fitted(image.width, image.height, container)

    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { container = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { tap ->
                    transform = ViewerZoom.doubleTap(transform, tap - container.center(), container, fitted())
                })
            }
            .pointerInput(bitmap) {
                val threshold = 48.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var drag = Offset.Zero
                    var multiTouch = false
                    val startedZoomed = transform.zoomed
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) multiTouch = true
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
                        } else if (fingers == 1) {
                            drag += event.calculatePan()
                        }
                    } while (event.changes.any { it.pressed })
                    // Увеличенная страница двигается в пределах краёв и страницу не переключает.
                    if (!multiTouch && !startedZoomed && !transform.zoomed && abs(drag.x) > threshold && abs(drag.x) > abs(drag.y)) {
                        swipe(if (drag.x < 0) 1 else -1)
                    }
                }
            }
            .graphicsLayer {
                scaleX = transform.scale
                scaleY = transform.scale
                translationX = transform.offset.x
                translationY = transform.offset.y
            }
    )
}

private fun Size.center(): Offset = Offset(width / 2f, height / 2f)

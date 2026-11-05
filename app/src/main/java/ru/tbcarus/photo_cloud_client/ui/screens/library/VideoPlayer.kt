package ru.tbcarus.photo_cloud_client.ui.screens.library

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dagger.hilt.android.EntryPointAccessors
import ru.tbcarus.photo_cloud_client.library.thumbnail.ThumbnailsEntryPoint
import ru.tbcarus.photo_cloud_client.library.video.VIDEO_UNSUPPORTED_TEXT
import ru.tbcarus.photo_cloud_client.library.video.createVideoPlayer
import ru.tbcarus.photo_cloud_client.library.video.videoErrorText
import ru.tbcarus.photo_cloud_client.library.video.videoUrl

/**
 * Встроенный плеер видео поверх всего приложения; открыт, пока LibraryViewModel хранит видео.
 * Один плеер на открытое видео: рекомпозиция его не пересоздаёт, закрытие освобождает. Уход в фон ставит паузу,
 * после возврата воспроизведение само не продолжается. При повороте Activity пересоздаётся: прежний плеер
 * освобождается, новый продолжает с сохранённой позиции.
 */
@Composable
fun LibraryVideoPlayer(viewModel: LibraryViewModel) {
    val fileId by viewModel.videoFileId.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val id = fileId ?: return
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, ThumbnailsEntryPoint::class.java)
    }
    val url = videoUrl(entryPoint.baseUrlProvider().baseUrl, id)
    val title = state.files.firstOrNull { it.id == id }?.originalFilename.orEmpty()
    val onClose = viewModel::closeVideo
    BackHandler(onBack = onClose)

    var savedPosition by rememberSaveable(id) { mutableLongStateOf(0L) }
    // Нажатие на видео — команда на воспроизведение: после готовности плеер начинает играть.
    var savedPlayWhenReady by rememberSaveable(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val player = remember(id, url) { createVideoPlayer(context, entryPoint.authClient()) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = videoErrorText(e)
            }

            // Неподдерживаемую видеодорожку плеер не выбирает и без ошибки играл бы только звук.
            override fun onTracksChanged(tracks: Tracks) {
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                if (video.isNotEmpty() && video.none { it.isSupported }) {
                    player.pause()
                    error = VIDEO_UNSUPPORTED_TEXT
                }
            }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(url))
        player.seekTo(savedPosition)
        player.playWhenReady = savedPlayWhenReady
        player.prepare()
        onDispose {
            player.removeListener(listener)
            // Освобождение прерывает незавершённые HTTP-запросы.
            player.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                savedPosition = player.currentPosition
                // При повороте воспроизведение продолжится в новом плеере, при уходе в фон — нет.
                savedPlayWhenReady = context.findActivity()?.isChangingConfigurations == true && player.playWhenReady
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        PlayerSurface(player)
        error?.let { message ->
            // Сообщение закрывает видео и элементы Media3: они не перекрывают текст и не принимают касаний.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) { detectTapGestures() }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(text = message, color = Color.White, textAlign = TextAlign.Center)
                    // Повтор — только по нажатию: плеер заново открывает тот же адрес с прежней позиции.
                    Button(onClick = {
                        error = null
                        player.prepare()
                        player.play()
                    }) { Text("Повторить") }
                }
            }
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
                Icon(Icons.Default.Close, contentDescription = "Закрыть видео", tint = Color.White)
            }
            Text(text = title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/** Штатные элементы Media3: воспроизведение, пауза, шкала, позиция и длительность, индикатор буферизации. */
@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(player: Player) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { it.player = player },
        // Отвязка от плеера: View не удерживает освобождённый плеер и Surface.
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize()
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

package ru.tbcarus.photo_cloud_client.library.video

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.Call
import ru.tbcarus.photo_cloud_client.api.ApiPaths
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto

/** Видео открывается встроенным плеером по MIME Server (`video/…`), а не по расширению имени. */
fun isPlayableVideo(file: FileItemDto): Boolean =
    file.mimeType?.lowercase()?.startsWith("video/") == true && (file.id ?: 0) > 0

/** Адрес оригинала: тот же download, что у ручного скачивания. Токен идёт заголовком клиента, не в URL. */
fun videoUrl(baseUrl: String, fileId: Long): String = baseUrl + ApiPaths.FILES + "/" + fileId + "/download"

/** Фабрика источника: авторизованный клиент приложения, Range при перемотке — штатно Media3, без дискового кеша. */
@OptIn(UnstableApi::class)
fun videoDataSourceFactory(client: Call.Factory): OkHttpDataSource.Factory = OkHttpDataSource.Factory(client)

/** Токен добавляет AuthInterceptor, 401 обрабатывает TokenAuthenticator: плеер своих токенов не ведёт. */
@OptIn(UnstableApi::class)
fun createVideoPlayer(context: Context, client: Call.Factory): ExoPlayer =
    ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(videoDataSourceFactory(client)))
        .build()

/** Текст ошибки воспроизведения: несколько понятных случаев, остальное — общее сообщение. */
@OptIn(UnstableApi::class)
fun videoErrorText(error: PlaybackException): String {
    val httpCode = generateSequence<Throwable>(error) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.responseCode
    return when {
        httpCode == 404 -> "Файл недоступен на сервере"
        httpCode == 401 || httpCode == 403 -> "Требуется вход"
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Нет связи с сервером"
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> VIDEO_UNSUPPORTED_TEXT
        else -> "Не удалось воспроизвести видео"
    }
}

const val VIDEO_UNSUPPORTED_TEXT = "Формат видео не поддерживается на этом устройстве"

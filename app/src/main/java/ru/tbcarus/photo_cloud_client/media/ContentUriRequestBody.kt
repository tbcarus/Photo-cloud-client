package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.net.Uri
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

/**
 * RequestBody, стримящий содержимое content://uri напрямую в сетевой sink,
 * не загружая файл целиком в память.
 */
class ContentUriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val mimeType: String,
    private val size: Long
) : RequestBody() {

    override fun contentType(): MediaType? = mimeType.toMediaTypeOrNull()

    // Известный размер позволяет OkHttp не буферизовать тело; иначе chunked (-1).
    override fun contentLength(): Long = if (size > 0) size else -1

    override fun writeTo(sink: BufferedSink) {
        // Поток открываем внутри writeTo, потому OkHttp может повторно отправить тело
        // после refresh token, и writeTo будет вызван больше одного раза.
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Не удалось открыть поток для uri: $uri")
        inputStream.use { stream ->
            sink.writeAll(stream.source())
        }
    }
}

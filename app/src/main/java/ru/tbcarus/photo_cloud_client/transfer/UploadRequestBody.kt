package ru.tbcarus.photo_cloud_client.transfer

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream

/** Источник не читается: ошибка файла, а не связи, и повтор не поможет. */
class LocalSourceException(cause: Throwable) : IOException(cause.message, cause)

/** Источник длиннее лимита Server: передача остановлена до его отказа. */
class UploadTooLargeException : IOException("Файл больше допустимого размера")

/**
 * Тело одного файла ручной загрузки. Поток открывается в каждом writeTo: OkHttp отправляет тело повторно после refresh.
 * Длина не объявляется: SIZE провайдера бывает неточным, а несовпадение с фактической длиной оборвало бы запрос.
 */
class UploadRequestBody(
    private val open: () -> InputStream,
    private val mimeType: String?,
    private val maxBytes: Long,
    private val onProgress: (Long) -> Unit
) : RequestBody() {

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }

    override fun contentType(): MediaType? = mimeType?.toMediaTypeOrNull()

    override fun contentLength(): Long = -1

    override fun writeTo(sink: BufferedSink) {
        val input = try {
            open()
        } catch (e: Exception) {
            throw LocalSourceException(e)
        }
        input.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var written = 0L
            onProgress(0)
            while (true) {
                val read = try {
                    stream.read(buffer)
                } catch (e: IOException) {
                    throw LocalSourceException(e)
                }
                if (read == -1) break
                written += read
                if (written > maxBytes) throw UploadTooLargeException()
                sink.write(buffer, 0, read)
                onProgress(written)
            }
        }
    }
}

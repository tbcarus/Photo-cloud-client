package ru.tbcarus.photo_cloud_client.library.thumbnail

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Один повтор миниатюры после 503 SERVICE_BUSY: Server не держит очередь генераций и просит повторить через
 * Retry-After. Стоит только на клиенте миниатюр. Ожидание идёт в потоке OkHttp и прерывается отменой вызова.
 */
class ThumbnailRetryInterceptor(
    private val maxDelayMs: Long = MAX_DELAY_MS,
    private val pollMs: Long = POLL_MS
) : Interceptor {

    companion object {
        private const val SERVICE_UNAVAILABLE = 503
        private const val DEFAULT_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 2_000L
        private const val POLL_MS = 50L
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val first = chain.proceed(request)
        if (first.code != SERVICE_UNAVAILABLE || !request.url.encodedPath.endsWith("/thumbnail")) return first

        val delayMs = retryDelayMs(first.header("Retry-After"))
        first.close()
        var waited = 0L
        while (waited < delayMs) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            val step = minOf(pollMs, delayMs - waited)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted")
            }
            waited += step
        }
        if (chain.call().isCanceled()) throw IOException("Canceled")
        return chain.proceed(request)
    }

    // Retry-After в секундах; дата и мусор — ожидание по умолчанию. Предел не даёт Server задержать строку надолго.
    private fun retryDelayMs(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull()
        val delay = if (seconds == null || seconds < 0) DEFAULT_DELAY_MS else seconds * 1_000L
        return delay.coerceAtMost(maxDelayMs)
    }
}

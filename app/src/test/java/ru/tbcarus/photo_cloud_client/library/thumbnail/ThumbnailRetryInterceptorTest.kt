package ru.tbcarus.photo_cloud_client.library.thumbnail

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

class ThumbnailRetryInterceptorTest {

    private val server = MockWebServer()
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(ThumbnailRetryInterceptor(maxDelayMs = 300))
            .retryOnConnectionFailure(false)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun busy(retryAfter: String? = "1") =
        MockResponse().setResponseCode(503).apply { retryAfter?.let { setHeader("Retry-After", it) } }
            .setBody("""{"code":"SERVICE_BUSY"}""")

    private fun call(path: String = "/api/v1/files/1/thumbnail") =
        client.newCall(Request.Builder().url(server.url(path)).build())

    @Test
    fun busyThenSuccessRetriesOnceAfterRetryAfter() {
        server.enqueue(busy())
        server.enqueue(MockResponse().setBody("jpeg"))

        val started = System.nanoTime()
        call().execute().use { assertEquals(200, it.code) }

        assertEquals(2, server.requestCount)
        // Retry-After: 1 ограничен пределом 300 мс: ожидание было, но не дольше предела с запасом.
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("elapsed $elapsed", elapsed in 250..1500)
    }

    @Test
    fun twoBusyResponsesStopAfterSingleRetry() {
        server.enqueue(busy())
        server.enqueue(busy())
        server.enqueue(MockResponse().setBody("jpeg"))

        call().execute().use { assertEquals(503, it.code) }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun missingOrInvalidRetryAfterUsesBoundedDefault() {
        server.enqueue(busy(retryAfter = null))
        server.enqueue(MockResponse().setBody("jpeg"))
        server.enqueue(busy(retryAfter = "Wed, 21 Oct 2015 07:28:00 GMT"))
        server.enqueue(MockResponse().setBody("jpeg"))

        call().execute().use { assertEquals(200, it.code) }
        call().execute().use { assertEquals(200, it.code) }

        assertEquals(4, server.requestCount)
    }

    @Test
    fun definiteFailuresAreNotRetried() {
        for (code in listOf(400, 401, 403, 404, 415, 422, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))
            call().execute().use { assertEquals(code, it.code) }
        }
        assertEquals(7, server.requestCount)
    }

    @Test
    fun otherApiRequestsAreNotRetried() {
        server.enqueue(busy())
        server.enqueue(MockResponse().setBody("{}"))

        call("/api/v1/files/1/download").execute().use { assertEquals(503, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancelDuringRetryAfterSendsNoRetry() {
        val waiting = OkHttpClient.Builder()
            .addInterceptor(ThumbnailRetryInterceptor(maxDelayMs = 2_000))
            .build()
        server.enqueue(busy("2"))
        server.enqueue(MockResponse().setBody("jpeg"))
        val finished = CountDownLatch(1)
        var failure: IOException? = null

        val pending = waiting.newCall(Request.Builder().url(server.url("/api/v1/files/1/thumbnail")).build())
        pending.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failure = e
                finished.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                finished.countDown()
            }
        })
        server.takeRequest(2, TimeUnit.SECONDS)
        Thread.sleep(200)
        pending.cancel()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(failure != null)
        Thread.sleep(2_200)
        // Повтор после Retry-After так и не ушёл.
        assertEquals(1, server.requestCount)
    }
}

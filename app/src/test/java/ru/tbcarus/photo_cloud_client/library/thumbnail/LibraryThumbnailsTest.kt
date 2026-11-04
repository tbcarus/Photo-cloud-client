package ru.tbcarus.photo_cloud_client.library.thumbnail

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.network.AuthInterceptor
import ru.tbcarus.photo_cloud_client.core.network.TokenAuthenticator
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.di.NetworkModule
import ru.tbcarus.photo_cloud_client.library.LibrarySession
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryThumbnailsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val otherServer = MockWebServer()
    private val storage = InMemoryTokenStorage(Tokens("access-a", "refresh-a"))
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    // Ответы по пути: очередь на каждый файл, по умолчанию — JPEG.
    private val scripted = ConcurrentHashMap<String, MutableList<MockResponse>>()
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()
    @Volatile private var responseDelayMs = 0L
    @Volatile private var gate: CountDownLatch? = null
    private var nowMs = System.currentTimeMillis()

    private lateinit var sessions: MutableStateFlow<LibrarySession>
    private lateinit var thumbnails: LibraryThumbnails

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val current = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(current) { a, b -> maxOf(a, b) }
                try {
                    gate?.await(5, TimeUnit.SECONDS)
                    if (responseDelayMs > 0) Thread.sleep(responseDelayMs)
                    val queue = scripted[request.path!!]
                    return synchronized(scripted) { queue?.removeFirstOrNull() } ?: jpegResponse()
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        server.dispatcher = dispatcher
        otherServer.dispatcher = dispatcher
        server.start()
        otherServer.start()
        sessions = MutableStateFlow(LibrarySession(server.url("/").toString(), "user-a"))
        thumbnails = LibraryThumbnails(
            context,
            authClient(),
            sessions,
            Dispatchers.Unconfined,
            temp.newFolder("thumbnails"),
            ThumbnailRetryInterceptor(maxDelayMs = 200),
            now = { Instant.fromEpochMilliseconds(nowMs) }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        otherServer.shutdown()
        thumbnails.imageLoader.shutdown()
        Dispatchers.resetMain()
    }

    // Настоящий auth-клиент приложения: токен добавляет AuthInterceptor, 401 обрабатывает TokenAuthenticator.
    private fun authClient(): OkHttpClient {
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java))
            .apply { baseUrl = server.url("/").toString() }
        return NetworkModule.provideAuthOkHttpClient(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE },
            AuthInterceptor(storage),
            TokenAuthenticator(storage, baseUrlProvider, OkHttpClient())
        )
    }

    // JPEG 32×24 из test resources.
    private val jpeg: ByteArray = javaClass.getResourceAsStream("/thumbnail/sample.jpg")!!.use { it.readBytes() }

    private fun jpegResponse() = MockResponse()
        .setHeader("Content-Type", "image/jpeg")
        .setHeader("Cache-Control", "max-age=86400, private")
        .setHeader("ETag", "\"etag-1\"")
        .setHeader("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)))
        .setBody(Buffer().write(jpeg))

    private fun script(fileId: Long, vararg responses: MockResponse) {
        scripted["/api/v1/files/$fileId/thumbnail"] = responses.toMutableList()
    }

    private fun thumbnail(fileId: Long, checksum: String = "c$fileId", scope: ThumbnailScope = thumbnails.currentScope.value!!) =
        Thumbnail(scope, fileId, checksum)

    private fun load(thumbnail: Thumbnail, memory: Boolean = true): ImageResult = runBlocking {
        val request = thumbnails.request(context, thumbnail).newBuilder()
            .apply { if (!memory) memoryCachePolicy(CachePolicy.DISABLED) }
            .build()
        thumbnails.imageLoader.execute(request)
    }

    private fun thumbnailRequests() = requests.filter { it.path!!.endsWith("/thumbnail") }

    @Test
    fun loadsJpegWithCurrentAuthorization() {
        val result = load(thumbnail(7))

        assertTrue(result.toString(), result is SuccessResult)
        val request = thumbnailRequests().single()
        assertEquals("/api/v1/files/7/thumbnail", request.path)
        assertEquals("Bearer access-a", request.getHeader("Authorization"))
    }

    // Ошибку декодирования здесь не проверить: Robolectric на Windows без native graphics «декодирует» любые байты.
    @Test
    fun emptyOrCorruptedBodyIsNotRequestedAgain() {
        script(1, MockResponse().setHeader("Content-Type", "image/jpeg").setBody("not a jpeg at all"))
        script(2, MockResponse().setHeader("Content-Type", "image/jpeg"))

        load(thumbnail(1))
        load(thumbnail(2))

        assertEquals(listOf("/api/v1/files/1/thumbnail", "/api/v1/files/2/thumbnail"), thumbnailRequests().map { it.path })
    }

    @Test
    fun definiteFailuresAreNotRetriedAndNotRequestedAgain() {
        for ((id, code) in listOf(1L to 404, 2L to 415, 3L to 422)) {
            script(id, MockResponse().setResponseCode(code))
            val thumbnail = thumbnail(id)

            assertTrue(load(thumbnail) is ErrorResult)
            assertTrue("$code remembered", thumbnails.isFailed(thumbnail))
        }
        assertEquals(3, thumbnailRequests().size)
    }

    @Test
    fun busyThenSuccessRetriesOnce() {
        script(4, MockResponse().setResponseCode(503).setHeader("Retry-After", "1"), jpegResponse())

        assertTrue(load(thumbnail(4)) is SuccessResult)
        assertEquals(2, thumbnailRequests().size)
    }

    @Test
    fun twoBusyResponsesGiveErrorThatCanBeRetriedLater() {
        script(4, MockResponse().setResponseCode(503).setHeader("Retry-After", "1"),
            MockResponse().setResponseCode(503).setHeader("Retry-After", "1"))
        val thumbnail = thumbnail(4)

        assertTrue(load(thumbnail) is ErrorResult)
        assertEquals(2, thumbnailRequests().size)
        // Занятость временная: при следующем показе строки запрос снова допустим.
        assertFalse(thumbnails.isFailed(thumbnail))
    }

    @Test
    fun memoryAndDiskCachePreventReloadWithinMaxAge() {
        val thumbnail = thumbnail(8)

        assertTrue(load(thumbnail) is SuccessResult)
        assertTrue(load(thumbnail) is SuccessResult)
        thumbnails.imageLoader.memoryCache!!.clear()
        assertTrue(load(thumbnail) is SuccessResult)
        nowMs += TimeUnit.HOURS.toMillis(23)
        assertTrue(load(thumbnail, memory = false) is SuccessResult)

        assertEquals(1, thumbnailRequests().size)
    }

    @Test
    fun expiredDiskEntryIsRevalidatedWithEtag() {
        val thumbnail = thumbnail(8)
        assertTrue(load(thumbnail) is SuccessResult)
        script(8, MockResponse().setResponseCode(304).setHeader("ETag", "\"etag-1\"")
            .setHeader("Cache-Control", "max-age=86400, private"))

        nowMs += TimeUnit.HOURS.toMillis(25)
        val result = load(thumbnail, memory = false)

        assertTrue(result.toString(), result is SuccessResult)
        val revalidation = thumbnailRequests()[1]
        assertEquals("\"etag-1\"", revalidation.getHeader("If-None-Match"))
        assertEquals(0L, revalidation.bodySize)
        assertEquals(2, thumbnailRequests().size)
    }

    @Test
    fun newChecksumIsLoadedAgain() {
        assertTrue(load(thumbnail(9, checksum = "old")) is SuccessResult)
        assertTrue(load(thumbnail(9, checksum = "new")) is SuccessResult)

        assertEquals(2, thumbnailRequests().size)
    }

    @Test
    fun noMoreThanThreeRequestsAtOnce() {
        responseDelayMs = 300

        val results = runBlocking(Dispatchers.IO) {
            (1L..8L).map { id -> async { load(thumbnail(id)) } }.awaitAll()
        }

        assertTrue(results.all { it is SuccessResult })
        assertEquals(8, thumbnailRequests().size)
        assertTrue("max in flight ${maxInFlight.get()}", maxInFlight.get() <= LibraryThumbnails.MAX_PARALLEL)
        assertEquals(LibraryThumbnails.MAX_PARALLEL, maxInFlight.get())
    }

    @Test
    fun accountChangeClearsCachesAndIsolatesKeys() {
        val before = thumbnail(5)
        script(6, MockResponse().setResponseCode(415))
        assertTrue(load(before) is SuccessResult)
        load(thumbnail(6))
        assertTrue(thumbnails.isFailed(thumbnail(6)))

        storage.saveTokens(Tokens("access-b", "refresh-b"))
        sessions.value = LibrarySession(server.url("/").toString(), "user-b")

        val after = thumbnail(5)
        assertNotEquals(before.scope, after.scope)
        assertNotEquals(before.cacheKey, after.cacheKey)
        assertEquals(0L, thumbnails.imageLoader.memoryCache!!.size)
        assertEquals(0L, thumbnails.imageLoader.diskCache!!.size)
        assertFalse(thumbnails.isFailed(thumbnail(6, scope = before.scope)))

        // Прежний контекст в сеть не идёт; тот же fileId нового пользователя загружается заново, с его токеном.
        assertTrue(load(before) is ErrorResult)
        assertTrue(load(after) is SuccessResult)
        assertEquals(3, thumbnailRequests().size)
        assertEquals("Bearer access-b", thumbnailRequests().last().getHeader("Authorization"))
    }

    @Test
    fun serverChangeDoesNotReuseThumbnailWithSameFileId() {
        assertTrue(load(thumbnail(5)) is SuccessResult)

        sessions.value = LibrarySession(otherServer.url("/").toString(), "user-a")
        val result = load(thumbnail(5))

        assertTrue(result is SuccessResult)
        assertEquals(2, thumbnailRequests().size)
        assertEquals(otherServer.port, thumbnailRequests().last().requestUrl!!.port)
    }

    @Test
    fun resultOfPreviousSessionIsDroppedWhenSessionChangesDuringLoad() {
        val thumbnail = thumbnail(10)
        gate = CountDownLatch(1)

        val result = runBlocking(Dispatchers.IO) {
            val pending = async { load(thumbnail) }
            while (thumbnailRequests().isEmpty()) Thread.sleep(10)
            sessions.value = LibrarySession(server.url("/").toString(), "user-b")
            gate!!.countDown()
            pending.await()
        }

        assertTrue(result.toString(), result is ErrorResult)
    }

    @Test
    fun logoutLeavesNoScope() {
        sessions.value = LibrarySession(server.url("/").toString(), null)

        assertNull(thumbnails.currentScope.value)
        assertEquals(0L, thumbnails.imageLoader.diskCache!!.size)
    }
}

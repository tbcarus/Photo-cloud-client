package ru.tbcarus.photo_cloud_client.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.di.NetworkModule
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Боевой auth-клиент (AuthInterceptor + TokenAuthenticator) против MockWebServer.
 * Диспетчер отвечает по пути, а обработчик refresh может менять хранилище, моделируя login/logout во время refresh.
 */
class TokenAuthenticatorTest {

    private companion object {
        const val PROTECTED = "/api/v1/folders/root"
        const val REFRESH = "/api/v1/auth/refresh-token"
        val OLD = Tokens("old-access", "refresh-1")
        val NEW_LOGIN = Tokens("login-access", "refresh-2")
    }

    private val server = MockWebServer()
    private val storage = InMemoryTokenStorage(OLD)
    private val refreshCount = AtomicInteger()

    // Access, которые принимает защищённый endpoint; остальные получают 401 как от entry point Server.
    @Volatile
    private var acceptedAccess = setOf("new-access")

    @Volatile
    private var onRefresh: () -> MockResponse = { refreshOk("new-access") }

    private lateinit var baseUrlProvider: BaseUrlProvider
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == REFRESH) {
                    refreshCount.incrementAndGet()
                    onRefresh()
                } else if (request.getHeader("Authorization")?.removePrefix("Bearer ") in acceptedAccess) {
                    MockResponse().setBody("ok")
                } else {
                    errorResponse(401, "UNAUTHORIZED")
                }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java))
            .apply { baseUrl = "http://127.0.0.1:${server.port}/" }
        client = authClient(storage)
    }

    private fun authClient(tokenStorage: TokenStorage): OkHttpClient {
        // Refresh идёт через plain-клиент; короткий timeout нужен только для теста зависшего ответа.
        val plainClient = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()
        return NetworkModule.provideAuthOkHttpClient(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE },
            AuthInterceptor(tokenStorage),
            TokenAuthenticator(tokenStorage, baseUrlProvider, plainClient)
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successfulRefreshSavesNewAccessAndRetriesOnce() {
        callProtected().use { response ->
            assertEquals(200, response.code)
        }

        assertEquals(Tokens("new-access", "refresh-1"), storage.getTokens())
        assertEquals(1, refreshCount.get())
        // 401 со старым access, refresh, один повтор с новым access.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun oldServerResponseWithoutRefreshKeepsStoredRefresh() {
        // Ответ без замены: поля нет или оно null — сохраняется прежний refresh.
        listOf(refreshOk("new-access"), refreshOk("new-access", refresh = null)).forEach { body ->
            storage.saveTokens(OLD)
            onRefresh = { body }

            callProtected().use { response -> assertEquals(200, response.code) }

            assertEquals(Tokens("new-access", OLD.refreshToken), storage.getTokens())
        }
    }

    @Test
    fun responseWithReplacementSavesNewPair() {
        onRefresh = { refreshOk("new-access", refresh = "refresh-2-rotated") }

        callProtected().use { response -> assertEquals(200, response.code) }

        assertEquals(Tokens("new-access", "refresh-2-rotated"), storage.getTokens())
        assertEquals(1, refreshCount.get())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun unconfirmedStorageWriteIsTemporaryFailure() {
        val failing = object : TokenStorage by storage {
            override fun replaceIf(expected: Tokens, replacement: Tokens?): Boolean =
                throw IOException("Token storage did not confirm the write")
        }
        onRefresh = { refreshOk("new-access", refresh = "refresh-2-rotated") }

        val exception = assertThrows(IOException::class.java) {
            authClient(failing).newCall(protectedRequest()).execute().close()
        }

        assertEquals(1, refreshCount.get())
        assertNoTokens(exception)
        assertFalse(exception.message.orEmpty().contains("refresh-2-rotated"))
    }

    @Test
    fun invalidRefreshTokenClearsCurrentPair() {
        onRefresh = { errorResponse(401, "INVALID_REFRESH_TOKEN") }

        callProtected().use { response -> assertEquals(401, response.code) }

        assertNull(storage.getTokens())
        assertEquals(1, refreshCount.get())
    }

    @Test
    fun revokedRefreshTokenClearsCurrentPair() {
        onRefresh = { errorResponse(403, "REFRESH_TOKEN_REVOKED") }

        callProtected().use { response -> assertEquals(401, response.code) }

        assertNull(storage.getTokens())
    }

    @Test
    fun otherRefreshFailuresAreTemporaryAndKeepPair() {
        val temporaryFailures = listOf(
            errorResponse(401, "UNAUTHORIZED"),
            MockResponse().setResponseCode(401),
            errorResponse(403, "FORBIDDEN"),
            errorResponse(400, "VALIDATION_ERROR"),
            MockResponse().setResponseCode(408),
            MockResponse().setResponseCode(429),
            errorResponse(500, "INTERNAL_ERROR"),
            MockResponse().setResponseCode(503)
        )
        temporaryFailures.forEach { failure ->
            onRefresh = { failure }
            val refreshesBefore = refreshCount.get()

            val exception = assertThrows(IOException::class.java) { callProtected().close() }

            assertEquals(OLD, storage.getTokens())
            // Один refresh на вызов; 408 OkHttp сам повторяет один раз — бесконечных повторов нет.
            assertTrue(refreshCount.get() - refreshesBefore in 1..2)
            assertNoTokens(exception)
        }
    }

    @Test
    fun invalidSuccessfulRefreshBodyIsTemporary() {
        listOf(
            MockResponse().setBody("{}"),
            refreshOk(""),
            refreshOk("new-access", refresh = ""),
            MockResponse().setBody("not json")
        ).forEach { body ->
            onRefresh = { body }

            val exception = assertThrows(IOException::class.java) { callProtected().close() }

            assertEquals(OLD, storage.getTokens())
            assertNoTokens(exception)
        }
    }

    @Test
    fun timeoutAndDisconnectKeepPair() {
        onRefresh = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        assertThrows(IOException::class.java) { callProtected().close() }
        assertEquals(OLD, storage.getTokens())

        onRefresh = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        assertThrows(IOException::class.java) { callProtected().close() }
        assertEquals(OLD, storage.getTokens())
    }

    @Test
    fun repeated401AfterRefreshDoesNotLoop() {
        acceptedAccess = emptySet()

        callProtected().use { response -> assertEquals(401, response.code) }

        assertEquals(1, refreshCount.get())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun concurrentRequestsWithExpiredAccessRefreshOnce() {
        onRefresh = {
            // Держим refresh, чтобы второй запрос успел получить свой 401 и ждать на блокировке.
            Thread.sleep(300)
            refreshOk("new-access")
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val calls = List(2) { executor.submit(Callable { callProtected().use { it.code } }) }

            assertEquals(listOf(200, 200), calls.map { it.get(5, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, refreshCount.get())
        assertEquals(Tokens("new-access", "refresh-1"), storage.getTokens())
    }

    @Test
    fun concurrentRequestsShareOneReplacement() {
        onRefresh = {
            Thread.sleep(300)
            refreshOk("new-access", refresh = "refresh-2-rotated")
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val calls = List(2) { executor.submit(Callable { callProtected().use { it.code } }) }

            assertEquals(listOf(200, 200), calls.map { it.get(5, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
        // Второй запрос повторяется с уже сохранённой парой и не предъявляет отозванный R1.
        assertEquals(1, refreshCount.get())
        assertEquals(Tokens("new-access", "refresh-2-rotated"), storage.getTokens())
    }

    @Test
    fun lateRejectionDoesNotClearNewLogin() {
        onRefresh = {
            storage.saveTokens(NEW_LOGIN)
            errorResponse(401, "INVALID_REFRESH_TOKEN")
        }

        callProtected().close()

        assertEquals(NEW_LOGIN, storage.getTokens())
    }

    @Test
    fun lateSuccessDoesNotOverwriteNewLoginAndRetriesWithIt() {
        acceptedAccess = setOf(NEW_LOGIN.accessToken)
        onRefresh = {
            storage.saveTokens(NEW_LOGIN)
            refreshOk("new-access")
        }

        callProtected().use { response -> assertEquals(200, response.code) }

        assertEquals(NEW_LOGIN, storage.getTokens())
    }

    @Test
    fun lateReplacementDoesNotOverwriteNewLogin() {
        acceptedAccess = setOf(NEW_LOGIN.accessToken)
        onRefresh = {
            storage.saveTokens(NEW_LOGIN)
            refreshOk("new-access", refresh = "refresh-2-rotated")
        }

        callProtected().use { response -> assertEquals(200, response.code) }

        assertEquals(NEW_LOGIN, storage.getTokens())
    }

    @Test
    fun logoutDuringRefreshDoesNotRestoreOldSession() {
        listOf(refreshOk("new-access"), refreshOk("new-access", refresh = "refresh-2-rotated")).forEach { body ->
            storage.saveTokens(OLD)
            onRefresh = {
                storage.clear()
                body
            }

            callProtected().use { response -> assertEquals(401, response.code) }

            assertNull(storage.getTokens())
        }
    }

    private fun callProtected(): Response = client.newCall(protectedRequest()).execute()

    private fun protectedRequest() = Request.Builder().url("http://127.0.0.1:${server.port}$PROTECTED").build()

    // Ответ текущего Server без замены: поля refreshToken нет.
    private fun refreshOk(access: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"accessToken":"$access"}""")

    // refresh = null передаёт поле явным null.
    private fun refreshOk(access: String, refresh: String?) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"accessToken":"$access","refreshToken":${refresh?.let { "\"$it\"" } ?: "null"}}""")

    private fun errorResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"id":"00000000-0000-0000-0000-000000000000","code":"$code","message":"error"}""")

    private fun assertNoTokens(exception: IOException) {
        val text = generateSequence<Throwable>(exception) { it.cause }.joinToString(" ") { it.message.orEmpty() }
        assertFalse(text.contains(OLD.accessToken) || text.contains(OLD.refreshToken))
    }
}

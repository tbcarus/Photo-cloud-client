package ru.tbcarus.photo_cloud_client.auth

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.network.AuthInterceptor
import ru.tbcarus.photo_cloud_client.core.network.TokenAuthenticator
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.di.NetworkModule
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Logout через боевой auth-клиент: истёкший access может вызвать refresh с заменой R1 на R2. */
class AuthRepositoryLogoutTest {

    private companion object {
        const val REFRESH = "/api/v1/auth/refresh-token"
        const val LOGOUT = "/api/v1/auth/logout"
        val OLD = Tokens("old-access", "refresh-1")
    }

    private val server = MockWebServer()
    private val storage = InMemoryTokenStorage(OLD)
    private val refreshCount = AtomicInteger()

    // Значения refreshToken из тел принятых logout, по порядку.
    private val loggedOut: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var acceptedAccess = setOf("new-access")

    @Volatile
    private var onRefresh: () -> MockResponse = { refreshOk("""{"accessToken":"new-access","refreshToken":"refresh-2"}""") }

    @Volatile
    private var failLogoutOf: String? = null

    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == REFRESH -> {
                    refreshCount.incrementAndGet()
                    onRefresh()
                }
                request.getHeader("Authorization")?.removePrefix("Bearer ") !in acceptedAccess ->
                    MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                        .setBody("""{"id":"00000000-0000-0000-0000-000000000000","code":"UNAUTHORIZED","message":"error"}""")
                request.path == LOGOUT -> {
                    val refresh = Regex("\"refreshToken\":\"([^\"]*)\"").find(request.body.readUtf8())!!.groupValues[1]
                    if (refresh == failLogoutOf) {
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    } else {
                        loggedOut += refresh
                        MockResponse().setHeader("Content-Type", "application/json").setBody("""{"message":"ok"}""")
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java))
            .apply { baseUrl = "http://127.0.0.1:${server.port}/" }
        val plainClient = OkHttpClient()
        val authClient = NetworkModule.provideAuthOkHttpClient(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE },
            AuthInterceptor(storage),
            TokenAuthenticator(storage, baseUrlProvider, plainClient)
        )
        repository = AuthRepository(
            storage,
            baseUrlProvider,
            ApiServiceFactory(baseUrlProvider, plainClient, authClient, plainClient)
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun logoutWithValidAccessRevokesCurrentRefreshOnly() = runBlocking {
        acceptedAccess = setOf(OLD.accessToken)

        assertTrue(repository.logout().isSuccess)

        assertEquals(listOf("refresh-1"), loggedOut)
        assertEquals(0, refreshCount.get())
        assertNull(storage.getTokens())
    }

    @Test
    fun logoutWithExpiredAccessInReplacementWindowRevokesR1AndR2() = runBlocking {
        assertTrue(repository.logout().isSuccess)

        // R1 из тела повторённого запроса отозван заменой, R2 отзывается отдельным logout.
        assertEquals(listOf("refresh-1", "refresh-2"), loggedOut)
        assertEquals(1, refreshCount.get())
        assertNull(storage.getTokens())
    }

    @Test
    fun logoutWithExpiredAccessWithoutReplacementSendsOneLogout() = runBlocking {
        onRefresh = { refreshOk("""{"accessToken":"new-access"}""") }

        assertTrue(repository.logout().isSuccess)

        assertEquals(listOf("refresh-1"), loggedOut)
        assertNull(storage.getTokens())
    }

    @Test
    fun failedLogoutOfR2KeepsPairForRetry() = runBlocking {
        failLogoutOf = "refresh-2"

        assertTrue(repository.logout().isFailure)
        // Пара с R2 остаётся, чтобы повторный Logout отозвал её.
        assertEquals(Tokens("new-access", "refresh-2"), storage.getTokens())

        failLogoutOf = null
        assertTrue(repository.logout().isSuccess)

        assertEquals(listOf("refresh-1", "refresh-2"), loggedOut)
        assertNull(storage.getTokens())
    }

    private fun refreshOk(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}

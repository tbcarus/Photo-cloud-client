package ru.tbcarus.photo_cloud_client.core.network

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MultipartBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.api.models.AuthRequest
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.di.NetworkModule
import ru.tbcarus.photo_cloud_client.media.ContentUriRequestBody
import ru.tbcarus.photo_cloud_client.media.MediaStreamOpener
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Клиенты OkHttp собираются боевыми провайдерами NetworkModule с уровнем логирования debug-сборки;
 * логгер лишь перехватывает строки, чтобы проверить, что в них нет тел и токенов.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HttpLoggingUploadTest {

    private companion object {
        const val ACCESS = "access-jwt-secret"
        const val REFRESH = "refresh-jwt-secret"
        const val PASSWORD = "password-secret"
    }

    // Текстовые байты: BODY-логирование записало бы их в лог как есть.
    private val fileBytes = ("PHOTO-CONTENT-" + "x".repeat(4096)).toByteArray()
    private val logs = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    private lateinit var context: Application
    private lateinit var apiServiceFactory: ApiServiceFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val storage = FixedTokenStorage(Tokens(ACCESS, REFRESH))
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java))
            .apply { baseUrl = "http://127.0.0.1:${server.port}/" }
        val logging = HttpLoggingInterceptor { logs += it }
            .apply { level = NetworkModule.httpLoggingLevel(debug = true) }
        val plainClient = NetworkModule.providePlainOkHttpClient(logging)
        val authClient = NetworkModule.provideAuthOkHttpClient(
            logging,
            AuthInterceptor(storage),
            TokenAuthenticator(storage, baseUrlProvider, plainClient)
        )
        apiServiceFactory = ApiServiceFactory(
            baseUrlProvider, plainClient, authClient, NetworkModule.provideRegisterOkHttpClient(plainClient)
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun multipartUploadSendsOriginalBytesOnceWithoutLoggingBodyOrToken() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val openCount = AtomicInteger()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            openCount.incrementAndGet()
            ByteArrayInputStream(fileBytes)
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":7}"""))
        val part = MultipartBody.Part.createFormData(
            "file", "photo.jpg", ContentUriRequestBody(MediaStreamOpener(context), uri, "image/jpeg", fileBytes.size.toLong())
        )

        val response = apiServiceFactory.authFileService().uploadFile(part, null).execute()

        assertEquals(7L, response.body()?.id)
        val recorded = server.takeRequest()
        assertEquals("Bearer $ACCESS", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readByteArray().containsSequence(fileBytes))
        // Поток открыт ровно один раз — для отправки; логирование тело не читало и не буферизовало.
        assertEquals(1, openCount.get())
        assertTrue(logs.any { it.startsWith("--> POST") })
        assertTrue(logs.any { it.startsWith("<-- 200") })
        assertTrue(logs.none { it.contains("PHOTO-CONTENT") || it.contains(ACCESS) || it.contains("Authorization") })
    }

    @Test
    fun loginLogsNeitherPasswordNorTokens() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"accessToken":"$ACCESS","refreshToken":"$REFRESH"}""")
        )

        val response = apiServiceFactory.plainAuthService().login(AuthRequest("user@test.local", PASSWORD)).execute()

        assertTrue(response.isSuccessful)
        assertTrue(logs.any { it.startsWith("--> POST") })
        assertTrue(logs.none { it.contains(PASSWORD) || it.contains(ACCESS) || it.contains(REFRESH) })
    }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean =
        (0..size - sequence.size).any { start -> sequence.indices.all { this[start + it] == sequence[it] } }

    private class FixedTokenStorage(private val tokens: Tokens) : TokenStorage {
        override val tokensFlow: Flow<Tokens?> = MutableStateFlow(tokens)
        override fun getTokens(): Tokens = tokens
        override fun saveTokens(tokens: Tokens) = Unit
        override fun clear() = Unit
        override fun replaceIf(expected: Tokens, replacement: Tokens?) = false
    }
}

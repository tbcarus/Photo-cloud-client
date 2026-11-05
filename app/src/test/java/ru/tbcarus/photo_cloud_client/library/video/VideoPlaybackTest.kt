package ru.tbcarus.photo_cloud_client.library.video

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.core.network.AuthInterceptor
import ru.tbcarus.photo_cloud_client.core.network.TokenAuthenticator
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.di.NetworkModule
import java.net.InetAddress

/**
 * Сетевая часть плеера: настоящий OkHttpDataSource Media3 поверх авторизованного клиента приложения.
 * ExoPlayer здесь не запускается: декодирование и воспроизведение проверяются на устройстве.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VideoPlaybackTest {

    private val server = MockWebServer()
    private val storage = InMemoryTokenStorage(Tokens("access-1", "refresh-1"))
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply { baseUrl = server.url("/").toString() }
        client = NetworkModule.provideAuthOkHttpClient(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE },
            AuthInterceptor(storage),
            TokenAuthenticator(storage, baseUrlProvider, OkHttpClient())
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun dataSource() = videoDataSourceFactory(client).createDataSource()

    private fun url(id: Long) = videoUrl(server.url("/").toString(), id)

    @Test
    fun onlyServerVideoMimeOpensPlayerAndUrlHasNoToken() {
        assertTrue(isPlayableVideo(FileItemDto(id = 1, mimeType = "video/mp4", originalFilename = "clip.bin")))
        assertTrue(isPlayableVideo(FileItemDto(id = 2, mimeType = "video/quicktime")))
        assertFalse(isPlayableVideo(FileItemDto(id = 3, mimeType = "image/jpeg", originalFilename = "clip.mp4")))
        assertFalse(isPlayableVideo(FileItemDto(id = 4, mimeType = "application/pdf")))
        assertFalse(isPlayableVideo(FileItemDto(id = null, mimeType = "video/mp4")))
        assertEquals("http://10.0.2.2:8080/api/v1/files/7/download", videoUrl("http://10.0.2.2:8080/", 7))
    }

    @Test
    fun streamIsAuthorizedAndSeekUsesRange() {
        val bytes = ByteArray(5_000) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "video/mp4"))
        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4000-4999/5000")
                .setBody(Buffer().write(bytes.copyOfRange(4000, 5000)))
        )

        val start = dataSource()
        start.open(DataSpec(Uri.parse(url(7))))
        start.read(ByteArray(16), 0, 16)
        start.close()
        // Перемотка: Media3 открывает тот же адрес с позиции, Server отвечает частью файла.
        val seek = dataSource()
        val remaining = seek.open(DataSpec.Builder().setUri(Uri.parse(url(7))).setPosition(4000).build())
        val chunk = ByteArray(4)
        seek.read(chunk, 0, chunk.size)
        seek.close()

        val first = server.takeRequest()
        assertEquals("/api/v1/files/7/download", first.path)
        assertEquals("Bearer access-1", first.getHeader("Authorization"))
        val ranged = server.takeRequest()
        assertEquals("bytes=4000-", ranged.getHeader("Range"))
        assertEquals("Bearer access-1", ranged.getHeader("Authorization"))
        assertEquals(1000L, remaining)
        assertEquals(bytes.copyOfRange(4000, 4004).toList(), chunk.toList())
    }

    @Test
    fun missingFileAndLostConnectionHaveReadableMessages() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"FILE_ITEM_NOT_FOUND"}"""))
        assertEquals("Файл недоступен на сервере", openError(url(9)))

        // Порт без сервера: соединение отклоняется.
        val offline = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val offlineUrl = videoUrl(offline.url("/").toString(), 9)
        offline.shutdown()
        assertEquals("Нет связи с сервером", openError(offlineUrl))
    }

    /** Ошибка открытия источника в том виде, в каком её получил бы плеер. */
    private fun openError(url: String): String {
        try {
            dataSource().open(DataSpec(Uri.parse(url)))
        } catch (e: HttpDataSource.HttpDataSourceException) {
            return videoErrorText(ExoPlaybackException.createForSource(e, e.reason))
        }
        fail("open must fail")
        error("unreachable")
    }
}

package ru.tbcarus.photo_cloud_client.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.network.AuthInterceptor
import ru.tbcarus.photo_cloud_client.core.network.TokenAuthenticator
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Запросы ручной передачи через настоящий HTTP-слой: авторизация, refresh, multipart и потоковое скачивание. */
class TransferRemoteTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var remote: TransferRemote

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val storage = InMemoryTokenStorage(Tokens("access", "refresh"))
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply {
            baseUrl = server.url("/").toString()
        }
        val plain = OkHttpClient()
        val auth = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(storage))
            .authenticator(TokenAuthenticator(storage, baseUrlProvider, plain))
            .build()
        remote = TransferRemote(ApiServiceFactory(baseUrlProvider, plain, auth, plain))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun downloadStreamsBodyAndCountsChecksum() = runBlocking {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val target = temporary.newFile()
        var progress = 0L

        val result = remote.download(42, target) { progress = it }

        val downloaded = (result as RemoteResult.Success).value
        assertEquals(bytes.size.toLong(), downloaded.size)
        assertEquals(sha256(bytes), downloaded.sha256)
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(bytes.size.toLong(), progress)
        val request = server.takeRequest()
        assertEquals("/api/v1/files/42/download", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
    }

    @Test
    fun missingFileIsHttpError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"FILE_ITEM_NOT_FOUND","message":"File not found"}"""))

        val result = remote.download(42, temporary.newFile()) {}

        assertEquals(RemoteResult.HttpError(404, "File not found"), result)
    }

    @Test
    fun cancelStopsStalledDownload() = runBlocking {
        // Тело приходит по 1 КиБ в 2 с: между порциями чтение заблокировано, отмена должна прервать его сразу.
        server.enqueue(
            MockResponse().setBody(Buffer().write(ByteArray(8 * 1024)))
                .throttleBody(1024, 2, TimeUnit.SECONDS)
        )
        val job = launch(Dispatchers.IO) { remote.download(42, temporary.newFile()) {} }
        Thread.sleep(500)

        withTimeout(1_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled)
    }

    @Test
    fun uploadSendsOriginalNameAndFolder() = runBlocking {
        server.enqueue(json("""{"id":7,"folderId":5,"originalFilename":"Чертёж.pdf"}"""))
        val body = UploadRequestBody({ ByteArrayInputStream("pdf-bytes".toByteArray()) }, "application/pdf", 1_000) {}

        val result = remote.upload(5, "Чертёж.pdf", body)

        assertEquals(7L, (result as RemoteResult.Success).value.id)
        val request = server.takeRequest()
        assertEquals("/api/v1/files/upload", request.path)
        val multipart = request.body.readUtf8()
        assertTrue(multipart.contains("filename=\"Чертёж.pdf\""))
        assertTrue(multipart.contains("pdf-bytes"))
        assertTrue(multipart.contains("name=\"folderId\""))
    }

    @Test
    fun uploadBodyIsResentWithReopenedSourceAfterRefresh() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(json("""{"accessToken":"access2"}"""))
        server.enqueue(json("""{"id":7}"""))
        var opens = 0
        val body = UploadRequestBody({ opens++; ByteArrayInputStream("content".toByteArray()) }, null, 1_000) {}

        val result = remote.upload(5, "a.txt", body)

        assertTrue(result is RemoteResult.Success)
        assertEquals(2, opens)
        assertTrue(server.takeRequest().body.readUtf8().contains("content"))
        server.takeRequest()
        val retried = server.takeRequest()
        assertEquals("Bearer access2", retried.getHeader("Authorization"))
        assertTrue(retried.body.readUtf8().contains("content"))
    }

    @Test
    fun oversizedSourceStopsBeforeServerLimit() = runBlocking {
        server.enqueue(json("""{"id":7}"""))
        val body = UploadRequestBody({ ByteArrayInputStream(ByteArray(2_000)) }, null, 1_000) {}

        val result = remote.upload(5, "big.bin", body)

        assertTrue((result as RemoteResult.NetworkError).cause is UploadTooLargeException)
    }

    @Test
    fun unreadableSourceIsLocalError() = runBlocking {
        server.enqueue(json("""{"id":7}"""))
        val body = UploadRequestBody({ throw SecurityException("Permission denied") }, null, 1_000) {}

        val result = remote.upload(5, "a.txt", body)

        assertTrue((result as RemoteResult.NetworkError).cause is LocalSourceException)
    }

    @Test
    fun createFolderPostsParentAndName() = runBlocking {
        server.enqueue(json("""{"id":9,"parentId":5,"name":"Проекты","folderType":"USER"}"""))

        val result = remote.createFolder(5, "Проекты")

        assertEquals("Проекты", (result as RemoteResult.Success).value.name)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/folders", request.path)
        assertEquals("""{"parentId":5,"name":"Проекты"}""", request.body.readUtf8())
    }

    @Test
    fun conflictKeepsStatusAndMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"CONFLICT","message":"File with this name already exists in folder"}"""))
        val body = UploadRequestBody({ ByteArrayInputStream("x".toByteArray()) }, null, 1_000) {}

        val result = remote.upload(5, "a.txt", body)

        assertEquals(RemoteResult.HttpError(409, "File with this name already exists in folder"), result)
    }

    @Test
    fun filesAreListedInLargePagesWithoutSort() = runBlocking {
        server.enqueue(json("""{"items":[],"hasNext":false}"""))

        remote.files(5, 2)

        assertEquals("/api/v1/files?folderId=5&page=2&size=${TransferRemote.FILES_PAGE_SIZE}", server.takeRequest().path)
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}

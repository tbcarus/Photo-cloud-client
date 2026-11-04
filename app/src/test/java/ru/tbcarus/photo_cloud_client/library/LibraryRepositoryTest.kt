package ru.tbcarus.photo_cloud_client.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.network.AuthInterceptor
import ru.tbcarus.photo_cloud_client.core.network.TokenAuthenticator
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Запросы к настоящему HTTP-слою: авторизованный клиент, refresh и разбор ответов Server. */
class LibraryRepositoryTest {

    private val root = FolderDto(1, null, "root", "ROOT")
    private val camera = FolderDto(2, 1, "Camera", "CAMERA")
    private val userFolder = FolderDto(3, 1, "Trips", "USER")

    private lateinit var server: MockWebServer
    private lateinit var storage: InMemoryTokenStorage
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        storage = InMemoryTokenStorage(Tokens("access", "refresh"))
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply {
            baseUrl = server.url("/").toString()
        }
        val plain = OkHttpClient()
        val auth = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(storage))
            .authenticator(TokenAuthenticator(storage, baseUrlProvider, plain))
            .build()
        repository = LibraryRepository(ApiServiceFactory(baseUrlProvider, plain, auth, plain))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun rootIsRequestedWithAccessToken() = runBlocking {
        server.enqueue(json("""{"id":1,"parentId":null,"name":"root","folderType":"ROOT"}"""))

        val result = repository.getRoot()

        assertEquals(LibraryResult.Success(root), result)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/folders/root", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
    }

    @Test
    fun childrenAreRequestedForFolder() = runBlocking {
        server.enqueue(json("""[{"id":2,"parentId":1,"name":"Camera","folderType":"CAMERA"}]"""))

        val result = repository.getChildren(1)

        assertEquals(LibraryResult.Success(listOf(camera)), result)
        assertEquals("/api/v1/folders/1/children", server.takeRequest().path)
    }

    @Test
    fun filesOfRegularFoldersAreSortedByName() = runBlocking {
        repeat(2) { server.enqueue(json(filePage(hasNext = false))) }

        repository.getFiles(root, 0)
        repository.getFiles(userFolder, 3)

        val rootRequest = server.takeRequest().requestUrl!!
        assertEquals("/api/v1/files", rootRequest.encodedPath)
        assertEquals("1", rootRequest.queryParameter("folderId"))
        assertEquals("0", rootRequest.queryParameter("page"))
        assertEquals("50", rootRequest.queryParameter("size"))
        assertEquals("name", rootRequest.queryParameter("sort"))

        val userRequest = server.takeRequest().requestUrl!!
        assertEquals("3", userRequest.queryParameter("folderId"))
        assertEquals("3", userRequest.queryParameter("page"))
        assertEquals("name", userRequest.queryParameter("sort"))
    }

    @Test
    fun cameraFilesKeepServerDateOrder() = runBlocking {
        server.enqueue(json(filePage(hasNext = false)))

        repository.getFiles(camera, 0)

        val url = server.takeRequest().requestUrl!!
        assertEquals("2", url.queryParameter("folderId"))
        assertFalse("sort" in url.queryParameterNames)
    }

    @Test
    fun filePageCarriesItemsAndHasNext() = runBlocking {
        server.enqueue(json(filePage(hasNext = true)))

        val page = (repository.getFiles(root, 0) as LibraryResult.Success).value

        assertTrue(page.hasNext)
        assertEquals(1, page.items?.size)
        val file = page.items!!.single()
        assertEquals(42L, file.id)
        assertEquals("Чертёж.pdf", file.originalFilename)
        assertEquals(870400L, file.size)
        assertEquals("DOCUMENT", file.fileType)
        assertEquals("2026-05-17T10:16:00.123", file.uploadedAt)
    }

    @Test
    fun browsingCameraOnlyReads() = runBlocking {
        server.enqueue(json("""{"id":1,"parentId":null,"name":"root","folderType":"ROOT"}"""))
        server.enqueue(json("""[{"id":2,"parentId":1,"name":"Camera","folderType":"CAMERA"}]"""))
        server.enqueue(json("[]"))
        server.enqueue(json(filePage(hasNext = false)))

        repository.getRoot()
        repository.getChildren(root.id)
        repository.getChildren(camera.id)
        repository.getFiles(camera, 0)

        val requests = List(server.requestCount) { server.takeRequest() }
        assertTrue(requests.all { it.method == "GET" })
        assertTrue(requests.none { it.path!!.endsWith("/camera") })
    }

    @Test
    fun expiredAccessIsRefreshedAndRequestRepeated() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(json("""{"accessToken":"access2","refreshToken":"refresh2"}"""))
        server.enqueue(json("""{"id":1,"parentId":null,"name":"root","folderType":"ROOT"}"""))

        val result = repository.getRoot()

        assertEquals(LibraryResult.Success(root), result)
        server.takeRequest()
        assertEquals("/api/v1/auth/refresh-token", server.takeRequest().path)
        assertEquals("Bearer access2", server.takeRequest().getHeader("Authorization"))
        assertEquals(Tokens("access2", "refresh2"), storage.getTokens())
    }

    @Test
    fun rejectedRefreshIsAuthorizationFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            json("""{"code":"INVALID_REFRESH_TOKEN","message":"Invalid refresh token"}""").setResponseCode(401)
        )

        val result = repository.getRoot()

        assertEquals(LibraryResult.Unauthorized, result)
        assertNull(storage.getTokens())
    }

    @Test
    fun forbiddenIsAuthorizationFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(LibraryResult.Unauthorized, repository.getChildren(1))
    }

    @Test
    fun missingFolderIsNotFound() = runBlocking {
        server.enqueue(json("""{"code":"NOT_FOUND","message":"Folder not found"}""").setResponseCode(404))

        assertEquals(LibraryResult.NotFound, repository.getChildren(7))
    }

    @Test
    fun serverErrorCarriesParsedMessage() = runBlocking {
        server.enqueue(json("""{"code":"INTERNAL_ERROR","message":"Storage failed"}""").setResponseCode(500))

        assertEquals(LibraryResult.ServerError("Storage failed"), repository.getFiles(root, 0))
    }

    @Test
    fun unreadableBodyIsServerError() = runBlocking {
        server.enqueue(json("<html>"))

        assertTrue(repository.getFiles(root, 0) is LibraryResult.ServerError)
    }

    @Test
    fun unreachableServerIsNetworkError() = runBlocking {
        server.shutdown()

        assertTrue(repository.getRoot() is LibraryResult.NetworkError)
    }

    @Test
    fun cancellationStopsRequestWithoutFailureResult() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        var result: LibraryResult<FolderDto>? = null

        val job = launch(Dispatchers.Default) { result = repository.getRoot() }
        server.takeRequest(5, TimeUnit.SECONDS)
        withTimeout(5_000) { job.cancelAndJoin() }

        assertNull(result)
    }

    @Test
    fun createFolderPostsParentAndNameWithoutBackupSource() = runBlocking {
        server.enqueue(json("""{"id":9,"parentId":3,"name":"Новая папка","folderType":"USER"}"""))

        val result = repository.createFolder(3, "Новая папка")

        assertEquals(LibraryResult.Success(FolderDto(9, 3, "Новая папка", "USER")), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/folders", request.path)
        assertEquals("""{"parentId":3,"name":"Новая папка"}""", request.body.readUtf8())
    }

    @Test
    fun renameFolderKeepsIdAndSendsOnlyName() = runBlocking {
        server.enqueue(json("""{"id":3,"parentId":1,"name":"Поездки","folderType":"USER"}"""))

        val result = repository.renameFolder(3, "Поездки")

        assertEquals(LibraryResult.Success(FolderDto(3, 1, "Поездки", "USER")), result)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/folders/3", request.path)
        assertEquals("""{"name":"Поездки"}""", request.body.readUtf8())
    }

    @Test
    fun renameFileSendsFullNameWithoutBackupSource() = runBlocking {
        server.enqueue(json("""{"id":42,"folderId":3,"originalFilename":"План.PDF"}"""))

        val result = repository.renameFile(42, "План.PDF")

        assertEquals(42L, (result as LibraryResult.Success).value.id)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/files/42", request.path)
        assertEquals("""{"originalName":"План.PDF"}""", request.body.readUtf8())
    }

    @Test
    fun changeRefusalsAreDistinguishedByStatusAndCode() = runBlocking {
        server.enqueue(json("""{"code":"CONFLICT","message":"Folder with this name already exists"}""").setResponseCode(409))
        server.enqueue(json("""{"code":"FOLDER_MANAGED","message":"Folder belongs to an active backup source"}""").setResponseCode(409))
        server.enqueue(json("""{"code":"BAD_REQUEST","message":"Cannot rename system folder"}""").setResponseCode(400))
        server.enqueue(json("""{"code":"NOT_FOUND","message":"Folder not found"}""").setResponseCode(404))
        server.enqueue(json("""{"code":"INTERNAL_ERROR","message":"Physical storage operation failed"}""").setResponseCode(500))

        assertEquals(LibraryResult.NameTaken, repository.createFolder(1, "Работа"))
        assertEquals(LibraryResult.Managed, repository.renameFolder(6, "Проекты"))
        assertEquals(LibraryResult.Rejected("Cannot rename system folder"), repository.renameFolder(2, "Фото"))
        assertEquals(LibraryResult.NotFound, repository.renameFile(42, "a.txt"))
        assertEquals(LibraryResult.ServerError("Physical storage operation failed"), repository.renameFile(42, "a.txt"))
    }

    @Test
    fun readingKeepsConflictAsServerError() = runBlocking {
        server.enqueue(json("""{"code":"CONFLICT","message":"Conflict"}""").setResponseCode(409))

        assertEquals(LibraryResult.ServerError("Conflict"), repository.getChildren(1))
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun filePage(hasNext: Boolean) = """
        {"items":[{"id":42,"folderId":1,"originalFilename":"Чертёж.pdf","mimeType":"application/pdf",
        "size":870400,"checksum":"aa","fileType":"DOCUMENT","capturedAt":"2026-05-17T10:15:30",
        "uploadedAt":"2026-05-17T10:16:00.123","deletedAt":null,"metadata":null}],
        "page":0,"size":50,"totalElements":1,"totalPages":1,"hasNext":$hasNext,"hasPrevious":false}
    """.trimIndent()
}

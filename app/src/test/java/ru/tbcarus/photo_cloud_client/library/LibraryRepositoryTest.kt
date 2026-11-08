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
import ru.tbcarus.photo_cloud_client.api.models.GrantShareRequest
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
    fun moveFileSendsOnlyTargetFolder() = runBlocking {
        server.enqueue(json("""{"id":42,"folderId":5,"originalFilename":"План.pdf"}"""))

        val result = repository.moveFile(42, 5)

        assertEquals(42L, (result as LibraryResult.Success).value.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/files/42/move", request.path)
        assertEquals("""{"targetFolderId":5}""", request.body.readUtf8())
    }

    @Test
    fun copyFileSendsTargetAndCopyName() = runBlocking {
        server.enqueue(json("""{"id":77,"folderId":3,"originalFilename":"План (черновик).pdf"}"""))

        val result = repository.copyFile(42, 3, "План (черновик).pdf")

        assertEquals(77L, (result as LibraryResult.Success).value.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/files/42/copy", request.path)
        assertEquals("""{"targetFolderId":3,"originalName":"План (черновик).pdf"}""", request.body.readUtf8())
    }

    @Test
    fun moveFolderSendsTargetParent() = runBlocking {
        server.enqueue(json("""{"id":3,"parentId":1,"name":"Trips","folderType":"USER"}"""))

        val result = repository.moveFolder(3, 1)

        assertEquals(LibraryResult.Success(FolderDto(3, 1, "Trips", "USER")), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/folders/3/move", request.path)
        assertEquals("""{"targetParentId":1}""", request.body.readUtf8())
    }

    @Test
    fun relocationRefusalsAreDistinguished() = runBlocking {
        server.enqueue(json("""{"code":"BAD_REQUEST","message":"Cannot move folder into its descendant"}""").setResponseCode(400))
        server.enqueue(json("""{"code":"CONFLICT","message":"File with this name already exists in folder"}""").setResponseCode(409))
        server.enqueue(json("""{"code":"FOLDER_MANAGED","message":"Folder belongs to an active backup source"}""").setResponseCode(409))
        server.enqueue(json("""{"code":"FILE_ITEM_NOT_FOUND","message":"File not found"}""").setResponseCode(404))

        assertEquals(LibraryResult.Rejected("Cannot move folder into its descendant"), repository.moveFolder(3, 5))
        assertEquals(LibraryResult.NameTaken, repository.copyFile(42, 3, "a.txt"))
        assertEquals(LibraryResult.Managed, repository.moveFile(42, 6))
        assertEquals(LibraryResult.NotFound, repository.moveFile(42, 6))
    }

    @Test
    fun unreadableSuccessOfRelocationIsUnknownResult() = runBlocking {
        // Server принял операцию (2xx), но тело не разбирается: результат неизвестен, а не ошибка.
        server.enqueue(json("<html>"))
        server.enqueue(json("""[{"id":77}]"""))
        server.enqueue(json(""""moved""""))

        assertTrue(repository.moveFile(42, 5) is LibraryResult.UnknownResult)
        assertTrue(repository.copyFile(42, 5, "a.txt") is LibraryResult.UnknownResult)
        assertTrue(repository.moveFolder(3, 1) is LibraryResult.UnknownResult)
        // Каждый вызов — ровно один запрос.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun relocationHttpErrorsAreUnchangedAndOtherChangesKeepServerError() = runBlocking {
        server.enqueue(json("""{"code":"INTERNAL_ERROR","message":"Physical storage operation failed"}""").setResponseCode(500))
        server.enqueue(json("<html>"))
        server.enqueue(json("<html>"))

        assertEquals(LibraryResult.ServerError("Physical storage operation failed"), repository.copyFile(42, 5, "a.txt"))
        // Создание и переименование (6.1) и чтение разбирают тело по-прежнему.
        assertTrue(repository.renameFolder(3, "X") is LibraryResult.ServerError)
        assertTrue(repository.getChildren(1) is LibraryResult.ServerError)
    }

    @Test
    fun deleteSucceedsWithNoContent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(LibraryResult.Success(Unit), repository.deleteFile(42))
        assertEquals(LibraryResult.Success(Unit), repository.deleteFolder(3))
        val file = server.takeRequest()
        assertEquals("DELETE", file.method)
        assertEquals("/api/v1/files/42", file.path)
        val folder = server.takeRequest()
        assertEquals("DELETE", folder.method)
        assertEquals("/api/v1/folders/3", folder.path)
    }

    @Test
    fun deleteRefusalsKeepServerReason() = runBlocking {
        server.enqueue(json("""{"code":"BAD_REQUEST","message":"Cannot delete system folder"}""").setResponseCode(400))
        server.enqueue(json("""{"code":"FOLDER_MANAGED","message":"Folder belongs to an active backup source"}""").setResponseCode(409))
        server.enqueue(json("""{"code":"CONFLICT","message":"Object is busy or not accessible, try again later"}""").setResponseCode(409))
        server.enqueue(json("""{"code":"FILE_ITEM_NOT_FOUND","message":"File not found"}""").setResponseCode(404))
        server.enqueue(json("""{"code":"INTERNAL_ERROR","message":"Physical storage operation failed"}""").setResponseCode(500))

        assertEquals(LibraryResult.Rejected("Cannot delete system folder"), repository.deleteFolder(2))
        assertEquals(LibraryResult.Managed, repository.deleteFolder(6))
        // Занятый объект — не конфликт имени: показывается причина Server.
        assertEquals(LibraryResult.Rejected("Object is busy or not accessible, try again later"), repository.deleteFile(42))
        assertEquals(LibraryResult.NotFound, repository.deleteFile(42))
        assertEquals(LibraryResult.ServerError("Physical storage operation failed"), repository.deleteFile(42))
    }

    @Test
    fun readingKeepsConflictAsServerError() = runBlocking {
        server.enqueue(json("""{"code":"CONFLICT","message":"Conflict"}""").setResponseCode(409))

        assertEquals(LibraryResult.ServerError("Conflict"), repository.getChildren(1))
    }

    @Test
    fun shareRequestsSendServerContractIncludingExplicitNullExpiry() = runBlocking {
        val shareJson = """{"id":5,"resourceType":"FILE","resourceId":42,"resourceName":"a.txt",
            "recipient":{"id":3,"email":"a@test","displayName":null},"expiresAt":null,"status":"ACTIVE"}"""
        server.enqueue(json("[$shareJson]"))
        server.enqueue(json(shareJson))
        server.enqueue(json(shareJson.replace("\"expiresAt\":null", "\"expiresAt\":\"2031-01-01T00:00:00Z\"")))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(json("""{"code":"SHARE_RECIPIENT_UNAVAILABLE","message":"Some recipients cannot receive access",
            "fieldErrors":{"x@test":"Recipient is not available"}}""").setResponseCode(400))

        repository.grantShare(GrantShareRequest(fileId = 42, emails = listOf("a@test", "b@test")))
        // Бессрочный доступ: поле присутствует со значением null, общий Gson его бы опустил.
        assertEquals(null, (repository.updateShareExpiry(5, null) as LibraryResult.Success).value.expiresAt)
        repository.updateShareExpiry(5, "2031-01-01T03:00:00+03:00")
        repository.revokeAllShares(fileId = null, folderId = 7)
        val rejected = repository.grantShare(GrantShareRequest(folderId = 7, emails = listOf("x@test")))

        val grant = server.takeRequest()
        assertEquals("POST", grant.method)
        assertEquals("/api/v1/shares/users", grant.path)
        // Gson не пишет null-поля: отсутствующие folderId и expiresAt Server понимает как файл и бессрочный срок.
        assertEquals("""{"fileId":42,"emails":["a@test","b@test"]}""", grant.body.readUtf8())
        val toNever = server.takeRequest()
        assertEquals("PATCH", toNever.method)
        assertEquals("/api/v1/shares/5", toNever.path)
        assertEquals("""{"expiresAt":null}""", toNever.body.readUtf8())
        assertEquals("""{"expiresAt":"2031-01-01T03:00:00+03:00"}""", server.takeRequest().body.readUtf8())
        val revokeAll = server.takeRequest()
        assertEquals("DELETE", revokeAll.method)
        assertEquals("/api/v1/shares/users?folderId=7", revokeAll.path)
        assertEquals(LibraryResult.RecipientsRejected(mapOf("x@test" to "Recipient is not available")), rejected)
    }

    @Test
    fun linkRequestsSendServerContractAndMapUnavailableSharing() = runBlocking {
        val linkJson = """{"id":9,"resourceType":"FOLDER","resourceId":7,"resourceName":"Trips",
            "url":"https://cloud.example.com/s/abc","expiresAt":null,"status":"ACTIVE","createdAt":"2026-11-08T10:15:30Z"}"""
        server.enqueue(json(linkJson))
        server.enqueue(json(linkJson.replace("\"id\":9", "\"id\":10")))
        server.enqueue(json("""{"items":[$linkJson],"page":0,"hasNext":true}"""))
        server.enqueue(json(linkJson))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(json("""{"code":"LINK_SHARING_UNAVAILABLE","message":"Link sharing is not configured"}""").setResponseCode(503))
        server.enqueue(json("""{"code":"LINK_SHARING_UNAVAILABLE","message":"Link sharing is not configured"}""").setResponseCode(503))

        val first = (repository.createLink(fileId = null, folderId = 7, expiresAt = null) as LibraryResult.Success).value
        val second = (repository.createLink(fileId = null, folderId = 7, expiresAt = "2031-01-01T03:00:00+03:00") as LibraryResult.Success).value
        val page = (repository.getLinks(fileId = null, folderId = 7, page = 0) as LibraryResult.Success).value
        repository.updateLinkExpiry(9, null)
        repository.revokeLink(9)
        repository.revokeAllLinks(fileId = 42, folderId = null)
        val unavailable = repository.createLink(fileId = 42, folderId = null, expiresAt = null)
        val unavailableList = repository.getLinks(fileId = null, folderId = null, page = 0)

        assertEquals("https://cloud.example.com/s/abc", first.shareableUrl)
        assertEquals(listOf(9L, 10L), listOf(first.id, second.id))
        assertEquals(listOf(9L), page.items!!.map { it.id })
        assertTrue(page.hasNext)
        // Каждое создание — отдельный POST: существующая ссылка не переиспользуется.
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/v1/shares/links", create.path)
        assertEquals("""{"folderId":7}""", create.body.readUtf8())
        assertEquals("""{"folderId":7,"expiresAt":"2031-01-01T03:00:00+03:00"}""", server.takeRequest().body.readUtf8())
        assertEquals("/api/v1/shares/links?folderId=7&page=0&size=50", server.takeRequest().path)
        val toNever = server.takeRequest()
        assertEquals("PATCH", toNever.method)
        assertEquals("/api/v1/shares/links/9", toNever.path)
        assertEquals("""{"expiresAt":null}""", toNever.body.readUtf8())
        val revoke = server.takeRequest()
        assertEquals("DELETE", revoke.method)
        assertEquals("/api/v1/shares/links/9", revoke.path)
        val revokeAll = server.takeRequest()
        assertEquals("DELETE", revokeAll.method)
        assertEquals("/api/v1/shares/links?fileId=42", revokeAll.path)
        // Ссылки не настроены на Server: отдельный результат и при создании, и при чтении.
        assertEquals(LibraryResult.LinksUnavailable, unavailable)
        assertEquals(LibraryResult.LinksUnavailable, unavailableList)
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun filePage(hasNext: Boolean) = """
        {"items":[{"id":42,"folderId":1,"originalFilename":"Чертёж.pdf","mimeType":"application/pdf",
        "size":870400,"checksum":"aa","fileType":"DOCUMENT","capturedAt":"2026-05-17T10:15:30",
        "uploadedAt":"2026-05-17T10:16:00.123","deletedAt":null,"metadata":null}],
        "page":0,"size":50,"totalElements":1,"totalPages":1,"hasNext":$hasNext,"hasPrevious":false}
    """.trimIndent()
}

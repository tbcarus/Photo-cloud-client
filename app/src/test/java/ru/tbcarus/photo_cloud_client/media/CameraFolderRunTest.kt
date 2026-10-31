package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.server.ServerRepository
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.row
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.sha256
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Получение Camera до отправки файлов (этап 1a.1). Тестовый Server повторяет контракт настоящего:
 * POST /folders/camera создаёт папку, файл с явным ID принимается независимо от типа содержимого,
 * нераспознанный файл без папки получает 400, неизвестный folderId — 404.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CameraFolderRunTest {

    private companion object {
        const val CAMERA_ID = 2L
        const val NEW_CAMERA_ID = 7L
        const val UNDETECTED = 2L
    }

    private data class Upload(val id: Long, val folderId: Long?)

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private lateinit var server: MockWebServer
    private lateinit var preferences: ServerPreferences
    private val syncStatusStore = testSyncStatusStore()
    private val dao get() = db.mediaFileDao()

    // Поведение тестового Server.
    @Volatile private var cameraId: Long? = null
    @Volatile private var cameraResponse: () -> MockResponse? = { null }
    @Volatile private var onPrecheck: () -> Unit = {}
    @Volatile private var onUpload: () -> Unit = {}
    @Volatile private var existingChecksums: Set<String> = emptySet()
    @Volatile private var rejectedUploads: Set<Long> = emptySet()
    private val paths = CopyOnWriteArrayList<String>()
    private val uploads = CopyOnWriteArrayList<Upload>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        media = FakeMediaStore.install(context)
        shadowOf(context).grantPermissions(mediaReadPermission)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        preferences = ServerPreferences(context)
        runBlocking { preferences.saveConnection("127.0.0.1", server.port.toString()) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun firstRunGetsCameraBeforeAnyFileAndSendsEveryFileWithFolderId() = runBlocking {
        (1L..3L).forEach { ready(it) }

        val result = runUploader()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            listOf(
                "/api/v1/test", "/api/v1/folders/camera", "/api/v1/files/checksums/exists",
                "/api/v1/files/upload", "/api/v1/files/upload", "/api/v1/files/upload"
            ),
            paths.toList()
        )
        // Нераспознанный сервером файл (№2) проходит: без папки он получил бы 400.
        assertEquals((1L..3L).map { Upload(it, CAMERA_ID) }, uploads.toList())
        assertEquals(3, dao.countByStatus(MediaFileStatus.SYNCED))
        assertEquals(0, dao.countByStatus(MediaFileStatus.FAILED))
    }

    @Test
    fun existingCameraIsUsedAndPrecheckStillSkipsKnownChecksum() = runBlocking {
        cameraId = CAMERA_ID
        ready(1)
        ready(2)
        existingChecksums = setOf(sha256(image(1).bytes))

        runUploader()

        assertEquals(listOf(Upload(2, CAMERA_ID)), uploads.toList())
        assertEquals(2, dao.countByStatus(MediaFileStatus.SYNCED))
        assertNull(dao.single(1).serverFileId)
    }

    @Test
    fun temporaryCameraFailureLeavesQueueWithoutFailedAndRecovers() = runBlocking {
        ready(1)
        pendingUpload(2)
        cameraResponse = { MockResponse().setResponseCode(503) }

        assertEquals(ListenableWorker.Result.retry(), runUploader())

        assertEquals(listOf("/api/v1/test", "/api/v1/folders/camera"), paths.toList())
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(1).status)
        assertEquals(MediaFileStatus.PENDING_UPLOAD, dao.single(2).status)
        assertEquals(0, dao.countByStatus(MediaFileStatus.FAILED))
        assertEquals(SyncStatus.ERROR, syncStatusStore.observeRunResults().first().uploaderProblem?.status)

        cameraResponse = { null }
        assertTrue(runUploader() is ListenableWorker.Result.Success)

        assertEquals(setOf(Upload(1, CAMERA_ID), Upload(2, CAMERA_ID)), uploads.toSet())
        assertEquals(2, dao.countByStatus(MediaFileStatus.SYNCED))
        assertNull(syncStatusStore.observeRunResults().first().uploaderProblem)
    }

    @Test
    fun brokenConnectionWhileGettingCameraIsTemporary() = runBlocking {
        pendingUpload(1)
        cameraResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }

        assertEquals(ListenableWorker.Result.retry(), runUploader())

        assertTrue(uploads.isEmpty())
        assertEquals(MediaFileStatus.PENDING_UPLOAD, dao.single(1).status)
    }

    @Test
    fun cameraIdIsRequestedOncePerRun() = runBlocking {
        (1L..25L).forEach { pendingUpload(it) }

        runUploader()

        assertEquals(1, paths.count { it == "/api/v1/folders/camera" })
        assertEquals(25, uploads.size)
    }

    @Test
    fun outdatedIdInPrecheckIsReplacedByNextRun() = runBlocking {
        cameraId = CAMERA_ID
        ready(1)
        onPrecheck = { cameraId = NEW_CAMERA_ID; onPrecheck = {} }

        assertEquals(ListenableWorker.Result.retry(), runUploader())
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(1).status)

        assertTrue(runUploader() is ListenableWorker.Result.Success)
        assertEquals(listOf(Upload(1, NEW_CAMERA_ID)), uploads.toList())
    }

    @Test
    fun outdatedIdInUploadReturnsFileToQueueAndNextRunSendsItWithNewId() = runBlocking {
        cameraId = CAMERA_ID
        pendingUpload(1)
        pendingUpload(2)
        onUpload = { cameraId = NEW_CAMERA_ID; onUpload = {} }

        assertEquals(ListenableWorker.Result.retry(), runUploader())
        assertEquals(listOf(Upload(1, CAMERA_ID)), uploads.toList())
        assertEquals(2, dao.countByStatus(MediaFileStatus.PENDING_UPLOAD))
        assertEquals(0, dao.countByStatus(MediaFileStatus.FAILED))

        assertTrue(runUploader() is ListenableWorker.Result.Success)
        assertEquals(listOf(Upload(1, NEW_CAMERA_ID), Upload(2, NEW_CAMERA_ID)), uploads.drop(1))
        assertEquals(2, dao.countByStatus(MediaFileStatus.SYNCED))
    }

    // Защита от цикла: на постоянно устаревающий ID приходится по одной попытке на прогон, дальше решает backoff.
    @Test
    fun permanentlyOutdatedIdMakesOneAttemptPerRun() = runBlocking {
        cameraId = CAMERA_ID
        pendingUpload(1)
        onUpload = { cameraId = cameraId!! + 1 }

        repeat(3) { assertEquals(ListenableWorker.Result.retry(), runUploader()) }

        assertEquals(3, paths.count { it == "/api/v1/folders/camera" })
        assertEquals(3, uploads.size)
        assertEquals(MediaFileStatus.PENDING_UPLOAD, dao.single(1).status)
    }

    @Test
    fun http400WithExplicitFolderIsTerminalAndNotResent() = runBlocking {
        cameraId = CAMERA_ID
        pendingUpload(1)
        rejectedUploads = setOf(1)

        runUploader()
        runUploader()

        assertEquals(1, uploads.size)
        val failed = dao.single(1)
        assertEquals(MediaFileStatus.FAILED, failed.status)
        assertEquals(FailureReason.HTTP_400, failed.failureReason)
        assertFalse(FailureReason.HTTP_400.isRetryable)
    }

    private suspend fun runUploader(): ListenableWorker.Result {
        val baseUrlProvider = BaseUrlProvider(preferences)
        val client = OkHttpClient()
        val api = ApiServiceFactory(baseUrlProvider, client, client, client)
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ) = UploadWorker(
                appContext,
                workerParameters,
                FolderRepository(api),
                ChecksumPrecheckRepository(dao, api),
                FileUploadRepository(dao, api, MediaStreamOpener(appContext)),
                ServerRepository(api),
                baseUrlProvider,
                syncStatusStore,
                InMemoryTokenStorage(Tokens("access", "refresh")),
                mock(PeriodicSyncCoordinator::class.java),
                SyncScheduler(appContext),
                SyncClock.SYSTEM
            )
        }
        return TestListenableWorkerBuilder<UploadWorker>(context).setWorkerFactory(factory).build().doWork()
    }

    private fun image(id: Long) =
        FakeMediaStore.Image(id = id, bytes = "photo-$id".toByteArray(), dateModifiedSec = 2_000_000_000L - id)

    private suspend fun ready(id: Long) {
        val image = media.add(image(id))
        dao.insert(row(image, MediaFileStatus.CHECKSUM_READY, sha256(image.bytes)))
    }

    private suspend fun pendingUpload(id: Long) {
        val image = media.add(image(id))
        dao.insert(row(image, MediaFileStatus.PENDING_UPLOAD, sha256(image.bytes)))
    }

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        paths += path
        return when (path) {
            "/api/v1/test" -> json("""{"message":"ok"}""")
            "/api/v1/folders/camera" -> cameraResponse() ?: json(
                """{"id":${cameraId ?: CAMERA_ID.also { cameraId = it }},"name":"Camera","folderType":"CAMERA"}"""
            )
            "/api/v1/files/checksums/exists" -> precheck(request.body.readUtf8())
            "/api/v1/files/upload" -> upload(request.body.readUtf8())
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun precheck(body: String): MockResponse {
        onPrecheck()
        val folderId = Regex("\"folderId\":(\\d+)").find(body)!!.groupValues[1].toLong()
        if (folderId != cameraId) return MockResponse().setResponseCode(404)
        val requested = Regex("[0-9a-f]{64}").findAll(body).map { it.value }.toList()
        val existing = requested.filter { it in existingChecksums }
        return json("""{"existing":${existing.toJson()},"missing":${(requested - existing.toSet()).toJson()}}""")
    }

    private fun upload(body: String): MockResponse {
        val id = Regex("IMG_(\\d+)\\.jpg").find(body)!!.groupValues[1].toLong()
        val folderId = Regex("name=\"folderId\"[\\s\\S]*?\r\n\r\n(\\d+)").find(body)?.groupValues?.get(1)?.toLong()
        uploads += Upload(id, folderId)
        if (folderId == null && id == UNDETECTED) return MockResponse().setResponseCode(400)
        if (id in rejectedUploads) return MockResponse().setResponseCode(400)
        val currentCamera = cameraId
        onUpload()
        if (folderId != currentCamera) return MockResponse().setResponseCode(404)
        return if (cameraId != currentCamera) MockResponse().setResponseCode(404) else json("""{"id":${1000 + id}}""")
    }

    private fun List<String>.toJson() = joinToString(",", "[", "]") { "\"$it\"" }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}

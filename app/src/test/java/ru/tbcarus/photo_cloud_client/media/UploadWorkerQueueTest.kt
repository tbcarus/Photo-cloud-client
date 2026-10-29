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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/** Загрузчик над подготовленной очередью Room: сохранение очереди, восстановление и условные переходы. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadWorkerQueueTest {

    private companion object {
        const val ROOT_ID = 1L
        const val CAMERA_ID = 2L
        const val SERVER_FILE_ID = 100L
    }

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private lateinit var serverPreferences: ServerPreferences
    private val syncStatusStore = testSyncStatusStore()

    private val dao get() = db.mediaFileDao()

    // Поведение тестового Server.
    @Volatile private var serverAvailable = true
    @Volatile private var existingChecksums: Set<String> = emptySet()
    @Volatile private var beforePrecheckResponse: () -> Unit = {}
    @Volatile private var beforeUploadResponse: () -> Unit = {}
    private val paths = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        media = FakeMediaStore.install(context)
        serverPreferences = ServerPreferences(context)
        runBlocking { serverPreferences.saveConnection("127.0.0.1", server.port.toString()) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun queueSurvivesUnavailableServerAndIsUploadedAfterRecovery() = runBlocking {
        grantFullAccess()
        val image = media.add(image(1, "photo-1"))
        dao.insert(row(image, MediaFileStatus.CHECKSUM_READY, checksum = sha256(image.bytes)))
        serverAvailable = false

        assertEquals(ListenableWorker.Result.retry(), runUploader())
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(image.id).status)

        serverAvailable = true
        val result = runUploader()

        assertTrue(result is ListenableWorker.Result.Success)
        val file = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, file.status)
        assertEquals(SERVER_FILE_ID, file.serverFileId)
        assertNull(syncStatusStore.observeRunResults().first().uploaderProblem)
    }

    @Test
    fun photoAlreadyOnServerIsNotUploadedAgain() = runBlocking {
        grantFullAccess()
        val image = media.add(image(1, "photo-1"))
        dao.insert(row(image, MediaFileStatus.CHECKSUM_READY, checksum = sha256(image.bytes)))
        existingChecksums = setOf(sha256(image.bytes))

        runUploader()

        assertEquals(MediaFileStatus.SYNCED, dao.single(image.id).status)
        assertTrue(paths.none { it.endsWith("/files/upload") })
        // Existing — не передача файла: время отправки не записывается.
        assertNull(syncStatusStore.observeRunResults().first().lastUploadAt)
    }

    @Test
    fun uploaderResultsAreRecordedSeparatelyFromProvider() = runBlocking {
        grantFullAccess()
        val image = media.add(image(1, "photo-1"))
        dao.insert(row(image, MediaFileStatus.CHECKSUM_READY, checksum = sha256(image.bytes)))
        syncStatusStore.recordProviderRun(scanned = true, problem = SyncStatus.ERROR)
        val provider = syncStatusStore.observeRunResults().first()
        serverAvailable = false

        runUploader()

        val unavailable = syncStatusStore.observeRunResults().first()
        assertEquals(SyncStatus.SERVER_UNAVAILABLE, unavailable.uploaderProblem?.status)
        assertNull(unavailable.lastUploadAt)

        serverAvailable = true
        runUploader()

        val uploaded = syncStatusStore.observeRunResults().first()
        assertNull(uploaded.uploaderProblem)
        assertNotNull(uploaded.lastUploadAt)
        // Успех Загрузчика не снимает проблему Поставщика.
        assertEquals(provider.providerProblem, uploaded.providerProblem)
        assertEquals(provider.lastScanAt, uploaded.lastScanAt)
    }

    @Test
    fun interruptedUploadIsRecovered() = runBlocking {
        grantFullAccess()
        val image = media.add(image(1, "photo-1"))
        dao.insert(row(image, MediaFileStatus.UPLOADING, checksum = sha256(image.bytes)))

        runUploader()

        val file = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, file.status)
        assertEquals(SERVER_FILE_ID, file.serverFileId)
    }

    @Test
    fun missingPermissionKeepsQueueIntact() = runBlocking {
        val image = media.add(image(1, "photo-1"))
        dao.insert(row(image, MediaFileStatus.PENDING_UPLOAD, checksum = sha256(image.bytes)))

        val result = runUploader()

        assertEquals(ListenableWorker.Result.success(), result)
        // Без разрешения строка не уходит в FAILED(PERMISSION), а ждёт следующего прогона.
        val file = dao.single(image.id)
        assertEquals(MediaFileStatus.PENDING_UPLOAD, file.status)
        assertNull(file.failureReason)
        assertEquals(listOf("/api/v1/test"), paths.toList())
    }

    @Test
    fun lateUploadResultDoesNotOverwriteChangedPhoto() = runBlocking {
        grantFullAccess()
        val image = media.add(image(1, "photo-1"))
        dao.insert(row(image, MediaFileStatus.PENDING_UPLOAD, checksum = sha256(image.bytes)))
        // Пока Server принимает файл, Поставщик обнаружил новую версию того же MediaStore ID.
        beforeUploadResponse = { changeOnDevice(image) }

        runUploader()

        val file = dao.single(image.id)
        assertEquals(MediaFileStatus.PENDING, file.status)
        assertNull(file.checksum)
        assertNull(file.serverFileId)
    }

    @Test
    fun latePrecheckResultDoesNotOverwriteChangedPhoto() = runBlocking {
        grantFullAccess()
        val image = media.add(image(1, "photo-1"))
        dao.insert(row(image, MediaFileStatus.CHECKSUM_READY, checksum = sha256(image.bytes)))
        existingChecksums = setOf(sha256(image.bytes))
        beforePrecheckResponse = { changeOnDevice(image) }

        runUploader()

        val file = dao.single(image.id)
        assertEquals(MediaFileStatus.PENDING, file.status)
        assertNull(file.checksum)
    }

    /** Имитация scan Поставщика после изменения файла: сброс версии и новые метаданные. */
    private fun changeOnDevice(image: FakeMediaStore.Image) = runBlocking {
        val newSize = image.size + 10
        val newModified = image.lastModifiedMs + 60_000
        dao.applyScannedMetadata(
            image.id, "IMG_${image.id}.jpg", image.relativePath, image.mimeType,
            newSize, image.lastModifiedMs, newModified, image.uri.toString()
        )
    }

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        paths += path
        if (!serverAvailable) return MockResponse().setResponseCode(503)
        return when {
            path == "/api/v1/test" -> json("""{"message":"ok"}""")
            path == "/api/v1/folders/root" -> json("""{"id":$ROOT_ID,"parentId":null,"name":"root","folderType":"ROOT"}""")
            path == "/api/v1/folders/$ROOT_ID/children" ->
                json("""[{"id":$CAMERA_ID,"parentId":$ROOT_ID,"name":"Camera","folderType":"CAMERA"}]""")
            path == "/api/v1/files/checksums/exists" -> {
                val requested = Regex("[0-9a-f]{64}").findAll(request.body.readUtf8()).map { it.value }.toList()
                beforePrecheckResponse()
                val existing = requested.filter { it in existingChecksums }
                val missing = requested - existing.toSet()
                json("""{"existing":${existing.toJson()},"missing":${missing.toJson()}}""")
            }
            path == "/api/v1/files/upload" -> {
                beforeUploadResponse()
                json("""{"id":$SERVER_FILE_ID}""")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun List<String>.toJson() = joinToString(",", "[", "]") { "\"$it\"" }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun grantFullAccess() = shadowOf(context).grantPermissions(mediaReadPermission)

    private fun image(id: Long, content: String) =
        FakeMediaStore.Image(id = id, bytes = content.toByteArray(), dateModifiedSec = 1_700_000_000L + id)

    private suspend fun runUploader(): ListenableWorker.Result {
        val baseUrlProvider = BaseUrlProvider(serverPreferences)
        val client = OkHttpClient()
        val apiServiceFactory = ApiServiceFactory(baseUrlProvider, client, client, client)
        val folderRepository = FolderRepository(apiServiceFactory)
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ) = UploadWorker(
                appContext,
                workerParameters,
                ChecksumPrecheckRepository(dao, apiServiceFactory, folderRepository),
                FileUploadRepository(dao, apiServiceFactory, folderRepository, MediaStreamOpener(appContext)),
                ServerRepository(apiServiceFactory),
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
}

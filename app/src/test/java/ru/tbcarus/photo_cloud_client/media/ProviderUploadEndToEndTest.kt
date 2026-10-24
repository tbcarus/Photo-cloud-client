package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.auth.AuthRepository
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.server.ServerRepository
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.sha256
import ru.tbcarus.photo_cloud_client.ui.screens.files.FilesViewModel
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Сквозной путь через WorkManager: MediaStore → Поставщик → Room → сигнал → Загрузчик → Server.
 * Workers создаются с настоящими репозиториями; Server — MockWebServer; адрес в памяти не восстановлен,
 * как в процессе без UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProviderUploadEndToEndTest {

    private companion object {
        const val ROOT_ID = 1L
        const val CAMERA_ID = 2L
    }

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private lateinit var scheduler: SyncScheduler
    private lateinit var coordinator: PeriodicSyncCoordinator
    private lateinit var mediaFileRepository: MediaFileRepository
    private val storage = InMemoryTokenStorage(Tokens("access", "refresh"))
    private val workManager get() = WorkManager.getInstance(context)
    private val dao get() = db.mediaFileDao()

    // Server: нет ни одного checksum, id новых файлов растут с 100.
    private val nextFileId = AtomicLong(100)
    private val paths = CopyOnWriteArrayList<String>()
    private val requestedChecksums = CopyOnWriteArrayList<String>()

    // Статусы строки в моменты, когда её видят MediaStore-поток и Server.
    private val trace = CopyOnWriteArrayList<MediaFileStatus>()

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
        media.onOpen = { id -> record(id) }
        shadowOf(context).grantPermissions(mediaReadPermission)
        val serverPreferences = ServerPreferences(context)
        runBlocking { serverPreferences.saveConnection("127.0.0.1", server.port.toString()) }

        val baseUrlProvider = BaseUrlProvider(serverPreferences)
        val client = OkHttpClient()
        val apiServiceFactory = ApiServiceFactory(baseUrlProvider, client, client, client)
        val folderRepository = FolderRepository(apiServiceFactory)
        val syncStatusStore = SyncStatusStore()
        // Общие экземпляры, как @Singleton в приложении: Mutex стадий действуют на все задачи.
        mediaFileRepository = MediaFileRepository(dao, MediaStoreRepository(context))
        val checksumRepository = ChecksumRepository(dao, context)
        val precheckRepository = ChecksumPrecheckRepository(dao, apiServiceFactory, folderRepository)
        val uploadRepository = FileUploadRepository(dao, apiServiceFactory, folderRepository, context)
        val serverRepository = ServerRepository(apiServiceFactory)
        scheduler = SyncScheduler(context, syncStatusStore)
        coordinator = PeriodicSyncCoordinator(
            context,
            AuthRepository(storage, baseUrlProvider, apiServiceFactory),
            scheduler,
            MediaChangeObserver(context, scheduler)
        )
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? = when (workerClassName) {
                ProviderWorker::class.java.name -> ProviderWorker(
                    appContext, workerParameters, mediaFileRepository, checksumRepository, syncStatusStore, coordinator
                )
                UploadWorker::class.java.name -> UploadWorker(
                    appContext, workerParameters, precheckRepository, uploadRepository, serverRepository,
                    baseUrlProvider, syncStatusStore, storage, coordinator
                )
                else -> null
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun newPhotoTravelsFullPathAndUnchangedPhotoIsNotUploadedAgain() = runBlocking {
        val image = media.add(image(1, "photo-1"))

        // Обнаружение: строка появляется в PENDING.
        mediaFileRepository.scanImages()
        record(image.id)

        // Поставщик: HASHING → CHECKSUM_READY без единого запроса к Server, затем сигнал Загрузчику.
        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)
        assertEquals(WorkInfo.State.SUCCEEDED, runProvider().state)
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(image.id).status)
        assertEquals(sha256(image.bytes), dao.single(image.id).checksum)
        assertEquals(0, server.requestCount)

        // Загрузчик по сигналу: pre-check → PENDING_UPLOAD → UPLOADING → SYNCED.
        assertEquals(WorkInfo.State.SUCCEEDED, runSignalledUpload(uploadsBefore).state)
        record(image.id)
        val synced = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, synced.status)
        assertEquals(100L, synced.serverFileId)
        assertEquals(
            listOf(
                MediaFileStatus.PENDING, MediaFileStatus.HASHING, MediaFileStatus.CHECKSUM_READY,
                MediaFileStatus.PENDING_UPLOAD, MediaFileStatus.UPLOADING, MediaFileStatus.SYNCED
            ),
            trace.distinctConsecutive()
        )
        assertEquals(listOf(sha256(image.bytes)), requestedChecksums.toList())

        // Повторный Поставщик не хэширует неизменённое фото и не будит Загрузчика.
        val opensAfterUpload = media.openCount(image.id)
        val uploadsAfterSync = ids(SyncScheduler.UPLOAD_WORK_NAME)
        runProvider()
        assertEquals(opensAfterUpload, media.openCount(image.id))
        assertEquals(uploadsAfterSync, ids(SyncScheduler.UPLOAD_WORK_NAME))

        // Даже явный прогон Загрузчика (как periodic) ничего не отправляет повторно.
        assertEquals(WorkInfo.State.SUCCEEDED, runUpload().state)
        assertEquals(1, paths.count { it == "/api/v1/files/upload" })
        assertEquals(1, requestedChecksums.size)
        assertEquals(MediaFileStatus.SYNCED, dao.single(image.id).status)
    }

    @Test
    fun changedSyncedPhotoGetsNewChecksumAndIsUploadedAgain() = runBlocking {
        val image = media.add(image(1, "old content"))
        syncAll()
        assertEquals(100L, dao.single(image.id).serverFileId)

        // Тот же MediaStore ID, новое содержимое, размер и время изменения.
        image.bytes = "new and longer content".toByteArray()
        image.dateModifiedSec += 60
        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)
        runProvider()

        val prepared = dao.single(image.id)
        assertEquals(MediaFileStatus.CHECKSUM_READY, prepared.status)
        assertEquals(sha256(image.bytes), prepared.checksum)
        assertNull(prepared.serverFileId)

        runSignalledUpload(uploadsBefore)

        val synced = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, synced.status)
        assertEquals(101L, synced.serverFileId)
        assertEquals(image.size, synced.size)
        assertEquals(1, rows())
        assertEquals(2, paths.count { it == "/api/v1/files/upload" })
        assertEquals(sha256(image.bytes), requestedChecksums.last())
    }

    @Test
    fun deletedPhotoIsRemovedOnlyFromRoom() = runBlocking {
        val kept = media.add(image(1, "kept"))
        val deleted = media.add(image(2, "deleted"))
        syncAll()
        val requestsBefore = server.requestCount
        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)

        media.remove(deleted.id)
        runProvider()

        assertNull(dao.find(deleted.id))
        assertEquals(MediaFileStatus.SYNCED, dao.single(kept.id).status)
        assertEquals(requestsBefore, server.requestCount)
        assertEquals(uploadsBefore, ids(SyncScheduler.UPLOAD_WORK_NAME))
    }

    @Test
    fun permissionGrantStartsProviderWithoutWaitingForPeriodic() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val image = media.add(image(1, "photo-1"))
        val viewModel = FilesViewModel(mediaFileRepository, scheduler, coordinator)

        viewModel.onMediaPermissionGranted()

        awaitUntil { runBlocking { dao.find(image.id)?.status } == MediaFileStatus.CHECKSUM_READY }
        awaitUntil { infos(SyncScheduler.PROVIDER_WORK_NAME).any { it.state == WorkInfo.State.SUCCEEDED } }
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(image.id).status)
        assertTrue(infos(SyncScheduler.PROVIDER_WORK_NAME).any { it.state == WorkInfo.State.SUCCEEDED })
        // Хэш выполнил разовый Поставщик: periodic ждёт своих условий и ещё не запускался.
        val periodic = infos(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).single()
        assertEquals(WorkInfo.State.ENQUEUED, periodic.state)
        assertEquals(0, periodic.runAttemptCount)
    }

    /** Полный цикл Поставщик → Загрузчик для всех фото библиотеки. */
    private fun syncAll() {
        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)
        runProvider()
        runSignalledUpload(uploadsBefore)
    }

    // Разовый Поставщик без constraints выполняется сразу; Загрузчику нужна сеть.
    private fun runProvider(): WorkInfo =
        runNew(SyncScheduler.PROVIDER_WORK_NAME, needsNetwork = false) { scheduler.enqueueProvider() }

    private fun runUpload(): WorkInfo =
        runNew(SyncScheduler.UPLOAD_WORK_NAME, needsNetwork = true) { scheduler.enqueueUpload() }

    /** Разовый Загрузчик, поставленный сигналом Поставщика; сеть «появляется» через TestDriver. */
    private fun runSignalledUpload(before: Set<UUID>): WorkInfo {
        val id = awaitNewId(SyncScheduler.UPLOAD_WORK_NAME, before)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
        return awaitFinished(id)
    }

    private fun runNew(name: String, needsNetwork: Boolean, start: () -> Unit): WorkInfo {
        val before = ids(name)
        start()
        val id = awaitNewId(name, before)
        if (needsNetwork) WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
        return awaitFinished(id)
    }

    private fun awaitNewId(name: String, before: Set<UUID>): UUID {
        var id: UUID? = null
        awaitUntil { id = (ids(name) - before).firstOrNull(); id != null }
        return requireNotNull(id) { "Задача $name не поставлена" }
    }

    private fun awaitFinished(id: UUID): WorkInfo {
        awaitUntil { workManager.getWorkInfoById(id).get()!!.state.isFinished }
        return workManager.getWorkInfoById(id).get()!!
    }

    // Workers выполняются на Dispatchers.Default, а enqueue асинхронен — ждём опросом с ограничением по времени.
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    private fun ids(name: String): Set<UUID> = infos(name).map { it.id }.toSet()

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private suspend fun rows(): Int = dao.getAll().first().size

    private fun record(id: Long) {
        runBlocking { dao.find(id)?.status }?.let { trace += it }
    }

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        paths += path
        record(1)
        return when (path) {
            "/api/v1/test" -> json("""{"message":"ok"}""")
            "/api/v1/folders/root" -> json("""{"id":$ROOT_ID,"parentId":null,"name":"root","folderType":"ROOT"}""")
            "/api/v1/folders/$ROOT_ID/children" ->
                json("""[{"id":$CAMERA_ID,"parentId":$ROOT_ID,"name":"Camera","folderType":"CAMERA"}]""")
            "/api/v1/files/checksums/exists" -> {
                val requested = Regex("[0-9a-f]{64}").findAll(request.body.readUtf8()).map { it.value }.toList()
                requestedChecksums += requested
                json("""{"existing":[],"missing":${requested.joinToString(",", "[", "]") { "\"$it\"" }}}""")
            }
            "/api/v1/files/upload" -> json("""{"id":${nextFileId.getAndIncrement()}}""")
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun image(id: Long, content: String) =
        FakeMediaStore.Image(id = id, bytes = content.toByteArray(), dateModifiedSec = 1_700_000_000L + id)

    private fun <T> List<T>.distinctConsecutive(): List<T> =
        fold(mutableListOf()) { acc, item -> if (acc.lastOrNull() != item) acc += item; acc }
}

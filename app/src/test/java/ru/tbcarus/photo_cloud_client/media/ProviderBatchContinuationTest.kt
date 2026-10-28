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
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
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
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.row
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.sha256
import java.io.FileNotFoundException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Поставщик с большой очередью (A8.1): хеширование партиями в пределах бюджета времени,
 * сигнал Загрузчику после первой готовой партии и продолжение следующим прогоном через WorkManager.
 * Время управляемое: каждое открытие файла «занимает» [STEP_MS], реальных минут тест не ждёт.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProviderBatchContinuationTest {

    private companion object {
        const val STEP_MS = 3_000L
        // Файлов, которые успевает начать один прогон: проверка бюджета идёт перед каждым файлом.
        val FILES_PER_RUN = (SyncConfig.WORKER_TIME_BUDGET_MS / STEP_MS).toInt()
        const val ROOT_ID = 1L
        const val CAMERA_ID = 2L
    }

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private lateinit var scheduler: SyncScheduler
    private lateinit var mediaFileRepository: MediaFileRepository
    private lateinit var checksumRepository: ChecksumRepository
    private val dao get() = db.mediaFileDao()
    private val workManager get() = WorkManager.getInstance(context)
    private val clock = AtomicLong()
    private val opened = CopyOnWriteArrayList<Long>()
    @Volatile private var onOpen: (Long) -> Unit = {}

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        media = FakeMediaStore.install(context)
        media.onOpen = { id ->
            opened += id
            onOpen(id)
            clock.addAndGet(STEP_MS)
        }
        shadowOf(context).grantPermissions(mediaReadPermission)

        val syncStatusStore = SyncStatusStore()
        scheduler = SyncScheduler(context, syncStatusStore)
        val baseUrlProvider = BaseUrlProvider(ServerPreferences(context))
        val client = OkHttpClient()
        // Пара есть — Поставщик ставит Загрузчика; тот ждёт сети и в этих тестах не выполняется.
        val coordinator = PeriodicSyncCoordinator(
            context,
            AuthRepository(InMemoryTokenStorage(Tokens("access", "refresh")), baseUrlProvider,
                ApiServiceFactory(baseUrlProvider, client, client, client)),
            scheduler,
            MediaChangeObserver(context, scheduler)
        )
        mediaFileRepository = MediaFileRepository(dao, MediaStoreRepository(context))
        checksumRepository = ChecksumRepository(dao, MediaStreamOpener(context), context)
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? = when (workerClassName) {
                ProviderWorker::class.java.name -> ProviderWorker(
                    appContext, workerParameters, mediaFileRepository, checksumRepository, syncStatusStore,
                    coordinator, scheduler, SyncClock { clock.get() }
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
        db.close()
    }

    @Test
    fun largeQueueIsHashedByChainOfBudgetLimitedRuns() = runBlocking {
        val total = FILES_PER_RUN + 110
        // ID добавляются вразнобой: порядок обработки определяет mediaStoreId, а не порядок появления.
        (1L..total).shuffled().forEach { media.add(image(it)) }
        val broken = media.add(image(total + 1L)).apply { openFailure = { FileNotFoundException("удалён") } }

        scheduler.enqueueProvider()
        awaitUntil { providerRuns().size == 2 && providerRuns().all { it.state.isFinished } }

        // Два прогона: первый остановлен бюджетом и зарегистрировал продолжение, второй дочитал очередь.
        assertEquals(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.SUCCEEDED), providerRuns().map { it.state })
        assertEquals(total, dao.countByStatus(MediaFileStatus.CHECKSUM_READY))
        assertEquals(0, dao.countByStatus(MediaFileStatus.PENDING))
        // Ошибка отдельного файла не остановила остальные.
        assertEquals(MediaFileStatus.FAILED, dao.single(broken.id).status)
        // Каждый файл открыт один раз — уже посчитанные во втором прогоне не перечитываются, порядок FIFO.
        assertEquals((1L..total + 1L).toList(), opened.toList())
    }

    @Test
    fun uploaderIsSignalledAfterFirstReadyBatchBeforeQueueEnds() = runBlocking {
        (1L..SyncConfig.PROVIDER_BATCH_SIZE * 2L).forEach { media.add(image(it)) }
        val uploaderQueuedDuringSecondBatch = AtomicBoolean(false)
        onOpen = { id ->
            if (id == SyncConfig.PROVIDER_BATCH_SIZE + 1L) {
                uploaderQueuedDuringSecondBatch.set(uploadWork().any { it.state == WorkInfo.State.ENQUEUED })
            }
        }

        scheduler.enqueueProvider()
        awaitUntil { providerRuns().all { it.state.isFinished } && providerRuns().isNotEmpty() }

        assertTrue(uploaderQueuedDuringSecondBatch.get())
        // Повторные сигналы после следующих партий не дублируют ожидающего Загрузчика.
        assertEquals(1, uploadWork().size)
    }

    @Test
    fun queueFinishedWithinBudgetDoesNotRegisterContinuation() = runBlocking {
        (1L..5L).forEach { media.add(image(it)) }

        scheduler.enqueueProvider()
        awaitUntil { providerRuns().isNotEmpty() && providerRuns().all { it.state.isFinished } }

        assertEquals(listOf(WorkInfo.State.SUCCEEDED), providerRuns().map { it.state })
        assertEquals(5, dao.countByStatus(MediaFileStatus.CHECKSUM_READY))
    }

    @Test
    fun runWithoutProgressDoesNotStartEndlessChain() = runBlocking {
        (1L..5L).forEach { media.add(image(it)) }
        // Scan израсходовал весь бюджет: ни один файл не начат, очередь осталась.
        media.beforeQuery = { clock.addAndGet(SyncConfig.WORKER_TIME_BUDGET_MS) }

        scheduler.enqueueProvider()
        awaitUntil { providerRuns().isNotEmpty() && providerRuns().all { it.state.isFinished } }
        Thread.sleep(200)

        assertEquals(listOf(WorkInfo.State.SUCCEEDED), providerRuns().map { it.state })
        assertEquals(5, dao.countByStatus(MediaFileStatus.PENDING))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun hashingIsNotBlockedByRunningUpload() = runBlocking {
        val uploadStarted = CountDownLatch(1)
        val releaseUpload = CountDownLatch(1)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v1/folders/root" -> json("""{"id":$ROOT_ID,"parentId":null,"name":"root","folderType":"ROOT"}""")
                    "/api/v1/folders/$ROOT_ID/children" ->
                        json("""[{"id":$CAMERA_ID,"parentId":$ROOT_ID,"name":"Camera","folderType":"CAMERA"}]""")
                    "/api/v1/files/upload" -> {
                        uploadStarted.countDown()
                        releaseUpload.await(10, TimeUnit.SECONDS)
                        json("""{"id":500}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        try {
            val prepared = media.add(image(1))
            dao.insert(row(prepared, MediaFileStatus.PENDING_UPLOAD, sha256(prepared.bytes)))
            val next = (2L..4L).map { media.add(image(it)).also { dao.insert(row(it, MediaFileStatus.PENDING)) } }
            val baseUrlProvider = BaseUrlProvider(ServerPreferences(context)).apply {
                baseUrl = "http://127.0.0.1:${server.port}/"
            }
            val client = OkHttpClient()
            val api = ApiServiceFactory(baseUrlProvider, client, client, client)
            val uploadRepository = FileUploadRepository(dao, api, FolderRepository(api), MediaStreamOpener(context))

            val upload = async(Dispatchers.IO) { uploadRepository.uploadPendingCameraFiles() }
            assertTrue(uploadStarted.await(10, TimeUnit.SECONDS))

            // Загрузчик держит файл в отправке, а Поставщик готовит следующие.
            val hashed = checksumRepository.computePendingChecksums() as ChecksumOutcome.Success
            assertEquals(3, hashed.result.succeeded)
            next.forEach { assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(it.id).status) }
            assertEquals(MediaFileStatus.UPLOADING, dao.single(prepared.id).status)

            releaseUpload.countDown()
            assertTrue(upload.await() is UploadOutcome.Success)
            assertEquals(MediaFileStatus.SYNCED, dao.single(prepared.id).status)
        } finally {
            releaseUpload.countDown()
            server.shutdown()
        }
    }

    private fun providerRuns(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(SyncScheduler.PROVIDER_WORK_NAME).get()

    private fun uploadWork(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(SyncScheduler.UPLOAD_WORK_NAME).get()

    private fun image(id: Long) =
        FakeMediaStore.Image(id = id, bytes = "photo-$id".toByteArray(), dateModifiedSec = 1_700_000_000L + id)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    // Workers выполняются на Dispatchers.Default — ждём опросом с ограничением по времени.
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Условие не выполнено за отведённое время", condition())
    }
}

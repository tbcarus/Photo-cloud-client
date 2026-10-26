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
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.sha256

/**
 * Разовый Поставщик при старте процесса (A4.3) через WorkManager Test APIs.
 * SDK 34: на API 21 по умолчанию Robolectric ContentUriTrigger наблюдателя отбрасывается.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProcessStartSyncTest {

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private lateinit var scheduler: SyncScheduler
    private lateinit var coordinator: PeriodicSyncCoordinator
    private lateinit var processStartSync: ProcessStartSync
    private val storage = InMemoryTokenStorage()
    private val workManager get() = WorkManager.getInstance(context)
    private val dao get() = db.mediaFileDao()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        media = FakeMediaStore.install(context)

        val baseUrlProvider = BaseUrlProvider(ServerPreferences(context)).apply { baseUrl = "http://127.0.0.1:8080/" }
        val client = OkHttpClient()
        val syncStatusStore = SyncStatusStore()
        val mediaFileRepository = MediaFileRepository(dao, MediaStoreRepository(context))
        val checksumRepository = ChecksumRepository(dao, context)
        scheduler = SyncScheduler(context, syncStatusStore)
        coordinator = PeriodicSyncCoordinator(
            context,
            AuthRepository(storage, baseUrlProvider, ApiServiceFactory(baseUrlProvider, client, client, client)),
            scheduler,
            MediaChangeObserver(context, scheduler)
        )
        processStartSync = ProcessStartSync(context, scheduler)
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? = when (workerClassName) {
                ProviderWorker::class.java.name -> ProviderWorker(
                    appContext, workerParameters, mediaFileRepository, checksumRepository, syncStatusStore, coordinator
                )
                else -> null // Наблюдатель и Загрузчик здесь не выполняются: их constraints не выполнены.
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
    fun fullAccessStartsProviderThatPreparesPhotoAndSignalsUploader() = runBlocking {
        grantFullAccess()
        storage.saveTokens(Tokens("access", "refresh"))
        val image = media.add(FakeMediaStore.Image(1, "photo-1".toByteArray(), 1_700_000_001L))

        processStartSync.onProcessStart()

        awaitUntil { infos(SyncScheduler.PROVIDER_WORK_NAME).any { it.state == WorkInfo.State.SUCCEEDED } }
        assertEquals(1, infos(SyncScheduler.PROVIDER_WORK_NAME).size)
        // Существующий конвейер: Поставщик подготовил фото и поставил разовый Загрузчик.
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(image.id).status)
        assertEquals(sha256(image.bytes), dao.single(image.id).checksum)
        awaitUntil { infos(SyncScheduler.UPLOAD_WORK_NAME).isNotEmpty() }
        assertEquals(WorkInfo.State.ENQUEUED, infos(SyncScheduler.UPLOAD_WORK_NAME).single().state)
    }

    @Test
    fun withoutFullAccessNothingIsScheduled() {
        processStartSync.onProcessStart()

        assertTrue(infos(SyncScheduler.PROVIDER_WORK_NAME).isEmpty())
        assertEquals(0, media.queryCount)
    }

    @Test
    fun repeatedCallDoesNotDuplicateActiveProvider() {
        grantFullAccess()
        media.add(FakeMediaStore.Image(1, "photo-1".toByteArray(), 1_700_000_001L))
        media.queryDelayMs = 1_000

        processStartSync.onProcessStart()
        val running = awaitProvider { it.state == WorkInfo.State.RUNNING }
        // Совпадение старта процесса, события MediaStore и обычного запуска: тот же KEEP по тому же имени.
        processStartSync.onProcessStart()
        scheduler.enqueueProvider()

        assertEquals(listOf(running.id), infos(SyncScheduler.PROVIDER_WORK_NAME).map { it.id })
        awaitUntil { workManager.getWorkInfoById(running.id).get()!!.state.isFinished }
        assertEquals(1, media.maxConcurrentQueries)
    }

    @Test
    fun periodicTasksAndMediaWatcherAreNotTouched() {
        grantFullAccess()
        storage.saveTokens(Tokens("access", "refresh"))
        coordinator.reconcile()
        val before = listOf(
            SyncScheduler.PROVIDER_PERIODIC_WORK_NAME,
            SyncScheduler.UPLOAD_PERIODIC_WORK_NAME,
            SyncScheduler.MEDIA_TRIGGER_WORK_NAME
        ).associateWith { infos(it).single().id }

        processStartSync.onProcessStart()
        awaitUntil { infos(SyncScheduler.PROVIDER_WORK_NAME).any { it.state.isFinished } }

        before.forEach { (name, id) ->
            val info = infos(name).single()
            assertEquals(name, id, info.id)
            assertEquals(name, WorkInfo.State.ENQUEUED, info.state)
        }
    }

    private fun grantFullAccess() = shadowOf(context).grantPermissions(mediaReadPermission)

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun awaitProvider(condition: (WorkInfo) -> Boolean): WorkInfo {
        awaitUntil { infos(SyncScheduler.PROVIDER_WORK_NAME).any(condition) }
        return infos(SyncScheduler.PROVIDER_WORK_NAME).first(condition)
    }

    // Workers выполняются на фоновых потоках — ждём опросом с ограничением по времени.
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }
}

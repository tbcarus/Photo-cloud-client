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
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.auth.AuthRepository
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.server.ServerRepository
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import java.net.InetAddress
import java.util.UUID

/** UploadWorker без сохранённой пары: ни одного сетевого запроса, без retry, фон Загрузчика останавливается. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadWorkerNoSessionTest {

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var baseUrlProvider: BaseUrlProvider
    private val storage = InMemoryTokenStorage()
    private val syncStatusStore = SyncStatusStore()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        // Адрес сохранён и доступен: без проверки сессии worker сделал бы ping.
        val serverPreferences = ServerPreferences(context)
        runBlocking { serverPreferences.saveConnection("127.0.0.1", server.port.toString()) }
        baseUrlProvider = BaseUrlProvider(serverPreferences)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun noSessionStopsBeforeAnyRequestWithoutRetry() = runBlocking {
        val coordinator = mock(PeriodicSyncCoordinator::class.java)
        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(factory { coordinator })
            .build()

        val result = worker.doWork()

        assertEquals(
            ListenableWorker.Result.success(workDataOf(UploadWorker.STOP_REASON_KEY to UploadWorker.STOP_REASON_NO_SESSION)),
            result
        )
        assertEquals(0, server.requestCount)
        assertEquals(SyncStatus.ERROR, syncStatusStore.observe().first()?.status)
        verify(coordinator).reconcile()
    }

    @Test
    fun periodicRunWithoutSessionCancelsItsScheduleAfterFinishingRun() = runBlocking {
        val scheduler = initWorkManager()

        scheduler.enqueueUploadPeriodic()
        val periodicId = singleWorkId()
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(periodicId)
        driver.setPeriodDelayMet(periodicId)

        val info = awaitFinished(periodicId)

        // reconcile без пары отменил periodic изнутри прогона: работа отменена, а не поставлена на retry.
        // runAttemptCount растёт при запуске, поэтому 1 означает ровно один прогон без повторов.
        assertEquals(WorkInfo.State.CANCELLED, info.state)
        assertEquals(1, info.runAttemptCount)
        assertEquals(0, server.requestCount)
        // Прогон дошёл до конца: статус записан до отмены расписания.
        assertEquals(SyncStatus.ERROR, syncStatusStore.observe().first()?.status)
    }

    @Test
    fun oneTimeRunWithoutSessionSucceedsAndCancelsPeriodic() = runBlocking {
        val scheduler = initWorkManager()
        scheduler.enqueueUploadPeriodic()
        val periodicId = singleWorkId()

        scheduler.enqueueUpload()
        val oneTimeInfo = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.UPLOAD_WORK_NAME).get().single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(oneTimeInfo.id)

        val finished = awaitFinished(oneTimeInfo.id)

        assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
        assertEquals(UploadWorker.STOP_REASON_NO_SESSION, finished.outputData.getString(UploadWorker.STOP_REASON_KEY))
        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context).getWorkInfoById(periodicId).get()!!.state)
        assertEquals(0, server.requestCount)
    }

    private fun initWorkManager(): SyncScheduler {
        val scheduler = SyncScheduler(context, syncStatusStore)
        val client = OkHttpClient()
        val apiServiceFactory = ApiServiceFactory(baseUrlProvider, client, client, client)
        // Настоящий координатор: без пары reconcile отменяет periodic Загрузчика.
        val coordinator = PeriodicSyncCoordinator(
            context,
            AuthRepository(storage, baseUrlProvider, apiServiceFactory),
            scheduler,
            MediaChangeObserver(context, scheduler)
        )
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory { coordinator })
                .build()
        )
        return scheduler
    }

    private fun factory(coordinator: () -> PeriodicSyncCoordinator) = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters
        ): ListenableWorker {
            val client = OkHttpClient()
            val apiServiceFactory = ApiServiceFactory(baseUrlProvider, client, client, client)
            val folderRepository = FolderRepository(apiServiceFactory)
            val dao = db.mediaFileDao()
            return UploadWorker(
                appContext,
                workerParameters,
                ChecksumPrecheckRepository(dao, apiServiceFactory, folderRepository),
                FileUploadRepository(dao, apiServiceFactory, folderRepository, appContext),
                ServerRepository(apiServiceFactory),
                baseUrlProvider,
                syncStatusStore,
                storage,
                coordinator()
            )
        }
    }

    private fun singleWorkId(): UUID =
        WorkManager.getInstance(context).getWorkInfosByTag(UploadWorker::class.java.name).get().single().id

    // CoroutineWorker выполняется на Dispatchers.Default, поэтому итог ждём опросом с ограничением по времени.
    private fun awaitFinished(id: UUID): WorkInfo {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val info = WorkManager.getInstance(context).getWorkInfoById(id).get()!!
            if (info.state.isFinished || System.currentTimeMillis() > deadline) return info
            Thread.sleep(20)
        }
    }
}

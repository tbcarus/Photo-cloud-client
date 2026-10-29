package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Монитор состояния backup (A7.1) над настоящими Room, WorkManager и DataStore:
 * активность periodic, восстановление после перезапуска процесса и реакция на актуальные условия.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupStateMonitorTest {

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }

    /** Прогон, который выполняется до разрешения теста: позволяет наблюдать RUNNING. */
    class BlockingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            started.countDown()
            release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return Result.success()
        }

        companion object {
            @Volatile var started = CountDownLatch(1)
            @Volatile var release = CountDownLatch(1)
        }
    }

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var scheduler: SyncScheduler
    private val dao get() = db.mediaFileDao()
    private val tokens = InMemoryTokenStorage(Tokens("access", "refresh"))
    private val ip = MutableStateFlow<String?>("127.0.0.1")
    private val serverPreferences = mock(ServerPreferences::class.java).also {
        `when`(it.getIp()).thenReturn(ip)
        `when`(it.getPort()).thenReturn(flowOf("8080"))
    }
    private val storeFile = newSyncStatusFile()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = testSyncStatusStore(storeFile, storeScope)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        BlockingWorker.started = CountDownLatch(1)
        BlockingWorker.release = CountDownLatch(1)
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = BlockingWorker(appContext, workerParameters)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        )
        scheduler = SyncScheduler(context)
    }

    @After
    fun tearDown() {
        BlockingWorker.release.countDown()
        db.close()
    }

    private fun monitor(syncStatusStore: SyncStatusStore = store) = BackupStateMonitor(
        MediaFileRepository(dao, MediaStoreRepository(context)), scheduler, syncStatusStore, tokens, serverPreferences
    )

    private suspend fun BackupStateMonitor.awaitState(
        hasMediaAccess: Boolean = true,
        predicate: (BackupState) -> Boolean
    ): BackupState = withTimeout(TIMEOUT_MS) { observe(flowOf(hasMediaAccess)).first(predicate) }

    @Test
    fun runningPeriodicProviderMakesBackupRunning() = runBlocking {
        val monitor = monitor()
        // До первой успешной проверки пустой индекс — ожидание, а не «всё сохранено».
        assertEquals(BackupPhase.WAITING, monitor.awaitState { true }.phase)
        store.recordProviderRun(scanned = true, problem = null)
        assertEquals(BackupPhase.SYNCED, monitor.awaitState { it.lastScanAt != null }.phase)

        scheduler.enqueueProviderPeriodic()
        val periodicId = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).get().single().id
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(periodicId)
        driver.setPeriodDelayMet(periodicId)
        assertTrue(BlockingWorker.started.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))

        val running = monitor.awaitState { it.phase == BackupPhase.RUNNING }
        assertTrue(running.isRunning)

        // Periodic завершил прогон и снова ожидает следующего периода — не «выполняется».
        BlockingWorker.release.countDown()
        assertEquals(BackupPhase.SYNCED, monitor.awaitState { !it.isRunning }.phase)
    }

    @Test
    fun stateIsRestoredAfterProcessRestart() = runBlocking {
        dao.insert(pendingUpload(1))
        dao.insert(pendingUpload(2))
        store.recordProviderRun(scanned = true, problem = null)
        store.recordUploaderRun(problem = SyncStatus.SERVER_UNAVAILABLE, filesUploaded = false)
        val saved = store.observeRunResults().first()
        // Разовый Загрузчик ждёт сети: задача в очереди WorkManager, но не выполняется.
        scheduler.enqueueUpload()

        // Новый процесс: Room и база WorkManager те же, DataStore открыт заново.
        storeScope.coroutineContext.job.cancelAndJoin()
        val restarted = testSyncStatusStore(storeFile)
        val state = monitor(restarted).awaitState { true }

        assertEquals(BackupPhase.WAITING, state.phase)
        assertEquals(
            listOf(BackupIssue(BackupIssueKind.SERVER_UNAVAILABLE, saved.uploaderProblem!!.since)),
            state.issues
        )
        assertEquals(2, state.counters.toUpload)
        assertEquals(saved.lastScanAt, state.lastScanAt)
        assertNull(state.lastUploadAt)
    }

    @Test
    fun currentConditionsAreFollowedWithoutRestart() = runBlocking {
        dao.insert(pendingUpload(1))
        store.recordProviderRun(scanned = true, problem = null)
        val monitor = monitor()
        assertEquals(BackupPhase.WAITING, monitor.awaitState { it.lastScanAt != null }.phase)

        tokens.clear()
        assertEquals(
            listOf(BackupIssueKind.NOT_SIGNED_IN),
            monitor.awaitState { it.phase == BackupPhase.ATTENTION_REQUIRED }.issues.map { it.kind }
        )

        tokens.saveTokens(Tokens("access", "refresh"))
        ip.value = null
        assertEquals(
            listOf(BackupIssueKind.NO_SERVER_ADDRESS),
            monitor.awaitState { state -> state.issues.any { it.kind == BackupIssueKind.NO_SERVER_ADDRESS } }
                .issues.map { it.kind }
        )

        ip.value = "127.0.0.1"
        dao.resetStatus(MediaFileStatus.PENDING_UPLOAD, MediaFileStatus.SYNCED)
        assertEquals(BackupPhase.SYNCED, monitor.awaitState { it.phase == BackupPhase.SYNCED }.phase)

        val noAccess = monitor.awaitState(hasMediaAccess = false) { true }
        assertEquals(listOf(BackupIssueKind.NO_MEDIA_PERMISSION), noAccess.issues.map { it.kind })
    }

    private fun pendingUpload(id: Long) = MediaFile(
        mediaStoreId = id,
        uri = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/",
        mimeType = "image/jpeg",
        size = 100,
        createdAt = id,
        lastModified = id,
        checksum = "a".repeat(64),
        status = MediaFileStatus.PENDING_UPLOAD
    )
}

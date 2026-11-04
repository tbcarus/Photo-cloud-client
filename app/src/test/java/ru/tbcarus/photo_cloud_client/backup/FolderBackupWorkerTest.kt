package ru.tbcarus.photo_cloud_client.backup

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.SyncClock
import ru.tbcarus.photo_cloud_client.media.WorkBudget
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Настоящий FolderBackupWorker на WorkManager 2.9 со сценарным движком: продолжение, retry, KEEP и пересечение
 * periodic с разовым прогоном. Движок, как и боевой, не допускает двух прогонов одновременно.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FolderBackupWorkerTest {

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var scheduler: FolderBackupScheduler
    private lateinit var engine: ScriptedEngine
    private val workManager get() = WorkManager.getInstance(context)

    /** Прогоны движка: признак полного обхода в порядке запусков. */
    private val calls = CopyOnWriteArrayList<Boolean>()
    private val busy = java.util.concurrent.atomic.AtomicInteger()

    private inner class ScriptedEngine(context: Context, db: AppDatabase) : FolderBackupEngine(
        db.backupSourceDao(), db.backupTreeDao(),
        FolderBackupRemote(ApiServiceFactory(BaseUrlProvider(mock(ServerPreferences::class.java)), OkHttpClient(), OkHttpClient(), OkHttpClient())),
        FakeTree(context, "Docs"), FakeAccess(context), FakeAccounts(null)
    ) {
        val steps = ConcurrentLinkedQueue<suspend () -> FolderBackupRun>()
        private val lock = Mutex()

        override suspend fun run(budget: WorkBudget, fullScan: Boolean): FolderBackupRun? {
            if (!lock.tryLock()) {
                busy.incrementAndGet()
                return null
            }
            try {
                calls += fullScan
                val step = steps.poll() ?: return FolderBackupRun(progress = false, remaining = false, retry = false)
                return step()
            } finally {
                lock.unlock()
            }
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        engine = ScriptedEngine(context, db)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == FolderBackupWorker::class.java.name) {
                    FolderBackupWorker(appContext, workerParameters, engine, scheduler, SyncClock.SYSTEM)
                } else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        )
        scheduler = FolderBackupScheduler(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun continuationsFinishQueueAndChainEnds() = runBlocking {
        repeat(3) { engine.steps += { FolderBackupRun(progress = true, remaining = true, retry = false) } }
        engine.steps += { FolderBackupRun(progress = true, remaining = false, retry = false) }

        scheduler.enqueue()
        awaitUntil { calls.size == 4 && oneTime().all { it.state.isFinished } }

        // Первый прогон начинает обход, продолжения только дочитывают.
        assertEquals(listOf(true, false, false, false), calls)
        assertTrue(oneTime().all { it.state == WorkInfo.State.SUCCEEDED })
        Thread.sleep(200)
        assertEquals(4, calls.size)
    }

    @Test
    fun noProgressMeansNoContinuation() = runBlocking {
        engine.steps += { FolderBackupRun(progress = false, remaining = true, retry = false) }

        scheduler.enqueue()
        awaitUntil { oneTime().all { it.state.isFinished } && calls.size == 1 }

        Thread.sleep(200)
        assertEquals(1, calls.size)
        assertEquals(1, oneTime().size)
    }

    @Test
    fun temporaryErrorUsesBackoffWithoutContinuation() = runBlocking {
        engine.steps += { FolderBackupRun(progress = true, remaining = true, retry = true) }

        scheduler.enqueue()
        awaitUntil { calls.size == 1 && oneTime().single().runAttemptCount == 1 && oneTime().single().state == WorkInfo.State.ENQUEUED }

        assertEquals(1, oneTime().size)
    }

    @Test
    fun externalRequestsKeepRunningWorkAndPeriodicMeetsBusyEngine() = runBlocking {
        val release = CompletableDeferred<Unit>()
        engine.steps += {
            release.await()
            FolderBackupRun(progress = true, remaining = false, retry = false)
        }
        scheduler.enqueue()
        awaitUntil { oneTime().singleOrNull()?.state == WorkInfo.State.RUNNING }

        repeat(3) { scheduler.enqueue() }
        assertEquals(1, oneTime().size)

        // Periodic в это время застаёт занятый движок и завершается, ничего не ставя.
        scheduler.enqueuePeriodic()
        val periodicId = periodic().single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(periodicId)
        awaitUntil { busy.get() == 1 && periodic().single().state == WorkInfo.State.ENQUEUED }
        assertEquals(1, oneTime().size)

        release.complete(Unit)
        awaitUntil { oneTime().all { it.state.isFinished } }
        assertEquals(listOf(true), calls)
    }

    @Test
    fun triggerFollowsAccountAndActiveSources() = runBlocking {
        val account = BackupAccount("http://10.0.2.2:8080/", "a@test.local")
        val trigger = FolderBackupTrigger(FakeAccounts(account), db.backupSourceDao(), scheduler)

        trigger.reconcile(account)
        assertTrue(periodic().isEmpty())
        assertTrue(oneTime().isEmpty())

        db.backupSourceDao().insert(
            BackupSource(
                sourceUuid = "u", treeUri = "content://x/tree/a", localName = "a", serverName = "a", serverRootFolderId = 5,
                serverUrl = account.serverUrl, accountId = account.accountId, registrationState = RegistrationState.ACTIVE,
                registrationError = null, accessState = SourceAccess.AVAILABLE, createdAt = 1
            )
        )
        trigger.reconcile(account)
        assertEquals(WorkInfo.State.ENQUEUED, periodic().single().state)
        assertEquals(1, oneTime().size)

        trigger.reconcile(null)
        assertEquals(WorkInfo.State.CANCELLED, periodic().single().state)
    }

    private fun oneTime(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(FolderBackupScheduler.WORK_NAME).get()

    private fun periodic(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(FolderBackupScheduler.PERIODIC_WORK_NAME).get()

    // Сеть в тесте не отслеживается: условие CONNECTED снимается для каждой новой разовой работы.
    private fun awaitUntil(condition: () -> Boolean) {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            oneTime().filter { it.state == WorkInfo.State.ENQUEUED }.forEach { driver.setAllConstraintsMet(it.id) }
            Thread.sleep(20)
        }
        assertTrue("Условие не выполнено за отведённое время", condition())
    }
}

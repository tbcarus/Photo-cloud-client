package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.background.systemjob.SystemJobScheduler
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.sha256
import java.util.UUID

/**
 * Наблюдатель MediaStore через WorkManager Test APIs. Срабатывание ContentUriTrigger моделируется
 * TestDriver.setAllConstraintsMet: системное уведомление MediaStore → JobScheduler здесь не воспроизводится.
 * Без сети, адреса Server и пары (как после logout), если тест не задаёт иное.
 * SDK задан явно: по умолчанию Robolectric здесь работает на API 21, где ContentUriTrigger (API 24+)
 * отбрасывается Constraints.Builder, и наблюдатель без триггера запускался бы сразу.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MediaTriggerWorkerTest {

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private lateinit var scheduler: SyncScheduler
    private lateinit var coordinator: PeriodicSyncCoordinator
    private val storage = InMemoryTokenStorage()
    private val workManager get() = WorkManager.getInstance(context)
    private val dao get() = db.mediaFileDao()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        media = FakeMediaStore.install(context)

        val baseUrlProvider = BaseUrlProvider(ServerPreferences(context))
        val client = OkHttpClient()
        val syncStatusStore = SyncStatusStore()
        val mediaFileRepository = MediaFileRepository(dao, MediaStoreRepository(context))
        val checksumRepository = ChecksumRepository(dao, MediaStreamOpener(context), context)
        scheduler = SyncScheduler(context, syncStatusStore)
        coordinator = PeriodicSyncCoordinator(
            context,
            AuthRepository(storage, baseUrlProvider, ApiServiceFactory(baseUrlProvider, client, client, client)),
            scheduler,
            MediaChangeObserver(context, scheduler)
        )
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? = when (workerClassName) {
                MediaTriggerWorker::class.java.name -> MediaTriggerWorker(appContext, workerParameters, scheduler)
                ProviderWorker::class.java.name -> ProviderWorker(
                    appContext, workerParameters, mediaFileRepository, checksumRepository, syncStatusStore, coordinator,
                    scheduler, SyncClock.SYSTEM
                )
                else -> null // Загрузчик здесь не выполняется: без пары он и не ставится.
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
    fun fullAccessRegistersSingleWatcherAndReconcileDoesNotDuplicateIt() {
        grantFullAccess()

        coordinator.reconcile()
        val first = pendingWatcher()
        coordinator.reconcile()
        coordinator.reconcile()

        assertEquals(first.id, pendingWatcher().id)
        assertEquals(1, watchers().size)
        val constraints = first.constraints
        val trigger = constraints.contentUriTriggers.single()
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, trigger.uri)
        assertTrue(trigger.isTriggeredForDescendants)
        assertEquals(3_000L, constraints.contentTriggerUpdateDelayMillis)
        assertEquals(30_000L, constraints.contentTriggerMaxDelayMillis)
        // Ни сети, ни заряда, ни других условий: от Server и входа наблюдение не зависит.
        assertEquals(NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresBatteryNotLow())
    }

    @Test
    fun workManagerSchedulesJobWithMediaStoreContentTrigger() {
        grantFullAccess()
        coordinator.reconcile()
        val watcherId = pendingWatcher().id

        // Тот же планировщик, что WorkManager использует на устройстве: WorkSpec → JobInfo → JobScheduler.
        val impl = WorkManagerImpl.getInstance(context)
        val spec = impl.workDatabase.workSpecDao().getWorkSpec(watcherId.toString())!!
        SystemJobScheduler(context, impl.workDatabase, impl.configuration).schedule(spec)

        val job = context.getSystemService(JobScheduler::class.java).allPendingJobs.single()
        val uri = job.triggerContentUris!!.single()
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, uri.uri)
        assertEquals(JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS, uri.flags)
        assertEquals(3_000L, job.triggerContentUpdateDelay)
        assertEquals(30_000L, job.triggerContentMaxDelay)
        assertNull(job.requiredNetwork) // сеть не требуется
        assertFalse(job.isPersisted)
    }

    @Test
    fun eachEventStartsProviderAndLeavesExactlyOneNewWatcher() = runBlocking {
        grantFullAccess()
        coordinator.reconcile()

        val seen = mutableSetOf<UUID>()
        for (n in 1L..3L) {
            val image = media.add(image(n, "photo-$n"))
            val watcher = pendingWatcher().id
            seen += watcher

            fireAndAwaitProvider(watcher)

            // Событие обработано Поставщиком без сети, адреса Server и пары.
            assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(image.id).status)
            assertEquals(sha256(image.bytes), dao.single(image.id).checksum)
            // Под именем наблюдателя ровно одно задание — новое ожидание, без цепочек и BLOCKED.
            val all = watchers()
            assertEquals(1, all.size)
            assertEquals(WorkInfo.State.ENQUEUED, all.single().state)
            assertFalse(all.single().id in seen)
            assertNull(workManager.getWorkInfoById(watcher).get())
        }
        assertTrue(infos(SyncScheduler.UPLOAD_WORK_NAME).isEmpty())
        assertTrue(allInfos().none { it.state == WorkInfo.State.BLOCKED })
    }

    @Test
    fun eventDuringRunningProviderDoesNotStartSecondProvider() = runBlocking {
        grantFullAccess()
        coordinator.reconcile()
        val image = media.add(image(1, "photo-1"))
        media.queryDelayMs = 1_000
        scheduler.enqueueProvider()
        val running = awaitProvider { it.state == WorkInfo.State.RUNNING }

        fire(pendingWatcher().id)

        // Новое ожидание уже стоит, пока Поставщик ещё работает: его ставит сам наблюдатель, а не Поставщик.
        assertEquals(WorkInfo.State.RUNNING, workManager.getWorkInfoById(running.id).get()!!.state)
        assertEquals(WorkInfo.State.ENQUEUED, watchers().single().state)
        // KEEP: выполняющийся Поставщик не заменён и не продублирован.
        assertEquals(listOf(running.id), infos(SyncScheduler.PROVIDER_WORK_NAME).map { it.id })
        awaitUntil { workManager.getWorkInfoById(running.id).get()!!.state.isFinished }
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(running.id).get()!!.state)
        assertEquals(1, media.maxConcurrentQueries)
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(image.id).status)
    }

    @Test
    fun changedAndDeletedPhotosAreProcessedAfterEvent() = runBlocking {
        grantFullAccess()
        coordinator.reconcile()
        val changed = media.add(image(1, "old content"))
        val deleted = media.add(image(2, "deleted"))
        fireAndAwaitProvider(pendingWatcher().id)

        changed.bytes = "new and longer content".toByteArray()
        changed.dateModifiedSec += 60
        media.remove(deleted.id)
        fireAndAwaitProvider(pendingWatcher().id)

        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(changed.id).status)
        assertEquals(sha256(changed.bytes), dao.single(changed.id).checksum)
        assertNull(dao.find(deleted.id))
        assertEquals(1, watchers().size)
    }

    @Test
    fun watchingContinuesAfterLogout() {
        grantFullAccess()
        storage.saveTokens(Tokens("access", "refresh"))
        coordinator.reconcile()
        val watcher = pendingWatcher().id

        storage.clear()
        coordinator.reconcile()

        assertEquals(watcher, pendingWatcher().id)
    }

    @Test
    fun withoutFullAccessReconcileRemovesWatcher() {
        grantFullAccess()
        coordinator.reconcile()

        shadowOf(context).denyPermissions(mediaReadPermission)
        coordinator.reconcile()

        assertTrue(watchers().none { !it.state.isFinished })
    }

    @Test
    fun eventWithoutFullAccessDoesNotProcessPhotosAndStopsWatching() = runBlocking {
        grantFullAccess()
        coordinator.reconcile()
        media.add(image(1, "photo-1"))
        val watcher = pendingWatcher().id
        // Разрешение отозвано, а ожидание осталось (UI ещё не открывался).
        shadowOf(context).denyPermissions(mediaReadPermission)

        fire(watcher)

        assertTrue(infos(SyncScheduler.PROVIDER_WORK_NAME).isEmpty())
        assertTrue(watchers().none { !it.state.isFinished })
        assertNull(dao.find(1))
        assertEquals(0, media.queryCount)
    }

    @Test
    fun restoredFullAccessResumesWatching() {
        grantFullAccess()
        coordinator.reconcile()
        val old = pendingWatcher().id
        shadowOf(context).denyPermissions(mediaReadPermission)
        coordinator.reconcile()

        grantFullAccess()
        coordinator.reconcile()

        val restored = pendingWatcher()
        assertNotEquals(old, restored.id)
        assertEquals(1, watchers().size)
    }

    @Test
    fun periodicProviderRestoresMissingWatcher() {
        grantFullAccess()
        coordinator.reconcile()
        scheduler.cancelMediaTrigger()
        assertTrue(watchers().none { !it.state.isFinished })

        val periodic = infos(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).single().id
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(periodic)
        driver.setPeriodDelayMet(periodic)

        awaitUntil { watchers().any { it.state == WorkInfo.State.ENQUEUED } }
        assertEquals(1, watchers().count { !it.state.isFinished })
        // Восстановил именно прогон periodic Поставщика: он же выполнил scan.
        awaitUntil { media.queryCount >= 1 }
        assertTrue(media.queryCount >= 1)
    }

    private fun grantFullAccess() = shadowOf(context).grantPermissions(mediaReadPermission)

    private fun image(id: Long, content: String) =
        FakeMediaStore.Image(id = id, bytes = content.toByteArray(), dateModifiedSec = 1_700_000_000L + id)

    /** Срабатывание ContentUriTrigger: ждём, пока задание наблюдателя завершится или будет заменено. */
    private fun fire(watcher: UUID) {
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(watcher)
        awaitUntil { workManager.getWorkInfoById(watcher).get()?.state?.isFinished ?: true }
    }

    private fun awaitProvider(condition: (WorkInfo) -> Boolean): WorkInfo {
        awaitUntil { infos(SyncScheduler.PROVIDER_WORK_NAME).any(condition) }
        return infos(SyncScheduler.PROVIDER_WORK_NAME).first(condition)
    }

    /** Событие и вызванный им новый прогон Поставщика до завершения. */
    private fun fireAndAwaitProvider(watcher: UUID) {
        val before = infos(SyncScheduler.PROVIDER_WORK_NAME).map { it.id }.toSet()
        fire(watcher)
        val provider = awaitProvider { it.id !in before }
        awaitUntil { workManager.getWorkInfoById(provider.id).get()!!.state.isFinished }
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(provider.id).get()!!.state)
    }

    // Workers выполняются на фоновых потоках, enqueue асинхронен — ждём опросом с ограничением по времени.
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    private fun watchers(): List<WorkInfo> = infos(SyncScheduler.MEDIA_TRIGGER_WORK_NAME)

    private fun pendingWatcher(): WorkInfo {
        awaitUntil { watchers().any { it.state == WorkInfo.State.ENQUEUED } }
        return watchers().single { it.state == WorkInfo.State.ENQUEUED }
    }

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun allInfos(): List<WorkInfo> = listOf(
        SyncScheduler.MEDIA_TRIGGER_WORK_NAME, SyncScheduler.PROVIDER_WORK_NAME,
        SyncScheduler.PROVIDER_PERIODIC_WORK_NAME, SyncScheduler.UPLOAD_WORK_NAME
    ).flatMap { infos(it) }
}

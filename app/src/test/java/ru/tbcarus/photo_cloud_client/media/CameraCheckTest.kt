package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.awaitAll
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.auth.AuthRepository
import ru.tbcarus.photo_cloud_client.backup.BackupAccountProvider
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.server.ServerRepository
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.row
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.sha256
import java.io.FileNotFoundException
import java.net.InetAddress
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Ручная сверка Camera (Q-01) через настоящие Поставщик и Загрузчик WorkManager: MediaStore — FakeMediaStore,
 * Server — MockWebServer, Room — файл БД (переживает «перезапуск процесса» в тесте рестарта).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CameraCheckTest {

    private companion object {
        const val DB_NAME = "camera-check-test.db"
        const val CAMERA_ID = 2L
    }

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private lateinit var scheduler: SyncScheduler
    private lateinit var check: CameraCheckRepository
    private lateinit var baseUrlProvider: BaseUrlProvider
    private lateinit var api: ApiServiceFactory
    private val storage = InMemoryTokenStorage(Tokens(accessTokenFor("user-a"), "refresh"))
    private val workManager get() = WorkManager.getInstance(context)
    private val dao get() = db.mediaFileDao()

    // Server: checksum Camera, которые уже есть; upload добавляет checksum отправленного файла.
    private val existing: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val checksumRequests = CopyOnWriteArrayList<List<String>>()
    private val uploads = CopyOnWriteArrayList<Long>()
    private val requests = AtomicInteger()
    private val nextFileId = AtomicLong(100)
    @Volatile private var down = false
    /** Ответ на n-й (с 1) запрос checksum-exists вместо обычного; null — обычный. */
    @Volatile private var onChecksums: (Int, List<String>) -> MockResponse? = { _, _ -> null }
    @Volatile private var onUpload: (Long) -> Unit = {}
    private val images = HashMap<Long, FakeMediaStore.Image>()
    private val released = HashSet<UUID>()
    // Часы прогонов: сдвиг на бюджет исчерпывает его у уже начатых прогонов, новые отсчитывают свой заново.
    private val clockShift = AtomicLong()
    private val clock = SyncClock { System.nanoTime() / 1_000_000 + clockShift.get() }
    // Одновременные запросы checksum-exists — для проверки, что серверная стадия не идёт параллельно.
    private val checksumsInFlight = AtomicInteger()
    private val maxChecksumsInFlight = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        media = FakeMediaStore.install(context)
        shadowOf(context).grantPermissions(mediaReadPermission)
        val serverPreferences = ServerPreferences(context)
        runBlocking { serverPreferences.saveConnection("127.0.0.1", server.port.toString()) }
        baseUrlProvider = BaseUrlProvider(serverPreferences)
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        api = ApiServiceFactory(baseUrlProvider, client, client, client)
        scheduler = SyncScheduler(context)
        openDatabase()
        initWorkManager()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    /** Открытие БД и репозитория — как при старте процесса: состояние берётся только из Room. */
    private fun openDatabase() {
        db = AppDatabase.build(context, DB_NAME)
        check = CameraCheckRepository(
            context, db, api, MediaStreamOpener(context), BackupAccountProvider(baseUrlProvider, storage), scheduler, storage
        )
        // В приложении это @Singleton: их Mutex общие для всех прогонов Загрузчика процесса.
        precheckRepository = ChecksumPrecheckRepository(db.mediaFileDao(), api)
        uploadRepository = FileUploadRepository(db.mediaFileDao(), api, MediaStreamOpener(context))
    }

    private lateinit var precheckRepository: ChecksumPrecheckRepository
    private lateinit var uploadRepository: FileUploadRepository

    private fun initWorkManager() {
        val coordinator = PeriodicSyncCoordinator(
            context, AuthRepository(storage, baseUrlProvider, api), scheduler, MediaChangeObserver(context, scheduler)
        )
        val syncStatusStore = testSyncStatusStore()
        val factory = object : WorkerFactory() {
            // Экземпляры берутся при создании Worker: после «перезапуска» — уже новые.
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                when (workerClassName) {
                    ProviderWorker::class.java.name -> ProviderWorker(
                        appContext, workerParameters, MediaFileRepository(dao, MediaStoreRepository(appContext)),
                        ChecksumRepository(dao, MediaStreamOpener(appContext), appContext), syncStatusStore, coordinator,
                        scheduler, clock, check
                    )
                    UploadWorker::class.java.name -> UploadWorker(
                        appContext, workerParameters, FolderRepository(api), precheckRepository,
                        uploadRepository, ServerRepository(api),
                        baseUrlProvider, syncStatusStore, storage, coordinator, scheduler, clock, check
                    )
                    else -> null
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        )
    }

    // --- 1, 4, 5: смешанные статусы, частичное совпадение, FAILED с checksum и без ---

    @Test
    fun mixedStatusesAreCheckedWithoutDowngradeOrUpload() = runBlocking {
        val syncedHere = file(1, MediaFileStatus.SYNCED, onServer = true)
        val syncedMissing = file(2, MediaFileStatus.SYNCED)
        val readyHere = file(3, MediaFileStatus.CHECKSUM_READY, onServer = true)
        val fresh = file(4, MediaFileStatus.PENDING)
        val failedUpload = file(5, MediaFileStatus.FAILED, failure = FailureReason.HTTP_413, retries = 1)
        val failedHashHere = file(6, MediaFileStatus.FAILED, failure = FailureReason.CHECKSUM_IO, retries = 3, withChecksum = false, onServer = true)
        val unreadable = file(7, MediaFileStatus.FAILED, failure = FailureReason.FILE_NOT_FOUND, retries = 1, withChecksum = false)
        unreadable.openFailure = { FileNotFoundException("gone") }
        val waiting = file(8, MediaFileStatus.PENDING_UPLOAD)
        val failedBefore = listOf(5L, 6L, 7L).associateWith { dao.single(it) }

        assertEquals(CheckStartResult.Started(scheduled = true), check.start())
        drain()

        val view = view()!!
        assertEquals(CameraCheckState.AWAITING_DECISION, view.check.state)
        assertEquals(8, view.counts.total)
        assertEquals(3, view.counts.confirmed)
        assertEquals(4, view.counts.missing)
        assertEquals(1, view.counts.unverifiable)
        assertEquals(setOf(2L, 4L, 5L, 8L), db.cameraCheckDao().missingItems().map { it.mediaStoreId }.toSet())
        // До решения ничего не отправлено и SYNCED не понижены.
        assertTrue(uploads.isEmpty())
        assertEquals(MediaFileStatus.SYNCED, dao.single(syncedHere.id).status)
        assertEquals(MediaFileStatus.SYNCED, dao.single(syncedMissing.id).status)
        // existing для ещё не проверенной строки подтверждается как у обычного pre-check.
        assertEquals(MediaFileStatus.SYNCED, dao.single(readyHere.id).status)
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(fresh.id).status)
        assertEquals(MediaFileStatus.PENDING_UPLOAD, dao.single(waiting.id).status)
        // FAILED: статус, причина и счётчик попыток не тронуты; checksum файла без него получен сверкой.
        failedBefore.forEach { (id, before) -> assertEquals(before, dao.single(id)) }
        assertTrue(checksumRequests.flatten().contains(sha256(failedHashHere.bytes)))
        assertFalse(checksumRequests.flatten().contains(sha256(unreadable.bytes)))
        assertEquals(sha256(failedUpload.bytes), db.cameraCheckDao().missingItems().single { it.mediaStoreId == 5L }.checksum)
        assertTrue(check.isUploadPaused())
    }

    // --- 2: всё есть на Server ---

    @Test
    fun allPresentFinishesAndLiftsPauseWithoutDecision() = runBlocking {
        (1L..3L).forEach { file(it, MediaFileStatus.SYNCED, onServer = true) }

        check.start()
        drain()

        val view = view()!!
        assertEquals(CameraCheckState.FINISHED, view.check.state)
        assertEquals(3, view.check.checked)
        assertEquals(3, view.check.confirmed)
        assertEquals(0, view.check.missing)
        assertNull(view.check.queuedForUpload)
        assertEquals(0, view.counts.total) // набор удалён, итог — в строке проверки
        assertFalse(check.isUploadPaused())
        assertTrue(uploads.isEmpty())
    }

    // --- 3: всего нет на Server, подтверждение загружает всё ---

    @Test
    fun allMissingAreUploadedOnlyAfterConfirmation() = runBlocking {
        (1L..3L).forEach { file(it, MediaFileStatus.SYNCED) }

        check.start()
        drain()
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
        assertTrue(uploads.isEmpty())

        val result = check.confirmUpload()
        assertEquals(ConfirmUploadResult.Queued(3, scheduled = true), result)
        drain()

        assertEquals(listOf(1L, 2L, 3L), uploads.sorted())
        (1L..3L).forEach { assertEquals(MediaFileStatus.SYNCED, dao.single(it).status) }
        assertEquals(CameraCheckState.FINISHED, view()!!.check.state)
        assertEquals(3, view()!!.check.queuedForUpload)
        assertFalse(check.isUploadPaused())
    }

    // --- 14, 18: подтверждение берёт только актуальные отсутствующие файлы ---

    @Test
    fun confirmationQueuesOnlyCurrentMissingFiles() = runBlocking {
        file(1, MediaFileStatus.SYNCED)                      // отсутствует — будет отправлен
        val changed = file(2, MediaFileStatus.SYNCED)        // изменится после проверки
        file(3, MediaFileStatus.SYNCED, onServer = true)     // есть — не трогается
        val appeared = file(4, MediaFileStatus.SYNCED)       // появится на Server до подтверждения
        val failed = file(5, MediaFileStatus.FAILED, failure = FailureReason.HTTP_413, retries = 2)
        check.start()
        drain()
        assertEquals(setOf(1L, 2L, 4L, 5L), db.cameraCheckDao().missingItems().map { it.mediaStoreId }.toSet())

        // Файл изменён на устройстве, и scan уже увидел это; другой файл тем временем загрузили на Server.
        changed.bytes = "changed content".toByteArray()
        changed.dateModifiedSec += 60
        MediaFileRepository(dao, MediaStoreRepository(context)).scanImages()
        existing += sha256(appeared.bytes)

        assertEquals(ConfirmUploadResult.Queued(3, scheduled = true), check.confirmUpload())
        // Выбранная FAILED-строка — ручной повтор: причина и счётчик сброшены, checksum считается заново
        // (Поставщик мог уже начать).
        val retried = dao.single(failed.id)
        assertTrue(retried.status in setOf(MediaFileStatus.PENDING, MediaFileStatus.HASHING, MediaFileStatus.CHECKSUM_READY))
        assertEquals(0, retried.retryCount)
        assertNull(retried.failureReason)
        drain()

        // Изменённый файл прошёл обычный путь с новым checksum; появившийся на Server не отправлен повторно.
        assertEquals(listOf(1L, 2L, 5L), uploads.sorted())
        assertEquals(sha256(changed.bytes), dao.single(changed.id).checksum)
        (1L..5L).forEach { assertEquals(MediaFileStatus.SYNCED, dao.single(it).status) }
    }

    @Test
    fun fileChangedDuringServerRequestIsUnverifiedNotMissing() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        val changing = file(2, MediaFileStatus.SYNCED)
        onChecksums = { _, _ ->
            // Scan применил новую версию файла, пока шёл запрос: ответ относится к прежнему содержимому.
            runBlocking { dao.applyScan(dao.single(changing.id), size = 999, lastModified = 1) }
            null
        }

        check.start()
        drain()

        val view = view()!!
        assertEquals(1, view.counts.missing)
        assertEquals(1, view.counts.unverifiable)
        assertEquals(listOf(1L), db.cameraCheckDao().missingItems().map { it.mediaStoreId })
    }

    // --- 6, 7: условия запуска ---

    @Test
    fun startNeedsPermissionSessionAndServer() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        val providersBefore = ids(SyncScheduler.PROVIDER_WORK_NAME)

        shadowOf(context).denyPermissions(mediaReadPermission)
        assertEquals(CheckStartResult.NoPermission, check.start())
        shadowOf(context).grantPermissions(mediaReadPermission)

        storage.clear()
        assertEquals(CheckStartResult.NotSignedIn, check.start())
        storage.saveTokens(Tokens(accessTokenFor("user-a"), "refresh"))

        ServerPreferences(context).saveConnection("", "")
        assertEquals(CheckStartResult.NoServer, check.start())

        assertNull(view())
        assertFalse(check.isUploadPaused())
        assertEquals(providersBefore, ids(SyncScheduler.PROVIDER_WORK_NAME))
    }

    // --- 8: отмена на каждой стадии ---

    @Test
    fun cancelDuringScanLeavesNoTrace() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        val ready = file(2, MediaFileStatus.CHECKSUM_READY)
        media.beforeQuery = { runBlocking { check.cancel() } }

        check.start()
        drain()

        assertNull(view())
        assertEquals(0, db.cameraCheckDao().counts().total)
        assertEquals(MediaFileStatus.SYNCED, dao.single(1).status)
        // Обычная синхронизация возобновлена: ожидающий файл отправлен, SYNCED — нет.
        assertEquals(listOf(ready.id), uploads.toList())
    }

    @Test
    fun cancelDuringHashLeavesNoTrace() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        val fresh = file(2, MediaFileStatus.PENDING)
        media.onOpen = { id -> if (id == fresh.id) runBlocking { check.cancel() } }

        check.start()
        drain()

        assertNull(view())
        assertEquals(MediaFileStatus.SYNCED, dao.single(1).status)
        // Хеширование — обычная работа Поставщика: оно завершено, файл ушёл обычной синхронизацией.
        assertEquals(MediaFileStatus.SYNCED, dao.single(fresh.id).status)
        assertEquals(listOf(fresh.id), uploads.toList())
    }

    @Test
    fun cancelDuringServerRequestDropsLateAnswer() = runBlocking {
        (1L..3L).forEach { file(it, MediaFileStatus.SYNCED) }
        val cancelled = CopyOnWriteArrayList<Boolean>()
        onChecksums = { _, _ ->
            cancelled += runBlocking { check.cancel() }
            cancelled += runBlocking { check.cancel() } // двойное нажатие
            null
        }

        check.start()
        drain()

        assertEquals(listOf(true, false), cancelled.toList())
        assertNull(view())
        assertEquals(0, db.cameraCheckDao().counts().total)
        // Поздний ответ missing не применён: SYNCED сохранены, ложных PENDING_UPLOAD и FAILED нет.
        (1L..3L).forEach { assertEquals(MediaFileStatus.SYNCED, dao.single(it).status) }
        assertTrue(uploads.isEmpty())
        assertFalse(check.isUploadPaused())
    }

    // --- 9, 10: пауза блокирует новые upload, начатый не прерывается ---

    @Test
    fun pauseStopsNextFileButLetsStartedUploadFinish() = runBlocking {
        (1L..3L).forEach { file(it, MediaFileStatus.PENDING_UPLOAD) }
        val started = CopyOnWriteArrayList<CheckStartResult>()
        onUpload = { id -> if (id == 1L) started += runBlocking { check.start() } }

        scheduler.enqueueUpload()
        drain()

        assertEquals(listOf<CheckStartResult>(CheckStartResult.Started(scheduled = true)), started.toList())
        // Файл 1 отправлялся в момент запуска — дошёл до SYNCED; следующий файл не взят.
        assertEquals(listOf(1L), uploads.toList())
        assertEquals(MediaFileStatus.SYNCED, dao.single(1).status)
        assertEquals(MediaFileStatus.PENDING_UPLOAD, dao.single(2).status)
        val view = view()!!
        assertEquals(CameraCheckState.AWAITING_DECISION, view.check.state)
        assertEquals(1, view.counts.confirmed)
        assertEquals(2, view.counts.missing)

        // Любой новый запуск Загрузчика на паузе ничего не отправляет и к Server не обращается.
        val requestsBefore = requests.get()
        scheduler.enqueueUpload()
        drain()
        assertEquals(requestsBefore, requests.get())
        assertEquals(listOf(1L), uploads.toList())
    }

    // --- 11, 12, 13: «Позже», рестарт, «Отказаться от проверки» ---

    @Test
    fun uploaderSignalDuringPauseDoesNotUploadCameraFiles() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        val ready = file(2, MediaFileStatus.CHECKSUM_READY)
        check.start()
        drain()
        assertTrue(check.defer())
        assertTrue(check.isUploadPaused())

        // Прогон Поставщика и прямой сигнал Загрузчику (Q-02.1): запуск Загрузчика на паузе ничего не отправляет.
        scheduler.enqueueProvider()
        scheduler.signalUpload()
        drain()

        assertTrue(uploads.isEmpty())
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(ready.id).status)
        assertTrue(check.isUploadPaused())
    }

    @Test
    fun laterKeepsResultAndPauseAcrossRestartUntilDiscarded() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        val ready = file(2, MediaFileStatus.CHECKSUM_READY)
        check.start()
        drain()
        assertTrue(check.defer())

        // Перезапуск процесса: новая БД-сессия и репозиторий, состояние — только из Room.
        db.close()
        openDatabase()
        val restored = view()!!
        assertEquals(CameraCheckState.AWAITING_DECISION, restored.check.state)
        assertTrue(restored.check.deferred)
        assertEquals(2, restored.counts.missing)
        assertTrue(check.isUploadPaused())
        ProcessStartSync(context, scheduler).onProcessStart()
        scheduler.enqueueUpload()
        drain()
        assertTrue(uploads.isEmpty())

        // «Отказаться от проверки»: результат удалён, пауза снята, обычная синхронизация продолжает свою очередь.
        assertTrue(check.cancel())
        drain()
        assertNull(view())
        assertFalse(check.isUploadPaused())
        assertEquals(listOf(ready.id), uploads.toList())
        assertEquals(MediaFileStatus.SYNCED, dao.single(1).status)
    }

    @Test
    fun interruptedCheckContinuesAfterRestart() = runBlocking {
        (1L..2L).forEach { file(it, MediaFileStatus.SYNCED) }
        down = true
        check.start()
        drain()
        assertEquals(CameraCheckError.SERVER_UNAVAILABLE, view()!!.check.error)

        // Процесс погиб во время backoff: при старте Поставщик снова зовёт Загрузчика для серверной стадии.
        db.close()
        openDatabase()
        down = false
        workManager.cancelAllWork().result.get()
        ProcessStartSync(context, scheduler).onProcessStart()
        drain()

        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
        assertEquals(2, view()!!.counts.missing)
    }

    // --- 15, 16: недоступный Server и ошибка в середине партий ---

    @Test
    fun unavailableServerKeepsSyncedAndReportsError() = runBlocking {
        (1L..3L).forEach { file(it, MediaFileStatus.SYNCED) }
        down = true

        check.start()
        drain()

        val view = view()!!
        assertEquals(CameraCheckState.ERROR, view.check.state)
        assertEquals(CameraCheckStage.SERVER, view.check.stage)
        assertEquals(CameraCheckError.SERVER_UNAVAILABLE, view.check.error)
        assertEquals(0, view.counts.missing)
        (1L..3L).forEach { assertEquals(MediaFileStatus.SYNCED, dao.single(it).status) }
        assertTrue(check.isUploadPaused())
    }

    @Test
    fun failedSecondBatchResumesWithoutRepeatingFirst() = runBlocking {
        val total = SyncConfig.PRECHECK_BATCH_SIZE + 1L
        (1L..total).forEach { file(it, MediaFileStatus.SYNCED, onServer = it % 2 == 0L) }
        onChecksums = { n, _ -> if (n == 2) MockResponse().setResponseCode(503) else null }

        check.start()
        drain()

        val failed = view()!!
        assertEquals(CameraCheckError.SERVER_ERROR, failed.check.error)
        assertEquals(SyncConfig.PRECHECK_BATCH_SIZE, failed.counts.answered)
        (1L..total).forEach { assertEquals(MediaFileStatus.SYNCED, dao.single(it).status) }

        // Повтор Загрузчика после backoff продолжает с неотвеченной партии.
        val retrying = backoffUpload()
        WorkManagerTestInitHelper.getTestDriver(context)!!.apply {
            setInitialDelayMet(retrying.id)
            setAllConstraintsMet(retrying.id)
        }
        awaitUntil { workManager.getWorkInfoById(retrying.id).get()!!.state.isFinished }
        drain()

        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(retrying.id).get()!!.state)
        assertEquals(3, checksumRequests.size)
        assertEquals(1, checksumRequests.last().size)
        assertEquals(SyncConfig.PRECHECK_BATCH_SIZE + 1, checksumRequests.flatten().toSet().size)
        val done = view()!!
        assertEquals(CameraCheckState.AWAITING_DECISION, done.check.state)
        assertEquals(total.toInt(), done.check.checked)
        assertEquals((total / 2).toInt(), done.check.confirmed)
        assertEquals((total - total / 2).toInt(), done.check.missing)
    }

    // --- 17: смена аккаунта во время проверки ---

    @Test
    fun answerForAnotherAccountIsRejected() = runBlocking {
        (1L..2L).forEach { file(it, MediaFileStatus.SYNCED) }
        onChecksums = { n, _ ->
            if (n == 1) storage.saveTokens(Tokens(accessTokenFor("user-b"), "refresh"))
            null
        }

        check.start()
        drain()

        val view = view()!!
        assertEquals(CameraCheckError.DESTINATION_CHANGED, view.check.error)
        assertEquals(0, view.counts.answered)
        assertEquals(ConfirmUploadResult.NotAwaiting, check.confirmUpload())

        // Возврат прежнего аккаунта: сверка продолжается для своего назначения.
        storage.saveTokens(Tokens(accessTokenFor("user-a"), "refresh"))
        scheduler.enqueueUpload()
        drain()
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)

        // Решение под другим аккаунтом не применяется.
        storage.saveTokens(Tokens(accessTokenFor("user-b"), "refresh"))
        assertEquals(ConfirmUploadResult.DestinationChanged, check.confirmUpload())
        (1L..2L).forEach { assertEquals(MediaFileStatus.SYNCED, dao.single(it).status) }
        assertTrue(uploads.isEmpty())
    }

    // --- Разрешения и неполный scan ---

    @Test
    fun revokedPermissionStopsCheckUntilGranted() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        shadowOf(context).denyPermissions(mediaReadPermission)
        // Запуск прошёл проверку раньше, разрешение отозвано до прогона Поставщика.
        db.cameraCheckDao().put(
            CameraCheck(runId = "r", state = CameraCheckState.RUNNING, stage = CameraCheckStage.SCAN,
                serverUrl = baseUrlProvider.reload(), accountId = "user-a", startedAt = 1)
        )
        scheduler.enqueueProvider()
        drain()
        assertEquals(CameraCheckError.NO_MEDIA_PERMISSION, view()!!.check.error)

        shadowOf(context).grantPermissions(mediaReadPermission)
        scheduler.enqueueProvider()
        drain()
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
    }

    @Test
    fun incompleteScanDoesNotStartServerCheck() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        media.brokenRows = 1

        check.start()
        drain()

        val view = view()!!
        assertEquals(CameraCheckError.SCAN_INCOMPLETE, view.check.error)
        assertEquals(0, view.counts.total)
        assertTrue(checksumRequests.isEmpty())

        // «Повторить» после восстановления MediaStore продолжает проверку.
        media.brokenRows = 0
        assertEquals(CheckStartResult.Started(scheduled = true), check.retry())
        drain()
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
    }

    // --- 19: повторные нажатия ---

    @Test
    fun doubleStartCreatesOneCheckAndOneProviderRun() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        val before = ids(SyncScheduler.PROVIDER_WORK_NAME)

        val results = listOf(async(Dispatchers.IO) { check.start() }, async(Dispatchers.IO) { check.start() }).awaitAll()
        drain()

        assertEquals(setOf(CheckStartResult.Started(scheduled = true), CheckStartResult.AlreadyRunning), results.toSet())
        assertEquals(1, (ids(SyncScheduler.PROVIDER_WORK_NAME) - before).size)
        assertEquals(1, checksumRequests.size)
    }

    // --- Q-01.1 D1: пауза между партиями обычного pre-check ---

    @Test
    fun pauseBetweenPrecheckBatchesStopsNextBatchAndSyncResumesAfterCancel() = runBlocking {
        val total = SyncConfig.PRECHECK_BATCH_SIZE + 1L
        (1L..total).forEach { file(it, MediaFileStatus.CHECKSUM_READY) }
        val started = CopyOnWriteArrayList<CheckStartResult>()
        // Сверка запускается, пока уже работающий Загрузчик ждёт ответа на первую партию pre-check.
        onChecksums = { n, _ ->
            if (n == 1) started += runBlocking { check.start() }
            null
        }

        scheduler.enqueueUpload()
        drain()

        assertEquals(listOf<CheckStartResult>(CheckStartResult.Started(scheduled = true)), started.toList())
        // Отправленная партия завершена, следующая партия обычного pre-check не отправлялась;
        // серверная стадия сверки не заблокирована: её две партии прошли.
        assertEquals(SyncConfig.PRECHECK_BATCH_SIZE, checksumRequests[0].size)
        assertEquals(listOf(SyncConfig.PRECHECK_BATCH_SIZE, 1), checksumRequests.drop(1).map { it.size })
        assertEquals(SyncConfig.PRECHECK_BATCH_SIZE, dao.countByStatus(MediaFileStatus.PENDING_UPLOAD))
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(total).status)
        assertTrue(uploads.isEmpty())
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
        assertEquals(total.toInt(), view()!!.counts.missing)

        // После снятия паузы обычная синхронизация продолжает: остаток pre-check и все upload.
        assertTrue(check.cancel())
        drain()
        assertEquals(total.toInt(), uploads.size)
        assertEquals(total.toInt(), dao.countByStatus(MediaFileStatus.SYNCED))
    }

    // --- Q-01.1 D2: «Повторить» без ожидания backoff ---

    @Test
    fun manualRetryDuringBackoffRunsWithoutWaitingForIt() = runBlocking {
        (1L..2L).forEach { file(it, MediaFileStatus.SYNCED) }
        down = true
        check.start()
        drain()
        assertEquals(CameraCheckError.SERVER_UNAVAILABLE, view()!!.check.error)
        val waiting = backoffUpload()

        down = false
        assertEquals(CheckStartResult.Started(scheduled = true), check.retry())
        drain()

        // Сверка завершена ручным прогоном; задача в backoff не отпускалась, не отменена и не заменена.
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
        assertEquals(WorkInfo.State.ENQUEUED, stateOf(waiting.id))
        assertEquals(1, checksumRequests.size)

        // Отпущенная позже, она находит решённую серверную стадию и запрос не повторяет.
        releaseBackoff(waiting.id)
        awaitUntil { stateOf(waiting.id).isFinished }
        drain()
        assertEquals(WorkInfo.State.SUCCEEDED, stateOf(waiting.id))
        assertEquals(1, checksumRequests.size)
        assertTrue(all().none { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun retryWhileUploaderRunsNeitherCancelsItNorRunsStageTwice() = runBlocking {
        (1L..2L).forEach { file(it, MediaFileStatus.SYNCED) }
        down = true
        check.start()
        drain()
        val auto = backoffUpload()
        down = false

        val retried = CopyOnWriteArrayList<CheckStartResult>()
        val manualSeenRunning = CopyOnWriteArrayList<Boolean>()
        // «Повторить» нажато, пока автоматический повтор держит серверную стадию и ждёт ответа.
        onChecksums = { n, _ ->
            if (n == 1) {
                retried += runBlocking { check.retry() }
                val manual = infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).single()
                WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(manual.id)
                awaitUntil { stateOf(manual.id) == WorkInfo.State.RUNNING }
                manualSeenRunning += stateOf(manual.id) == WorkInfo.State.RUNNING
            }
            null
        }
        releaseBackoff(auto.id)
        awaitUntil { stateOf(auto.id).isFinished }
        drain()

        assertEquals(listOf<CheckStartResult>(CheckStartResult.Started(scheduled = true)), retried.toList())
        assertEquals(listOf(true), manualSeenRunning.toList())
        // Работавший прогон не отменён; ручной дождался серверной стадии и повторно Server не спрашивал.
        assertEquals(WorkInfo.State.SUCCEEDED, stateOf(auto.id))
        assertEquals(WorkInfo.State.SUCCEEDED, infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).single().state)
        assertEquals(1, checksumRequests.size)
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
        assertTrue(all().none { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun doubleRetryCreatesOneRunAndFailedManualRunLeavesNoQueue() = runBlocking {
        (1L..2L).forEach { file(it, MediaFileStatus.SYNCED) }
        down = true
        check.start()
        drain()

        val results = listOf(async(Dispatchers.IO) { check.retry() }, async(Dispatchers.IO) { check.retry() }).awaitAll()
        assertEquals(setOf(CheckStartResult.Started(scheduled = true), CheckStartResult.AlreadyRunning), results.toSet())
        assertEquals(1, infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).size)
        drain()

        // Server всё ещё недоступен: ошибка видна снова, ручной прогон завершён без собственного backoff.
        assertEquals(CameraCheckError.SERVER_UNAVAILABLE, view()!!.check.error)
        assertEquals(WorkInfo.State.SUCCEEDED, infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).single().state)

        // Следующее нажатие снова запускает прогон сразу; под именем остаётся одна запись.
        down = false
        assertEquals(CheckStartResult.Started(scheduled = true), check.retry())
        drain()
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
        assertEquals(1, infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).size)
        assertEquals(1, checksumRequests.size)
    }

    @Test
    fun cancelWhileRetryWaitsLeavesNoHangingWork() = runBlocking {
        (1L..2L).forEach { file(it, MediaFileStatus.SYNCED) }
        down = true
        check.start()
        drain()
        val waiting = backoffUpload()

        assertEquals(CheckStartResult.Started(scheduled = true), check.retry())
        assertTrue(check.cancel())
        down = false
        drain()
        releaseBackoff(waiting.id)
        awaitUntil { stateOf(waiting.id).isFinished }
        drain()

        assertNull(view())
        assertFalse(check.isUploadPaused())
        assertTrue(all().all { it.state == WorkInfo.State.SUCCEEDED })
        (1L..2L).forEach { assertEquals(MediaFileStatus.SYNCED, dao.single(it).status) }
        assertTrue(checksumRequests.isEmpty())
        assertTrue(uploads.isEmpty())
    }

    // --- Q-01.2: отмена в ERROR при задаче в backoff ---

    @Test
    fun cancelInErrorLetsNormalSyncContinueWithoutWaitingForBackoff() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        val ready = file(2, MediaFileStatus.CHECKSUM_READY)
        down = true
        check.start()
        drain()
        assertEquals(CameraCheckError.SERVER_UNAVAILABLE, view()!!.check.error)
        val waiting = backoffUpload()

        down = false
        assertTrue(check.cancel())
        drain()

        // Ожидающий файл отправлен немедленной попыткой; задача в backoff не отпускалась, не отменена и не заменена.
        assertEquals(listOf(ready.id), uploads.toList())
        assertEquals(MediaFileStatus.SYNCED, dao.single(1).status)
        assertEquals(MediaFileStatus.SYNCED, dao.single(ready.id).status)
        assertEquals(WorkInfo.State.ENQUEUED, stateOf(waiting.id))

        // Отпущенная позже, она и её продолжение завершаются без повторной отправки.
        releaseBackoff(waiting.id)
        awaitUntil { stateOf(waiting.id).isFinished }
        drain()
        assertTrue(all().all { it.state == WorkInfo.State.SUCCEEDED })
        assertEquals(listOf(ready.id), uploads.toList())
    }

    @Test
    fun cancelWhileUploaderRunsDoesNotCancelIt() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        val ready = file(2, MediaFileStatus.CHECKSUM_READY)
        down = true
        check.start()
        drain()
        val auto = backoffUpload()
        down = false
        val cancelled = CopyOnWriteArrayList<Boolean>()
        // Отмена — пока автоматический повтор ждёт ответа сверки (проверка ещё в ERROR).
        onChecksums = { n, _ ->
            if (n == 1) cancelled += runBlocking { check.cancel() }
            null
        }

        releaseBackoff(auto.id)
        awaitUntil { stateOf(auto.id).isFinished }
        drain()

        assertEquals(listOf(true), cancelled.toList())
        assertEquals(WorkInfo.State.SUCCEEDED, stateOf(auto.id))
        assertTrue(all().none { it.state == WorkInfo.State.CANCELLED })
        assertNull(view())
        // Поздний missing не применён; ожидающий файл отправлен ровно один раз.
        assertEquals(MediaFileStatus.SYNCED, dao.single(1).status)
        assertEquals(listOf(ready.id), uploads.toList())
    }

    @Test
    fun unavailableServerAfterCancelCreatesNoEndlessRetries() = runBlocking {
        file(1, MediaFileStatus.SYNCED)
        val ready = file(2, MediaFileStatus.CHECKSUM_READY)
        down = true
        check.start()
        drain()
        val waiting = backoffUpload()

        assertTrue(check.cancel())
        drain()
        val requestsAfter = requests.get()
        Thread.sleep(500)

        // Одна немедленная попытка; сама по себе она не повторяется, ручная цепочка завершена.
        assertEquals(requestsAfter, requests.get())
        assertTrue(infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).all { it.state == WorkInfo.State.SUCCEEDED })
        // Повтор остался за прежней задачей в backoff (новой KEEP не поставил); за ней — одно продолжение отмены.
        assertEquals(waiting.id, backoffUpload().id)
        assertEquals(
            listOf(WorkInfo.State.BLOCKED),
            infos(SyncScheduler.UPLOAD_WORK_NAME).filter { it.id != waiting.id && !it.state.isFinished }.map { it.state }
        )
        // Backoff-политика сохранена: при недоступном Server задача снова уходит в повтор.
        val attempts = waiting.runAttemptCount
        releaseBackoff(waiting.id)
        // runAttemptCount растёт при старте прогона — ждём и возврата в очередь после отказа.
        awaitUntil {
            workManager.getWorkInfoById(waiting.id).get()!!.let { it.runAttemptCount == attempts + 1 && it.state == WorkInfo.State.ENQUEUED }
        }
        assertEquals(WorkInfo.State.ENQUEUED, stateOf(waiting.id))
        assertEquals(attempts + 1, workManager.getWorkInfoById(waiting.id).get()!!.runAttemptCount)
        assertTrue(uploads.isEmpty())
        assertEquals(MediaFileStatus.SYNCED, dao.single(1).status)
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(ready.id).status)
    }

    // --- Q-01.2: исчерпание бюджета серверной стадии ---

    @Test
    fun exhaustedBudgetContinuesCheckWithSameRunWithoutRepeatingBatches() = runBlocking {
        val total = twoBatchCamera()
        onChecksums = { n, _ -> exhaustBudgetOn(n) }

        check.start()
        val runId = view()!!.check.runId
        drain()

        val done = view()!!
        assertEquals(CameraCheckState.AWAITING_DECISION, done.check.state)
        assertEquals(runId, done.check.runId)
        assertEquals(listOf(SyncConfig.PRECHECK_BATCH_SIZE, 1), checksumRequests.map { it.size })
        assertEquals(total, checksumRequests.flatten().toSet().size)
        assertEquals(total, done.check.checked)
        // Первая партия и её продолжение — в цепочке ручных прогонов.
        assertEquals(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.SUCCEEDED), infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).map { it.state })
    }

    @Test
    fun continuationIsNotDelayedByBackoffOfNormalChain() = runBlocking {
        twoBatchCamera()
        down = true
        check.start()
        drain()
        val waiting = backoffUpload()
        down = false
        onChecksums = { n, _ -> exhaustBudgetOn(n) }

        assertEquals(CheckStartResult.Started(scheduled = true), check.retry())
        drain()

        // «Повторить» проверил первую партию, его продолжение — вторую; задача в backoff не отпускалась.
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
        assertEquals(listOf(SyncConfig.PRECHECK_BATCH_SIZE, 1), checksumRequests.map { it.size })
        assertEquals(WorkInfo.State.ENQUEUED, stateOf(waiting.id))
        assertEquals(2, infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).count { it.state == WorkInfo.State.SUCCEEDED })
    }

    @Test
    fun cancelBetweenRunsStopsFurtherCheck() = runBlocking {
        val total = twoBatchCamera()
        onChecksums = { n, _ -> exhaustBudgetOn(n) }

        check.start()
        drain(releaseManual = false)
        // Первый прогон проверил партию и поставил продолжение; оно ещё не запускалось.
        val first = infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first.id)
        awaitUntil { stateOf(first.id).isFinished }
        val pending = infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).single { it.id != first.id }
        assertEquals(WorkInfo.State.ENQUEUED, pending.state)
        assertEquals(0, pending.runAttemptCount)
        assertEquals(SyncConfig.PRECHECK_BATCH_SIZE, view()!!.counts.answered)

        assertTrue(check.cancel())
        drain()

        // Продолжение выполнилось, но сверку не продолжило: вторая партия не отправлялась.
        assertNull(view())
        assertEquals(WorkInfo.State.SUCCEEDED, stateOf(pending.id))
        assertEquals(1, checksumRequests.size)
        (1L..total).forEach { assertEquals(MediaFileStatus.SYNCED, dao.single(it).status) }
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun repeatedSchedulingNeverRunsServerStageInParallel() = runBlocking {
        twoBatchCamera()
        down = true
        check.start()
        drain()
        val auto = backoffUpload()
        down = false
        val extra = CopyOnWriteArrayList<CheckStartResult>()
        // Пока ручной прогон ждёт ответа на первую партию: просыпается задача в backoff, ещё одно «Повторить»
        // и лишнее продолжение. Все они должны дождаться серверной стадии, а не идти параллельно.
        onChecksums = { n, _ ->
            if (n == 1) {
                exhaustBudgetOn(n)
                releaseBackoff(auto.id)
                extra += runBlocking { check.retry() }
                runBlocking { scheduler.continueUploadNow().result.get() }
                val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
                infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).filter { it.state == WorkInfo.State.ENQUEUED }
                    .forEach { driver.setAllConstraintsMet(it.id) }
                awaitUntil { stateOf(auto.id) == WorkInfo.State.RUNNING }
                Thread.sleep(300)
            }
            null
        }

        assertEquals(CheckStartResult.Started(scheduled = true), check.retry())
        drain()
        awaitUntil { stateOf(auto.id).isFinished }
        drain()

        assertEquals(listOf<CheckStartResult>(CheckStartResult.AlreadyRunning), extra.toList())
        assertEquals(1, maxChecksumsInFlight.get())
        assertEquals(listOf(SyncConfig.PRECHECK_BATCH_SIZE, 1), checksumRequests.map { it.size })
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
        assertEquals(WorkInfo.State.SUCCEEDED, stateOf(auto.id))
        assertTrue(all().none { it.state == WorkInfo.State.CANCELLED })
    }

    // --- Q-01.3: первый запуск серверной стадии ---

    /** Обычная синхронизация не достучалась до Server и ждёт backoff; Server уже снова доступен. */
    private suspend fun normalChainInBackoff(): WorkInfo {
        down = true
        scheduler.enqueueUpload()
        drain()
        down = false
        return backoffUpload()
    }

    @Test
    fun firstServerBatchDoesNotWaitForNormalChainBackoff() = runBlocking {
        file(1, MediaFileStatus.SYNCED, onServer = true)
        val ready = file(2, MediaFileStatus.CHECKSUM_READY)
        val waiting = normalChainInBackoff()

        check.start()
        drain()

        // Сверка дошла до итога, не отпуская задачу в backoff; ожидающий файл на паузе не отправлен.
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
        assertEquals(listOf(ready.id), db.cameraCheckDao().missingItems().map { it.mediaStoreId })
        assertEquals(1, checksumRequests.size)
        assertEquals(WorkInfo.State.ENQUEUED, stateOf(waiting.id))
        assertEquals(listOf(WorkInfo.State.SUCCEEDED), infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).map { it.state })
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun normalUploaderWakingDuringFirstStageDoesNotDuplicateIt() = runBlocking {
        (1L..2L).forEach { file(it, MediaFileStatus.SYNCED) }
        val waiting = normalChainInBackoff()
        onChecksums = { n, _ ->
            if (n == 1) {
                // Пока первая партия ждёт ответа, просыпается обычный Загрузчик из backoff.
                releaseBackoff(waiting.id)
                awaitUntil { stateOf(waiting.id) == WorkInfo.State.RUNNING }
                Thread.sleep(300)
            }
            null
        }

        check.start()
        drain()
        awaitUntil { stateOf(waiting.id).isFinished }
        drain()

        assertEquals(1, maxChecksumsInFlight.get())
        assertEquals(1, checksumRequests.size)
        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
        assertEquals(WorkInfo.State.SUCCEEDED, stateOf(waiting.id))
        assertTrue(all().none { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun unavailableServerAtFirstStageMakesOneAttemptThenUsesBackoff() = runBlocking {
        (1L..2L).forEach { file(it, MediaFileStatus.SYNCED) }
        down = true

        check.start()
        drain()

        assertEquals(CameraCheckError.SERVER_UNAVAILABLE, view()!!.check.error)
        // Одна немедленная попытка в ручной цепочке; повтор — разовая задача с задержкой первого backoff.
        assertEquals(listOf(WorkInfo.State.SUCCEEDED), infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).map { it.state })
        val delayed = backoffUpload()
        assertEquals(0, delayed.runAttemptCount)
        assertTrue(delayed.initialDelayMillis >= androidx.work.WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS)
        val requestsAfter = requests.get()
        Thread.sleep(500)
        assertEquals(requestsAfter, requests.get())

        // Сигналы Поставщика в ERROR немедленных попыток не добавляют.
        scheduler.enqueueProvider()
        drain()
        assertEquals(requestsAfter, requests.get())
        assertEquals(1, infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).size)

        // Отпущенная задача при той же ошибке уходит в обычный backoff.
        releaseBackoff(delayed.id)
        awaitUntil {
            workManager.getWorkInfoById(delayed.id).get()!!.let { it.runAttemptCount == 1 && it.state == WorkInfo.State.ENQUEUED }
        }
        assertEquals(WorkInfo.State.ENQUEUED, stateOf(delayed.id))
        (1L..2L).forEach { assertEquals(MediaFileStatus.SYNCED, dao.single(it).status) }
    }

    @Test
    fun cancelBetweenHashAndServerSendsNoCheckRequest() = runBlocking {
        (1L..2L).forEach { file(it, MediaFileStatus.SYNCED) }

        check.start()
        drain(releaseManual = false)
        assertEquals(CameraCheckStage.SERVER, view()!!.check.stage)
        assertEquals(CameraCheckState.RUNNING, view()!!.check.state)
        val pending = infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).single()
        assertEquals(WorkInfo.State.ENQUEUED, pending.state)

        assertTrue(check.cancel())
        drain()

        assertNull(view())
        assertEquals(WorkInfo.State.SUCCEEDED, stateOf(pending.id))
        assertTrue(checksumRequests.isEmpty())
        (1L..2L).forEach { assertEquals(MediaFileStatus.SYNCED, dao.single(it).status) }
    }

    @Test
    fun restartInServerStageResumesWithoutWaitingForBackoff() = runBlocking {
        (1L..2L).forEach { file(it, MediaFileStatus.SYNCED) }
        val waiting = normalChainInBackoff()
        check.start()
        drain(releaseManual = false)
        assertEquals(CameraCheckStage.SERVER, view()!!.check.stage)

        // Перезапуск процесса: Room сохранена, запрос серверной стадии потерян, обычная задача по-прежнему в backoff.
        workManager.cancelUniqueWork(SyncScheduler.UPLOAD_RETRY_WORK_NAME).result.get()
        db.close()
        openDatabase()
        ProcessStartSync(context, scheduler).onProcessStart()
        drain()

        assertEquals(CameraCheckState.AWAITING_DECISION, view()!!.check.state)
        assertEquals(2, view()!!.counts.missing)
        assertEquals(1, checksumRequests.size)
        assertEquals(WorkInfo.State.ENQUEUED, stateOf(waiting.id))
    }

    @Test
    fun withoutCheckNormalSchedulingIsUnchanged() = runBlocking {
        val first = file(1, MediaFileStatus.CHECKSUM_READY)
        val waiting = normalChainInBackoff()
        val second = file(2, MediaFileStatus.PENDING)

        // Сигнал Поставщика без проверки — прежний KEEP разовой цепочки: ждущую backoff он не обходит.
        scheduler.enqueueProvider()
        drain()
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(second.id).status)
        assertTrue(uploads.isEmpty())
        assertTrue(infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).isEmpty())
        assertEquals(WorkInfo.State.ENQUEUED, stateOf(waiting.id))

        releaseBackoff(waiting.id)
        awaitUntil { stateOf(waiting.id).isFinished }
        drain()
        assertEquals(listOf(first.id, second.id), uploads.sorted())
        assertTrue(infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).isEmpty())
    }

    @Test
    fun serverStageWaitsForUploadAlreadyInFlight() = runBlocking {
        (1L..3L).forEach { file(it, MediaFileStatus.PENDING_UPLOAD) }
        // Проверка запускается во время отправки файла 1; Server ответит на неё только после того, как
        // ручной прогон серверной стадии уже работает.
        onUpload = { id ->
            if (id == 1L) {
                runBlocking { check.start() }
                awaitUntil { infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME).any { it.state == WorkInfo.State.RUNNING } }
                Thread.sleep(500)
            }
        }

        scheduler.enqueueUpload()
        drain()

        // Ответ сверки учитывает дошедший файл 1: он подтверждён, а не «не найден».
        assertEquals(listOf(1L), uploads.toList())
        val view = view()!!
        assertEquals(CameraCheckState.AWAITING_DECISION, view.check.state)
        assertEquals(1, view.counts.confirmed)
        assertEquals(setOf(2L, 3L), db.cameraCheckDao().missingItems().map { it.mediaStoreId }.toSet())
    }

    /** Camera на две партии сверки: чётные есть на Server. @return число файлов. */
    private suspend fun twoBatchCamera(): Int {
        val total = SyncConfig.PRECHECK_BATCH_SIZE + 1L
        (1L..total).forEach { file(it, MediaFileStatus.SYNCED, onServer = it % 2 == 0L) }
        return total.toInt()
    }

    /** Ответ на первую партию «съедает» бюджет начатого прогона: следующая партия — уже в продолжении. */
    private fun exhaustBudgetOn(n: Int): MockResponse? {
        if (n == 1) clockShift.addAndGet(SyncConfig.WORKER_TIME_BUDGET_MS)
        return null
    }

    // --- 20: переустановка с пустой Room ---

    @Test
    fun emptyRoomAfterReinstallFindsEverythingOnServerWithoutUpload() = runBlocking {
        val photos = (1L..3L).map { id -> media.add(image(id)).also { existing += sha256(it.bytes) } }

        check.start()
        drain()

        assertEquals(CameraCheckState.FINISHED, view()!!.check.state)
        assertEquals(3, view()!!.check.confirmed)
        photos.forEach {
            assertEquals(MediaFileStatus.SYNCED, dao.single(it.id).status)
            // Checksum посчитан один раз — Поставщиком; сверка его не пересчитывала.
            assertEquals(1, media.openCount(it.id))
        }
        assertTrue(uploads.isEmpty())
        assertFalse(check.isUploadPaused())
    }

    // --- Вспомогательное ---

    private suspend fun view(): CameraCheckView? = check.observe().first()

    private fun image(id: Long) =
        FakeMediaStore.Image(id = id, bytes = "photo-$id".toByteArray(), dateModifiedSec = 1_700_000_000L + id)

    /** Фото на устройстве и строка Room в [status]; [onServer] — его checksum уже есть в Camera. */
    private suspend fun file(
        id: Long,
        status: MediaFileStatus,
        onServer: Boolean = false,
        withChecksum: Boolean = status != MediaFileStatus.PENDING,
        failure: FailureReason? = null,
        retries: Int = 0
    ): FakeMediaStore.Image {
        val image = media.add(image(id))
        images[id] = image
        val checksum = if (withChecksum) sha256(image.bytes) else null
        dao.insert(
            row(image, status, checksum).copy(
                failureReason = failure,
                retryCount = retries,
                lastFailureAt = failure?.let { System.currentTimeMillis() }
            )
        )
        if (onServer) existing += sha256(image.bytes)
        return image
    }

    /**
     * Выполняет все разовые задачи Поставщика и Загрузчика, включая поставленные ими самими. Задача, ждущая
     * повтора после backoff, остаётся ждать: её отпускает сам тест.
     */
    private fun drain(releaseManual: Boolean = true) {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        val deadline = System.currentTimeMillis() + 30_000
        var quietChecks = 0
        // releaseManual = false: новые ручные прогоны (photo_upload_retry) остаются ждать сеть — их отпускает тест.
        val watched = { if (releaseManual) all() else infos(SyncScheduler.PROVIDER_WORK_NAME) + infos(SyncScheduler.UPLOAD_WORK_NAME) }
        while (quietChecks < 3) {
            kotlin.check(System.currentTimeMillis() < deadline) { "Задачи не завершились: ${all().map { it.state }}" }
            val busy = watched().filter {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED && !it.isWaiting()
            }
            if (busy.isEmpty()) {
                quietChecks++
            } else {
                quietChecks = 0
                // Загрузчику нужна сеть: ограничение снимается один раз для каждой новой задачи.
                (infos(SyncScheduler.UPLOAD_WORK_NAME) + if (releaseManual) infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME) else emptyList())
                    .filter { it.state == WorkInfo.State.ENQUEUED && !it.isWaiting() && released.add(it.id) }
                    .forEach { driver.setAllConstraintsMet(it.id) }
            }
            Thread.sleep(50)
        }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    private fun all(): List<WorkInfo> =
        infos(SyncScheduler.PROVIDER_WORK_NAME) + infos(SyncScheduler.UPLOAD_WORK_NAME) + infos(SyncScheduler.UPLOAD_RETRY_WORK_NAME)

    /** Разовый Загрузчик, ждущий повтора после backoff. */
    private fun backoffUpload(): WorkInfo =
        infos(SyncScheduler.UPLOAD_WORK_NAME).single { it.state == WorkInfo.State.ENQUEUED && it.isWaiting() }

    /** Ждёт backoff или отложенного запуска — сам по себе в тесте не стартует. */
    private fun WorkInfo.isWaiting() = runAttemptCount > 0 || initialDelayMillis > 0

    private fun releaseBackoff(id: UUID) {
        WorkManagerTestInitHelper.getTestDriver(context)!!.apply {
            setInitialDelayMet(id)
            setAllConstraintsMet(id)
        }
    }

    private fun stateOf(id: UUID): WorkInfo.State = workManager.getWorkInfoById(id).get()!!.state

    private fun ids(name: String): Set<UUID> = infos(name).map { it.id }.toSet()

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun respond(request: RecordedRequest): MockResponse {
        requests.incrementAndGet()
        if (down) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        return when (request.path.orEmpty()) {
            "/api/v1/test" -> json("""{"message":"ok"}""")
            "/api/v1/folders/camera" -> json("""{"id":$CAMERA_ID,"parentId":1,"name":"Camera","folderType":"CAMERA"}""")
            "/api/v1/files/checksums/exists" -> {
                val inFlight = checksumsInFlight.incrementAndGet()
                maxChecksumsInFlight.accumulateAndGet(inFlight) { a, b -> maxOf(a, b) }
                try {
                    val requested = Regex("[0-9a-f]{64}").findAll(request.body.readUtf8()).map { it.value }.toList()
                    checksumRequests += requested
                    onChecksums(checksumRequests.size, requested)?.let { return it }
                    val (found, absent) = requested.partition { it in existing }
                    json("""{"existing":${found.asJson()},"missing":${absent.asJson()}}""")
                } finally {
                    checksumsInFlight.decrementAndGet()
                }
            }
            "/api/v1/files/upload" -> {
                val body = request.body.readByteArray()
                val id = Regex("filename=\"IMG_(\\d+)\\.jpg\"").find(body.toString(Charsets.ISO_8859_1))!!.groupValues[1].toLong()
                onUpload(id)
                uploads += id
                existing += uploadedChecksum(body)
                json(uploadResponseJson(nextFileId.getAndIncrement(), body))
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun List<String>.asJson() = joinToString(",", "[", "]") { "\"$it\"" }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}

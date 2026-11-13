package ru.tbcarus.photo_cloud_client.media

import android.Manifest
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
import kotlinx.coroutines.awaitAll
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
import ru.tbcarus.photo_cloud_client.ui.screens.settings.SettingsViewModel
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        val GPS_ORIGINAL = "JPEG|GPS:55.7558N,37.6173E|pixels".toByteArray()
        val GPS_REDACTED = "JPEG|GPS:00.0000\u0000,00.0000\u0000|pixels".toByteArray()
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

    // Server: вначале нет ни одного checksum, id новых файлов растут с 100; checksum считается по принятым байтам,
    // повтор того же содержимого получает прежний id (duplicate), pre-check видит всё принятое.
    private val nextFileId = AtomicLong(100)
    private val serverIds = ConcurrentHashMap<String, Long>()
    private val paths = CopyOnWriteArrayList<String>()
    private val requestedChecksums = CopyOnWriteArrayList<String>()
    private val uploadedBodies = CopyOnWriteArrayList<ByteArray>()

    // Статусы строки в моменты, когда её видят MediaStore-поток и Server.
    private val trace = CopyOnWriteArrayList<MediaFileStatus>()

    @Volatile private var uploaderRunRecorded: () -> Unit = {}

    // Upload: действие во время запроса и код ответа вместо успешного (null — успех).
    @Volatile private var onUploadRequest: () -> Unit = {}
    @Volatile private var uploadErrorCode: Int? = null

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
        val syncStatusStore = testSyncStatusStore()
        // Итог Загрузчик пишет после последней проверки очереди: хук попадает в окно до завершения задачи.
        val uploaderStatusStore = testSyncStatusStore(wallClock = { uploaderRunRecorded(); System.currentTimeMillis() })
        // Общие экземпляры, как @Singleton в приложении: Mutex стадий действуют на все задачи.
        mediaFileRepository = MediaFileRepository(dao, MediaStoreRepository(context))
        val checksumRepository = ChecksumRepository(dao, MediaStreamOpener(context), context)
        val precheckRepository = ChecksumPrecheckRepository(dao, apiServiceFactory)
        val uploadRepository = FileUploadRepository(dao, apiServiceFactory, MediaStreamOpener(context))
        val serverRepository = ServerRepository(apiServiceFactory)
        scheduler = SyncScheduler(context)
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
                    appContext, workerParameters, mediaFileRepository, checksumRepository, syncStatusStore, coordinator,
                    scheduler, SyncClock.SYSTEM, idleCameraCheck(appContext, db)
                )
                UploadWorker::class.java.name -> UploadWorker(
                    appContext, workerParameters, folderRepository, precheckRepository, uploadRepository, serverRepository,
                    baseUrlProvider, uploaderStatusStore, storage, coordinator, scheduler, SyncClock.SYSTEM, idleCameraCheck(appContext, db)
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
    fun permissionGrantInSettingsStartsProviderWithoutWaitingForPeriodic() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val image = media.add(image(1, "photo-1"))
        val viewModel = SettingsViewModel(coordinator, scheduler)

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

    @Test
    @Config(sdk = [34]) // MediaStore.setRequireOriginal — API 29+
    fun withMediaLocationChecksumAndUploadUseOriginalBytes() = runBlocking {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val image = media.add(imageWithGps(1))
        mediaFileRepository.scanImages()

        syncAll()

        val synced = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, synced.status)
        assertEquals(sha256(GPS_ORIGINAL), synced.checksum)
        assertEquals(listOf(sha256(GPS_ORIGINAL)), requestedChecksums.toList())
        // Server получает ровно оригинал: его SHA-256 совпадает с локальным checksum.
        assertEquals(GPS_ORIGINAL.toString(Charsets.ISO_8859_1), uploadedFilePart())
        assertEquals(0, media.openCount(image.id))
        // hash, проверка доступа и отправка — три чтения оригинала.
        assertEquals(3, media.originalOpenCount(image.id))
    }

    @Test
    @Config(sdk = [34]) // MediaStore.setRequireOriginal — API 29+
    fun withoutMediaLocationBackupContinuesWithAvailableVersion() = runBlocking {
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val image = media.add(imageWithGps(1))
        mediaFileRepository.scanImages()

        syncAll()

        val synced = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, synced.status)
        assertEquals(sha256(image.bytes), synced.checksum)
        assertEquals(image.bytes.toString(Charsets.ISO_8859_1), uploadedFilePart())
        assertEquals(0, media.originalOpenCount(image.id))
    }

    @Test
    @Config(sdk = [34]) // MediaStore.setRequireOriginal — API 29+
    fun refusedOriginalDoesNotFailWorkers() = runBlocking {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        media.originalFailure = { UnsupportedOperationException("Caller must hold ACCESS_MEDIA_LOCATION") }
        val image = media.add(imageWithGps(1))
        mediaFileRepository.scanImages()

        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)
        assertEquals(WorkInfo.State.SUCCEEDED, runProvider().state)
        assertEquals(WorkInfo.State.SUCCEEDED, runSignalledUpload(uploadsBefore).state)

        val synced = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, synced.status)
        assertNull(synced.failureReason)
        assertEquals(sha256(image.bytes), synced.checksum)
        assertEquals(image.bytes.toString(Charsets.ISO_8859_1), uploadedFilePart())
    }

    @Test
    @Config(sdk = [34]) // MediaStore.setRequireOriginal — API 29+
    fun locationRevokedBetweenChecksumAndUploadSyncsAcceptedVersionWithoutSecondTransfer() = runBlocking {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val image = media.add(imageWithGps(1))
        mediaFileRepository.scanImages()
        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)
        runProvider()
        assertEquals(sha256(GPS_ORIGINAL), dao.single(image.id).checksum)

        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        runUploadThenRehash(uploadsBefore)

        // Отправлена доступная версия: Server вернул её checksum, Android перехешировал файл тем же правилом выбора
        // потока, и pre-check нашёл принятую копию — SYNCED с checksum отправленных байтов без второй передачи.
        val synced = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, synced.status)
        assertEquals(sha256(GPS_REDACTED), synced.checksum)
        assertEquals(0, synced.uploadMismatchCount)
        assertEquals(GPS_REDACTED.toString(Charsets.ISO_8859_1), uploadedFilePart())
        assertEquals(listOf(sha256(GPS_ORIGINAL), sha256(GPS_REDACTED)), requestedChecksums.toList())
    }

    // --- Несовпадение checksum ответа upload (Q-02) ---

    @Test
    fun contentChangedBetweenHashAndUploadIsTransferredOnce() = runBlocking {
        val image = media.add(image(1, "content-A"))
        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)
        runProvider()
        assertEquals(sha256("content-A".toByteArray()), dao.single(image.id).checksum)
        // Тот же размер и время изменения: scan изменения не видит, upload читает уже новые байты.
        image.bytes = "content-B".toByteArray()

        runUploadThenRehash(uploadsBefore)

        val synced = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, synced.status)
        assertEquals(sha256(image.bytes), synced.checksum)
        assertEquals(0, synced.uploadMismatchCount)
        // Передача одна: B уже на Server, повторный hash и pre-check его подтвердили.
        assertEquals(1, paths.count { it == "/api/v1/files/upload" })
        assertEquals("content-B", uploadedFilePart())
        assertEquals(listOf(sha256("content-A".toByteArray()), sha256(image.bytes)), requestedChecksums.toList())
    }

    @Test
    fun contentChangedAgainBeforeRehashIsUploadedWithNewChecksum() = runBlocking {
        val image = media.add(image(1, "content-A"))
        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)
        runProvider()
        image.bytes = "content-B".toByteArray()

        // К повторному hash файл изменился ещё раз: C на Server нет — обычный upload с новой проверкой ответа.
        runUploadThenRehash(uploadsBefore) { image.bytes = "content-C".toByteArray() }

        val synced = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, synced.status)
        assertEquals(sha256("content-C".toByteArray()), synced.checksum)
        assertEquals(101L, synced.serverFileId)
        assertEquals(0, synced.uploadMismatchCount)
        assertEquals(2, paths.count { it == "/api/v1/files/upload" })
    }

    @Test
    fun rehashFinishedWhileUploaderIsStillRunningStartsPrecheckWithoutSecondTransfer() = runBlocking {
        val image = media.add(image(1, "content-A"))
        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)
        runProvider()
        image.bytes = "content-B".toByteArray()
        val upload = awaitNewId(SyncScheduler.UPLOAD_WORK_NAME, uploadsBefore)
        val providersBefore = ids(SyncScheduler.PROVIDER_WORK_NAME)

        // Поставщик, поставленный несовпадением, начинает повторный hash только после того, как Загрузчик прошёл
        // последнюю проверку очереди (строка ещё PENDING), и успевает завершиться и подать сигнал, пока задача
        // Загрузчика всё ещё RUNNING.
        val rehashAllowed = CountDownLatch(1)
        media.beforeQuery = { rehashAllowed.await(10, TimeUnit.SECONDS) }
        val providerState = java.util.concurrent.atomic.AtomicReference<WorkInfo.State?>()
        val uploadStateAtSignal = java.util.concurrent.atomic.AtomicReference<WorkInfo.State?>()
        uploaderRunRecorded = {
            uploaderRunRecorded = {}
            rehashAllowed.countDown()
            providerState.set(awaitFinished(awaitNewId(SyncScheduler.PROVIDER_WORK_NAME, providersBefore)).state)
            uploadStateAtSignal.set(workManager.getWorkInfoById(upload).get()!!.state)
        }
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(upload)
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(upload).state)
        media.beforeQuery = {}
        assertEquals(WorkInfo.State.SUCCEEDED, providerState.get())
        assertEquals(WorkInfo.State.RUNNING, uploadStateAtSignal.get())
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(image.id).status)
        assertEquals(sha256(image.bytes), dao.single(image.id).checksum)

        // Сигнал не потерян: следующий прогон Загрузчика стартует сам, pre-check находит B на Server.
        val next = awaitNewId(SyncScheduler.UPLOAD_WORK_NAME, uploadsBefore + upload)
        assertEquals(WorkInfo.State.SUCCEEDED, runSignalledUpload(uploadsBefore + upload).state)
        val synced = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, synced.status)
        assertEquals(sha256(image.bytes), synced.checksum)
        assertEquals(1, paths.count { it == "/api/v1/files/upload" })
        assertEquals(listOf(sha256("content-A".toByteArray()), sha256(image.bytes)), requestedChecksums.toList())
        // Ровно один дополнительный прогон, бесконечной цепочки нет.
        assertEquals(setOf(upload, next), ids(SyncScheduler.UPLOAD_WORK_NAME) - uploadsBefore)
    }

    @Test
    fun signalDuringRunningUploaderKeepsBackoffAfterServerError() = runBlocking {
        media.add(image(1, "photo-1"))
        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)
        runProvider()
        val upload = awaitNewId(SyncScheduler.UPLOAD_WORK_NAME, uploadsBefore)
        // Пока Загрузчик отправляет первое фото, Поставщик готовит второе и сигналит; затем Server отвечает 500.
        uploadErrorCode = 500
        onUploadRequest = {
            onUploadRequest = {}
            media.add(image(2, "photo-2"))
            runProvider()
        }

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(upload)
        awaitUntil { workManager.getWorkInfoById(upload).get()!!.let { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 } }

        // Прогон ждёт backoff; добавленный сигналом запуск стоит после него и раньше не начнётся.
        val runs = infos(SyncScheduler.UPLOAD_WORK_NAME).filter { it.id !in uploadsBefore }
        assertEquals(2, runs.size)
        assertEquals(WorkInfo.State.ENQUEUED, runs.single { it.id == upload }.state)
        assertEquals(WorkInfo.State.BLOCKED, runs.single { it.id != upload }.state)
        // Повторный сигнал при ожидающем прогоне ничего не добавляет и backoff не обходит.
        runProvider()
        Thread.sleep(300)
        assertEquals(runs.map { it.id }.toSet(), ids(SyncScheduler.UPLOAD_WORK_NAME) - uploadsBefore)
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfoById(upload).get()!!.state)
        assertEquals(1, paths.count { it == "/api/v1/files/upload" })
    }

    // --- Ручной повтор FAILED (кнопка «Повторить ошибки» на Files) ---

    @Test
    fun manualRetrySyncsAllFailedPhotosAndLeavesSyncedOnesAlone() = runBlocking {
        val good = media.add(image(1, "good"))
        val broken = listOf(media.add(image(2, "broken-2")), media.add(image(3, "broken-3")))
        broken.forEach { it.openFailure = { FileNotFoundException("gone") } }
        syncAll()
        val syncedBefore = dao.single(good.id)
        assertEquals(MediaFileStatus.SYNCED, syncedBefore.status)
        broken.forEach { assertEquals(FailureReason.FILE_NOT_FOUND, dao.single(it.id).failureReason) }

        // Файлы снова читаются; пользователь нажимает кнопку один раз.
        broken.forEach { it.openFailure = null }
        val providersBefore = ids(SyncScheduler.PROVIDER_WORK_NAME)
        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)
        assertEquals(FailedRetryResult.Queued(2, scheduled = true), failedRetry().retryAll())

        // Продолжение Поставщика выполняется само, затем по его сигналу — Загрузчик.
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(awaitNewId(SyncScheduler.PROVIDER_WORK_NAME, providersBefore)).state)
        runSignalledUpload(uploadsBefore)

        broken.forEach {
            val row = dao.single(it.id)
            assertEquals(MediaFileStatus.SYNCED, row.status)
            assertNull(row.failureReason)
            assertEquals(0, row.retryCount)
            assertEquals(sha256(it.bytes), row.checksum)
        }
        assertEquals(syncedBefore, dao.single(good.id))
        assertEquals(3, paths.count { it == "/api/v1/files/upload" })
    }

    @Test
    fun failureAfterManualRetryKeepsAutomaticRetryLimit() = runBlocking {
        val image = media.add(image(1, "flaky"))
        image.openFailure = { IOException("read error") }
        runProvider()
        // Авто-повтор исчерпан (лимит 3, cooldown прошёл): Поставщик запись больше не берёт.
        exhaustAutoRetry(image.id)
        runProvider()
        assertEquals(MediaFileStatus.FAILED, dao.single(image.id).status)
        assertEquals(3, dao.single(image.id).retryCount)

        val before = ids(SyncScheduler.PROVIDER_WORK_NAME)
        assertEquals(FailedRetryResult.Queued(1, scheduled = true), failedRetry().retryAll())
        awaitFinished(awaitNewId(SyncScheduler.PROVIDER_WORK_NAME, before))

        // Ошибка повторилась: попытка засчитана заново, дальше — прежний ограниченный авто-повтор.
        val failedAgain = dao.single(image.id)
        assertEquals(MediaFileStatus.FAILED, failedAgain.status)
        assertEquals(FailureReason.CHECKSUM_IO, failedAgain.failureReason)
        assertEquals(1, failedAgain.retryCount)
        exhaustAutoRetry(image.id)
        val opens = media.openCount(image.id)
        runProvider()
        assertEquals(MediaFileStatus.FAILED, dao.single(image.id).status)
        assertEquals(3, dao.single(image.id).retryCount)
        assertEquals(opens, media.openCount(image.id))
    }

    @Test
    fun withoutPhotoAccessFailedPhotosStayFailed() = runBlocking {
        val image = media.add(image(1, "photo"))
        image.openFailure = { FileNotFoundException("gone") }
        runProvider()
        val failed = dao.single(image.id)
        shadowOf(context).denyPermissions(mediaReadPermission)
        val before = ids(SyncScheduler.PROVIDER_WORK_NAME)

        assertEquals(FailedRetryResult.NoPermission, failedRetry().retryAll())

        assertEquals(failed, dao.single(image.id))
        assertEquals(before, ids(SyncScheduler.PROVIDER_WORK_NAME))
    }

    @Test
    fun doublePressQueuesFailedOnceAndAddsOneProviderRun() = runBlocking {
        val images = listOf(media.add(image(1, "a")), media.add(image(2, "b")))
        images.forEach { it.openFailure = { FileNotFoundException("gone") } }
        runProvider()
        images.forEach { it.openFailure = null }
        val before = ids(SyncScheduler.PROVIDER_WORK_NAME)
        val retry = failedRetry()

        val results = listOf(async(Dispatchers.IO) { retry.retryAll() }, async(Dispatchers.IO) { retry.retryAll() }).awaitAll()

        assertEquals(setOf(FailedRetryResult.Queued(2, scheduled = true), FailedRetryResult.NothingToRetry), results.toSet())
        awaitFinished(awaitNewId(SyncScheduler.PROVIDER_WORK_NAME, before))
        assertEquals(1, (ids(SyncScheduler.PROVIDER_WORK_NAME) - before).size)
        images.forEach { assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(it.id).status) }
    }

    @Test
    fun retryDuringRunningProviderIsAppendedWithoutReplacingIt() = runBlocking {
        val image = media.add(image(1, "photo"))
        image.openFailure = { FileNotFoundException("gone") }
        runProvider()
        image.openFailure = null
        val retry = failedRetry()
        val duringRun = java.util.concurrent.atomic.AtomicReference<FailedRetryResult?>()
        // Нажатие приходится на уже выполняющийся Поставщик (его scan читает MediaStore).
        media.beforeQuery = { if (duringRun.get() == null) duringRun.set(runBlocking { retry.retryAll() }) }
        val before = ids(SyncScheduler.PROVIDER_WORK_NAME)

        scheduler.enqueueProvider()

        awaitUntil { infos(SyncScheduler.PROVIDER_WORK_NAME).filter { it.id !in before }.let { it.size == 2 && it.all { w -> w.state.isFinished } } }
        val runs = infos(SyncScheduler.PROVIDER_WORK_NAME).filter { it.id !in before }
        assertEquals(FailedRetryResult.Queued(1, scheduled = true), duringRun.get())
        // Выполнявшийся прогон не отменён, повтор добавлен следующим после него.
        assertEquals(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.SUCCEEDED), runs.map { it.state })
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(image.id).status)
    }

    @Test
    fun queuedRowsWithoutRecordedContinuationArePickedUpOnProcessStart() = runBlocking {
        val image = media.add(image(1, "photo"))
        image.openFailure = { FileNotFoundException("gone") }
        runProvider()
        image.openFailure = null
        // Процесс погиб после записи в Room, продолжение Поставщика записать не успели.
        assertEquals(1, dao.resetAllFailedForManualRetry())
        assertEquals(MediaFileStatus.PENDING, dao.single(image.id).status)

        runNew(SyncScheduler.PROVIDER_WORK_NAME, needsNetwork = false) { ProcessStartSync(context, scheduler).onProcessStart() }

        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(image.id).status)
    }

    private fun failedRetry() = FailedRetry(context, dao, scheduler)

    private fun exhaustAutoRetry(id: Long) = db.openHelper.writableDatabase.execSQL(
        "UPDATE media_files SET retryCount = 3, lastFailureAt = 0 WHERE mediaStoreId = $id"
    )

    /** Полный цикл Поставщик → Загрузчик для всех фото библиотеки. */
    private fun syncAll() {
        val uploadsBefore = ids(SyncScheduler.UPLOAD_WORK_NAME)
        runProvider()
        runSignalledUpload(uploadsBefore)
    }

    /**
     * Загрузчик по сигналу, получивший несовпадение checksum, затем продолжение Поставщика, которое он поставил,
     * и Загрузчик по сигналу этого Поставщика. Поставщик ждёт, пока тест прочитает итог первого Загрузчика: иначе
     * его сигнал (KEEP) мог бы прийтись на ещё выполняющийся прогон, а KEEP после окончания удаляет завершённую
     * задачу из базы WorkManager. [beforeRehash] меняет файл до повторного hash.
     */
    private fun runUploadThenRehash(uploadsBefore: Set<UUID>, beforeRehash: () -> Unit = {}) {
        val upload = awaitNewId(SyncScheduler.UPLOAD_WORK_NAME, uploadsBefore)
        val providersBefore = ids(SyncScheduler.PROVIDER_WORK_NAME)
        val uploadChecked = CountDownLatch(1)
        media.beforeQuery = { _ ->
            uploadChecked.await(10, TimeUnit.SECONDS)
            beforeRehash()
        }
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(upload)
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(upload).state)
        uploadChecked.countDown()
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(awaitNewId(SyncScheduler.PROVIDER_WORK_NAME, providersBefore)).state)
        media.beforeQuery = {}
        assertEquals(WorkInfo.State.SUCCEEDED, runSignalledUpload(uploadsBefore + upload).state)
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
            "/api/v1/folders/camera" -> json("""{"id":$CAMERA_ID,"parentId":$ROOT_ID,"name":"Camera","folderType":"CAMERA"}""")
            "/api/v1/files/checksums/exists" -> {
                val requested = Regex("[0-9a-f]{64}").findAll(request.body.readUtf8()).map { it.value }.toList()
                requestedChecksums += requested
                val (existing, missing) = requested.partition { serverIds.containsKey(it) }
                json("""{"existing":${existing.asJson()},"missing":${missing.asJson()}}""")
            }
            "/api/v1/files/upload" -> {
                val body = request.body.readByteArray()
                onUploadRequest()
                uploadErrorCode?.let { return MockResponse().setResponseCode(it) }
                uploadedBodies += body
                val checksum = uploadedChecksum(body)
                json("""{"id":${serverIds.computeIfAbsent(checksum) { nextFileId.getAndIncrement() }},"checksum":"$checksum"}""")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun List<String>.asJson() = joinToString(",", "[", "]") { "\"$it\"" }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun image(id: Long, content: String) =
        FakeMediaStore.Image(id = id, bytes = content.toByteArray(), dateModifiedSec = 1_700_000_000L + id)

    // Обычный поток MediaStore без ACCESS_MEDIA_LOCATION: те же размер и структура, координаты затёрты.
    private fun imageWithGps(id: Long) = FakeMediaStore.Image(
        id = id,
        bytes = GPS_REDACTED,
        dateModifiedSec = 1_700_000_000L + id,
        originalBytes = GPS_ORIGINAL
    )

    /** Содержимое part «file» единственного upload: байты между заголовками part и закрывающим boundary. */
    private fun uploadedFilePart(): String {
        val body = uploadedBodies.single().toString(Charsets.ISO_8859_1)
        val start = body.indexOf("\r\n\r\n", body.indexOf("name=\"file\"")) + 4
        return body.substring(start, body.indexOf("\r\n--", start))
    }

    private fun <T> List<T>.distinctConsecutive(): List<T> =
        fold(mutableListOf()) { acc, item -> if (acc.lastOrNull() != item) acc += item; acc }
}

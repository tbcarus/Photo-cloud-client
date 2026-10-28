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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Загрузчик с большой очередью (A8.2): pre-check и upload партиями FIFO по mediaStoreId в пределах бюджета,
 * продолжение следующим прогоном через WorkManager. Время управляемое: запросы тестового Server
 * «занимают» [precheckCostMs] и [uploadCostMs]; прогоны запускаются по одному через TestDriver.
 * Единичный обрыв ответа upload (A8.3.2) моделируется ответом без последнего байта и закрытием соединения.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadBatchContinuationTest {

    private companion object {
        const val ROOT_ID = 1L
        const val CAMERA_ID = 2L
        val BUDGET = SyncConfig.WORKER_TIME_BUDGET_MS
    }

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private lateinit var server: MockWebServer
    private lateinit var scheduler: SyncScheduler
    private lateinit var uploadRepository: FileUploadRepository
    private lateinit var baseUrlProvider: BaseUrlProvider
    private val dao get() = db.mediaFileDao()
    private val workManager get() = WorkManager.getInstance(context)
    private val clock = AtomicLong()

    // Поведение тестового Server.
    @Volatile private var pingCostMs = 0L
    @Volatile private var precheckCostMs = 0L
    @Volatile private var uploadCostMs = 0L
    @Volatile private var isExisting: (Long) -> Boolean = { false }
    @Volatile private var onUpload: (Long) -> Unit = {}
    private val uploadCodes = ConcurrentHashMap<Long, Int>()
    /** Сколько ближайших ответов upload для файла оборвать; файл при этом уже сохранён Server. */
    private val lostResponses = ConcurrentHashMap<Long, Int>()
    /** Server идемпотентен по checksum: повторная отправка того же файла получает тот же FileItem. */
    private val serverIds = ConcurrentHashMap<Long, Long>()
    private val uploadedContents = CopyOnWriteArrayList<Pair<Long, String>>()
    private val idByChecksum = ConcurrentHashMap<String, Long>()
    private val precheckRequests = CopyOnWriteArrayList<List<Long>>()
    private val uploads = CopyOnWriteArrayList<Long>()
    private val nextServerId = AtomicInteger(1_000)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        media = FakeMediaStore.install(context)
        shadowOf(context).grantPermissions(mediaReadPermission)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val serverPreferences = ServerPreferences(context)
        runBlocking { serverPreferences.saveConnection("127.0.0.1", server.port.toString()) }

        val syncStatusStore = SyncStatusStore()
        scheduler = SyncScheduler(context, syncStatusStore)
        baseUrlProvider = BaseUrlProvider(serverPreferences)
        val client = OkHttpClient()
        val api = ApiServiceFactory(baseUrlProvider, client, client, client)
        val folderRepository = FolderRepository(api)
        // Общие экземпляры, как @Singleton в приложении.
        val precheckRepository = ChecksumPrecheckRepository(dao, api, folderRepository)
        uploadRepository = FileUploadRepository(dao, api, folderRepository, MediaStreamOpener(context))
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? = when (workerClassName) {
                UploadWorker::class.java.name -> UploadWorker(
                    appContext, workerParameters, precheckRepository, uploadRepository, ServerRepository(api),
                    baseUrlProvider, syncStatusStore, InMemoryTokenStorage(Tokens("access", "refresh")),
                    mock(PeriodicSyncCoordinator::class.java), scheduler, SyncClock { clock.get() }
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
    }

    @Test
    fun precheckOfSeveralBatchesThenUploadInSameRunInFifoOrder() = runBlocking {
        // ID в Room вставляются вразнобой; на Server нет каждого сотого фото.
        (1L..1_100L).shuffled().forEach { ready(it) }
        isExisting = { it % 100 != 0L }

        scheduler.enqueueUpload()
        val run = runNextUploader()

        assertEquals(WorkInfo.State.SUCCEEDED, run.state)
        assertEquals(listOf(500, 500, 100), precheckRequests.map { it.size })
        assertEquals((1L..500L).toList(), precheckRequests[0])
        assertEquals((1L..1_100L).toList(), precheckRequests.flatten())
        // Pre-check завершился в пределах бюджета — upload в том же прогоне, тоже FIFO.
        assertEquals((100L..1_100L step 100).toList(), uploads.toList())
        assertEquals(1_100, dao.countByStatus(MediaFileStatus.SYNCED))
        // Очередь исчерпана — продолжения нет.
        assertEquals(1, uploaderRuns().size)
    }

    @Test
    fun budgetExhaustedDuringPrecheckSkipsUploadAndContinuesNextRun() = runBlocking {
        (1L..1_500L).forEach { ready(it) }
        isExisting = { it > 2 }
        precheckCostMs = 250_000

        scheduler.enqueueUpload()
        val first = runNextUploader()

        // Два запроса уложились в бюджет, третий не начат; upload в этом прогоне не начинался.
        assertEquals(WorkInfo.State.SUCCEEDED, first.state)
        assertEquals(2, precheckRequests.size)
        assertTrue(uploads.isEmpty())
        assertEquals(998, dao.countByStatus(MediaFileStatus.SYNCED))
        assertEquals(2, dao.countByStatus(MediaFileStatus.PENDING_UPLOAD))
        assertEquals(500, dao.countByStatus(MediaFileStatus.CHECKSUM_READY))
        assertEquals(1, pendingUploaderRuns().size)

        val second = runNextUploader()

        assertEquals(WorkInfo.State.SUCCEEDED, second.state)
        assertEquals(3, precheckRequests.size)
        assertEquals(listOf(1L, 2L), uploads.toList())
        assertEquals(1_500, dao.countByStatus(MediaFileStatus.SYNCED))
        assertEquals(0, pendingUploaderRuns().size)
    }

    @Test
    fun budgetExhaustedDuringUploadKeepsSentFilesAndChainStaysBounded() = runBlocking {
        (1L..30L).forEach { pendingUpload(it) }
        uploadCostMs = 30_000
        val perRun = (BUDGET / uploadCostMs).toInt()

        scheduler.enqueueUpload()
        val syncedAfterRun = mutableListOf<Int>()
        var maxUnfinished = 0
        while (true) {
            runNextUploader()
            syncedAfterRun += dao.countByStatus(MediaFileStatus.SYNCED)
            maxUnfinished = maxOf(maxUnfinished, uploaderRuns().count { !it.state.isFinished })
            if (pendingUploaderRuns().isEmpty()) break
        }

        // Отправленные файлы остаются SYNCED, остаток продолжают следующие прогоны.
        assertEquals(listOf(perRun, perRun * 2, 30), syncedAfterRun)
        assertEquals((1L..30L).toList(), uploads.toList())
        assertTrue(uploaderRuns().all { it.state == WorkInfo.State.SUCCEEDED })
        // После прогона ожидает не больше одного продолжения; следующая постановка очищает цепочку.
        assertTrue(maxUnfinished <= 1)
        scheduler.enqueueUpload()
        assertEquals(1, uploaderRuns().size)
    }

    @Test
    fun transientErrorKeepsRetryPolicyWithoutContinuation() = runBlocking {
        (1L..30L).forEach { pendingUpload(it) }
        uploadCodes[3] = 503

        scheduler.enqueueUpload()
        val run = runNextUploader(expectFinished = false)

        // Прежний Result.retry(): та же работа ждёт повтора, штатного продолжения рядом нет.
        assertEquals(WorkInfo.State.ENQUEUED, run.state)
        assertEquals(1, run.runAttemptCount)
        assertEquals(listOf(run.id), uploaderRuns().map { it.id })
        assertEquals(MediaFileStatus.SYNCED, dao.single(2).status)
        assertEquals(MediaFileStatus.PENDING_UPLOAD, dao.single(3).status)
        assertEquals(28, dao.countByStatus(MediaFileStatus.PENDING_UPLOAD))
        // HTTP-ошибка немедленно не повторяется.
        assertEquals(listOf(1L, 2L, 3L), uploads.toList())
    }

    @Test
    fun permanentErrorOfOneFileDoesNotStopOthers() = runBlocking {
        (1L..5L).forEach { pendingUpload(it) }
        uploadCodes[2] = 400
        uploadCodes[3] = 409
        uploadCodes[4] = 413

        scheduler.enqueueUpload()
        val run = runNextUploader()

        assertEquals(WorkInfo.State.SUCCEEDED, run.state)
        assertEquals(FailureReason.HTTP_400, dao.single(2).failureReason)
        assertEquals(FailureReason.HTTP_409, dao.single(3).failureReason)
        assertEquals(FailureReason.HTTP_413, dao.single(4).failureReason)
        assertEquals(3, dao.countByStatus(MediaFileStatus.FAILED))
        assertEquals(2, dao.countByStatus(MediaFileStatus.SYNCED))
        // Каждый файл отправлен ровно один раз: ни успех, ни HTTP-ошибка не повторяются.
        assertEquals((1L..5L).toList(), uploads.toList())
        assertEquals(1, uploaderRuns().size)
    }

    @Test
    fun singleLostResponseIsResentImmediatelyWithoutWorkManagerRetry() = runBlocking {
        (1L..5L).forEach { pendingUpload(it) }
        lostResponses[3] = 1

        scheduler.enqueueUpload()
        val run = runNextUploader()

        assertEquals(WorkInfo.State.SUCCEEDED, run.state)
        assertEquals(1, uploaderRuns().size)
        assertEquals(5, dao.countByStatus(MediaFileStatus.SYNCED))
        assertEquals(listOf(1L, 2L, 3L, 3L, 4L, 5L), uploads.toList())
    }

    @Test
    fun resendAfterLostResponseIsRecognizedAsAlreadyStoredFile() = runBlocking {
        pendingUpload(1)
        lostResponses[1] = 1

        scheduler.enqueueUpload()
        runNextUploader()

        // Первая отправка уже сохранила файл; повтор получил тот же FileItem, второй файл не создан.
        assertEquals(1, serverIds.size)
        assertEquals(serverIds[1L], dao.single(1).serverFileId)
        assertEquals(MediaFileStatus.SYNCED, dao.single(1).status)
        // Тело повтора заново прочитано из content://uri целиком.
        assertEquals(listOf(1L to "photo-1", 1L to "photo-1"), uploadedContents.toList())
    }

    @Test
    fun secondLostResponseKeepsRetryPolicy() = runBlocking {
        (1L..5L).forEach { pendingUpload(it) }
        lostResponses[3] = 2

        scheduler.enqueueUpload()
        val run = runNextUploader(expectFinished = false)

        // Повтор только один; дальше прежний Result.retry() без продолжения.
        assertEquals(WorkInfo.State.ENQUEUED, run.state)
        assertEquals(1, run.runAttemptCount)
        assertEquals(listOf(run.id), uploaderRuns().map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 3L), uploads.toList())
        assertEquals(MediaFileStatus.PENDING_UPLOAD, dao.single(3).status)
        assertEquals(2, dao.countByStatus(MediaFileStatus.SYNCED))
    }

    @Test
    fun exhaustedBudgetDoesNotStartResend() = runBlocking {
        (1L..2L).forEach { pendingUpload(it) }
        lostResponses[1] = 1
        onUpload = { id -> if (id == 1L) clock.addAndGet(BUDGET) }

        scheduler.enqueueUpload()
        val run = runNextUploader(expectFinished = false)

        assertEquals(WorkInfo.State.ENQUEUED, run.state)
        assertEquals(listOf(1L), uploads.toList())
        assertEquals(2, dao.countByStatus(MediaFileStatus.PENDING_UPLOAD))
    }

    @Test
    fun stoppedWorkDoesNotStartResend() = runBlocking {
        (1L..2L).forEach { pendingUpload(it) }
        lostResponses[1] = 1
        val sending = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        onUpload = { id ->
            if (id == 1L) {
                sending.countDown()
                stopped.await(10, TimeUnit.SECONDS)
            }
        }

        baseUrlProvider.reload()
        // Остановка Worker отменяет корутину прогона, пока первая отправка ещё ждёт ответа.
        val run = launch(Dispatchers.Default) { uploadRepository.uploadPendingCameraFiles() }
        assertTrue(sending.await(10, TimeUnit.SECONDS))
        run.cancel()
        stopped.countDown()
        run.join()

        assertTrue(run.isCancelled)
        assertEquals(listOf(1L), uploads.toList())
        // Строку вернёт в очередь начало следующего прогона upload.
        assertEquals(MediaFileStatus.UPLOADING, dao.single(1).status)
        assertEquals(MediaFileStatus.PENDING_UPLOAD, dao.single(2).status)
    }

    @Test
    fun emptyQueueAndRunWithoutProgressDoNotRegisterContinuation() = runBlocking {
        scheduler.enqueueUpload()
        assertEquals(WorkInfo.State.SUCCEEDED, runNextUploader().state)
        assertEquals(1, uploaderRuns().size)

        // Ping израсходовал весь бюджет: ни один запрос pre-check не начат, очередь осталась.
        (1L..10L).forEach { ready(it) }
        pingCostMs = BUDGET
        scheduler.enqueueUpload()
        val run = runNextUploader()

        assertEquals(WorkInfo.State.SUCCEEDED, run.state)
        assertTrue(precheckRequests.isEmpty())
        assertEquals(10, dao.countByStatus(MediaFileStatus.CHECKSUM_READY))
        assertEquals(listOf(run.id), uploaderRuns().map { it.id })
    }

    @Test
    fun periodicAndOneTimeUploaderDoNotCancelEachOther() = runBlocking {
        scheduler.enqueueUploadPeriodic()
        val periodicId = periodicInfo().id
        (1L..30L).forEach { pendingUpload(it) }
        uploadCostMs = 30_000
        val uploadStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        onUpload = { id ->
            if (id == 1L) {
                uploadStarted.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }

        scheduler.enqueueUpload()
        startEnqueuedUploaders()
        assertTrue(uploadStarted.await(10, TimeUnit.SECONDS))
        val oneTimeId = uploaderRuns().single().id

        // Так регистрирует продолжение периодический прогон: KEEP не трогает идущую разовую цепочку.
        scheduler.continueUpload(fromOneTimeRun = false).result.get()
        assertEquals(listOf(oneTimeId), uploaderRuns().map { it.id })
        assertEquals(WorkInfo.State.RUNNING, uploaderRuns().single().state)

        release.countDown()
        awaitUntil { uploaderRuns().first { it.id == oneTimeId }.state.isFinished }
        while (pendingUploaderRuns().isNotEmpty()) runNextUploader()

        // Цепочка разового Загрузчика с продолжениями не отменила periodic.
        assertEquals(30, dao.countByStatus(MediaFileStatus.SYNCED))
        assertTrue(uploaderRuns().all { it.state == WorkInfo.State.SUCCEEDED })
        assertEquals(periodicId, periodicInfo().id)
        assertEquals(WorkInfo.State.ENQUEUED, periodicInfo().state)
    }

    @Test
    fun readyRowsAddedByProviderDuringUploadAreProcessedByContinuation() = runBlocking {
        (1L..3L).forEach { pendingUpload(it) }
        // Пока Загрузчик отправляет, Поставщик готовит новые строки.
        onUpload = { id ->
            if (id == 2L) runBlocking { (10L..14L).forEach { ready(it) } }
        }

        scheduler.enqueueUpload()
        runNextUploader()

        assertEquals(3, dao.countByStatus(MediaFileStatus.SYNCED))
        assertEquals(5, dao.countByStatus(MediaFileStatus.CHECKSUM_READY))
        assertEquals(1, pendingUploaderRuns().size)

        runNextUploader()

        assertEquals(8, dao.countByStatus(MediaFileStatus.SYNCED))
        assertEquals(listOf(1L, 2L, 3L, 10L, 11L, 12L, 13L, 14L), uploads.toList())
        assertEquals(0, pendingUploaderRuns().size)
    }

    /** Запускает ожидающий разовый Загрузчик и ждёт окончания этого прогона. */
    private fun runNextUploader(expectFinished: Boolean = true): WorkInfo {
        val id = startEnqueuedUploaders().single()
        awaitUntil {
            val info = info(id)
            if (expectFinished) info.state.isFinished else info.state != WorkInfo.State.RUNNING && info.runAttemptCount > 0
        }
        return info(id)
    }

    /** Снимает сетевое ограничение с ожидающих разовых Загрузчиков (BLOCKED-продолжение ждёт предыдущий прогон). */
    private fun startEnqueuedUploaders(): List<UUID> {
        awaitUntil { uploaderRuns().any { it.state == WorkInfo.State.ENQUEUED } }
        val ids = uploaderRuns().filter { it.state == WorkInfo.State.ENQUEUED }.map { it.id }
        ids.forEach { WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(it) }
        return ids
    }

    private fun uploaderRuns(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(SyncScheduler.UPLOAD_WORK_NAME).get()

    private fun pendingUploaderRuns(): List<WorkInfo> = uploaderRuns().filter { !it.state.isFinished }

    private fun info(id: UUID): WorkInfo = workManager.getWorkInfoById(id).get()!!

    private fun periodicInfo(): WorkInfo =
        workManager.getWorkInfosForUniqueWork(SyncScheduler.UPLOAD_PERIODIC_WORK_NAME).get().single()

    private suspend fun ready(id: Long) {
        val image = image(id)
        idByChecksum[sha256(image.bytes)] = id
        dao.insert(row(media.add(image), MediaFileStatus.CHECKSUM_READY, sha256(image.bytes)))
    }

    private suspend fun pendingUpload(id: Long) {
        val image = image(id)
        dao.insert(row(media.add(image), MediaFileStatus.PENDING_UPLOAD, sha256(image.bytes)))
    }

    private fun image(id: Long) =
        FakeMediaStore.Image(id = id, bytes = "photo-$id".toByteArray(), dateModifiedSec = 2_000_000_000L - id)

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when (path) {
            "/api/v1/test" -> {
                clock.addAndGet(pingCostMs)
                json("""{"message":"ok"}""")
            }
            "/api/v1/folders/root" -> json("""{"id":$ROOT_ID,"parentId":null,"name":"root","folderType":"ROOT"}""")
            "/api/v1/folders/$ROOT_ID/children" ->
                json("""[{"id":$CAMERA_ID,"parentId":$ROOT_ID,"name":"Camera","folderType":"CAMERA"}]""")
            "/api/v1/files/checksums/exists" -> {
                val requested = Regex("[0-9a-f]{64}").findAll(request.body.readUtf8()).map { it.value }.toList()
                precheckRequests += requested.map { idByChecksum.getValue(it) }
                clock.addAndGet(precheckCostMs)
                val existing = requested.filter { isExisting(idByChecksum.getValue(it)) }
                val missing = requested - existing.toSet()
                json("""{"existing":${existing.toJson()},"missing":${missing.toJson()}}""")
            }
            "/api/v1/files/upload" -> {
                val body = request.body.readUtf8()
                val id = Regex("IMG_(\\d+)\\.jpg").find(body)!!.groupValues[1].toLong()
                uploads += id
                uploadedContents += id to filePartContent(body)
                onUpload(id)
                clock.addAndGet(uploadCostMs)
                val code = uploadCodes[id]
                if (code != null) return MockResponse().setResponseCode(code)
                val serverId = serverIds.computeIfAbsent(id) { nextServerId.getAndIncrement().toLong() }
                val response = """{"id":$serverId}"""
                if (lostResponses.computeIfPresent(id) { _, left -> left - 1 }?.let { it >= 0 } == true) {
                    // Файл сохранён, но клиент не получает последний байт ответа: соединение закрыто раньше.
                    json(response.dropLast(1))
                        .setHeader("Content-Length", response.length)
                        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                } else json(response)
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** Содержимое части file multipart-запроса. */
    private fun filePartContent(body: String): String {
        val headers = Regex("filename=\"[^\"]+\"\r\nContent-Type: [^\r]+\r\nContent-Length: (\\d+)\r\n\r\n")
        val match = headers.find(body)!!
        val start = match.range.last + 1
        return body.substring(start, start + match.groupValues[1].toInt())
    }

    private fun List<String>.toJson() = joinToString(",", "[", "]") { "\"$it\"" }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    // Workers выполняются на Dispatchers.Default — ждём опросом с ограничением по времени.
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Условие не выполнено за отведённое время", condition())
    }
}

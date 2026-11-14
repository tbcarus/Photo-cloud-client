package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.row
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.sha256
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Q-02: SYNCED после upload — только при совпадении checksum ответа (SHA-256 принятых Server байтов) с локальным.
 * Несовпадение возвращает строку Поставщику не более трёх раз подряд, затем FAILED CHANGED_DURING_UPLOAD.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadChecksumVerificationTest {

    private companion object {
        const val CAMERA_ID = 2L
        const val DB_NAME = "upload-mismatch.db"
    }

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private lateinit var server: MockWebServer
    private lateinit var api: ApiServiceFactory
    private val dao get() = db.mediaFileDao()

    // Поведение тестового Server: по умолчанию checksum ответа — SHA-256 полученного part «file».
    @Volatile private var responseChecksum: (ByteArray) -> String? = { uploadedChecksum(it) }
    @Volatile private var onUpload: (Long) -> Unit = {}
    /** Сколько ближайших ответов upload оборвать: файл уже сохранён Server, клиент ответа не получает. */
    private val lostResponses = AtomicInteger()
    /** Server идемпотентен по checksum: тот же контент получает тот же id. */
    private val idByChecksum = ConcurrentHashMap<String, Long>()
    private val nextId = AtomicLong(100)
    private val uploads = CopyOnWriteArrayList<Long>()

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
        val baseUrlProvider = BaseUrlProvider(ServerPreferences(context)).apply {
            baseUrl = "http://127.0.0.1:${server.port}/"
        }
        val client = OkHttpClient()
        api = ApiServiceFactory(baseUrlProvider, client, client, client)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    // --- Совпадение ---

    @Test
    fun matchingChecksumMarksSyncedWithServerId() = runBlocking {
        val image = pendingUpload(1, "photo-1")

        val outcome = upload()

        val row = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, row.status)
        assertEquals(100L, row.serverFileId)
        assertEquals(sha256(image.bytes), row.checksum)
        assertEquals(UploadResult(attempted = 1, succeeded = 1, failed = 0, leftPending = 0, rehashQueued = 0), outcome.result)
    }

    @Test
    fun videoMultipartUsesSameChecksumVerification() = runBlocking {
        val video = media.add(FakeMediaStore.Image(1, "video".toByteArray(), 1_700_000_001L,
            mimeType = "video/mp4", collection = MediaCollection.VIDEO))
        dao.insert(row(video, MediaFileStatus.PENDING_UPLOAD, sha256(video.bytes)))

        val success = upload()

        assertEquals(MediaFileStatus.SYNCED, dao.getByUri(video.uri.toString())?.status)
        assertEquals(1, success.result?.succeeded)

        dao.updateStatusIfUnchanged(video.uri.toString(), MediaFileStatus.SYNCED,
            MediaFileStatus.PENDING_UPLOAD, sha256(video.bytes), video.size, video.lastModifiedMs)
        video.bytes = "other".toByteArray()
        val mismatch = upload()

        assertEquals(MediaFileStatus.PENDING, dao.getByUri(video.uri.toString())?.status)
        assertEquals(1, mismatch.result?.rehashQueued)
    }

    @Test
    fun uppercaseChecksumInResponseMatches() = runBlocking {
        val image = pendingUpload(1, "photo-1")
        responseChecksum = { uploadedChecksum(it).uppercase() }

        upload()

        assertEquals(MediaFileStatus.SYNCED, dao.single(image.id).status)
    }

    @Test
    fun duplicateResponseWithSameChecksumMarksSynced() = runBlocking {
        // Два фото с одинаковым содержимым: второй upload получает прежний FileItem (duplicate Server).
        val first = pendingUpload(1, "same")
        val second = pendingUpload(2, "same")

        upload()

        assertEquals(100L, dao.single(first.id).serverFileId)
        assertEquals(100L, dao.single(second.id).serverFileId)
        assertEquals(MediaFileStatus.SYNCED, dao.single(second.id).status)
    }

    @Test
    fun successResetsMismatchCounter() = runBlocking {
        val image = pendingUpload(1, "photo-1", mismatches = 2)

        upload()

        val row = dao.single(image.id)
        assertEquals(MediaFileStatus.SYNCED, row.status)
        assertEquals(0, row.uploadMismatchCount)
    }

    // --- Несовпадение ---

    @Test
    fun mismatchReturnsRowToHashingWithoutServerId() = runBlocking {
        val image = pendingUpload(1, "photo-A")
        val next = pendingUpload(2, "photo-2")
        // Файл изменился после hash: размер и время те же, Server получает другие байты.
        image.bytes = "photo-B".toByteArray()

        val outcome = upload()

        val row = dao.single(image.id)
        assertEquals(MediaFileStatus.PENDING, row.status)
        assertNull(row.checksum)
        assertNull(row.serverFileId)
        assertNull(row.failureReason)
        assertEquals(1, row.uploadMismatchCount)
        assertEquals(0, row.retryCount)
        // Немедленного повтора нет, следующий файл обрабатывается в том же прогоне.
        assertEquals(listOf(1L, 2L), uploads.toList())
        assertEquals(MediaFileStatus.SYNCED, dao.single(next.id).status)
        assertEquals(UploadResult(attempted = 2, succeeded = 1, failed = 0, leftPending = 0, rehashQueued = 1), outcome.result)
    }

    @Test
    fun mismatchAfterLostResponseIsJudgedByCompletedRetry() = runBlocking {
        val image = pendingUpload(1, "photo-A")
        // Первый ответ потерян; к повтору файл изменился — ответ на повтор несёт checksum новых байтов.
        lostResponses.set(1)
        onUpload = { if (uploads.size == 1) image.bytes = "photo-B".toByteArray() }

        upload()

        assertEquals(2, uploads.size)
        val row = dao.single(image.id)
        assertEquals(MediaFileStatus.PENDING, row.status)
        assertEquals(1, row.uploadMismatchCount)
    }

    @Test
    fun lostResponseRetryWithSameBytesMarksSynced() = runBlocking {
        val image = pendingUpload(1, "photo-A")
        lostResponses.set(1)

        upload()

        assertEquals(2, uploads.size)
        assertEquals(MediaFileStatus.SYNCED, dao.single(image.id).status)
        assertEquals(100L, dao.single(image.id).serverFileId)
    }

    @Test
    fun staleMismatchDoesNotTouchVersionUpdatedByScan() = runBlocking {
        val image = pendingUpload(1, "photo-A", mismatches = 1)
        image.bytes = "photo-B".toByteArray()
        // Пока Server принимает файл, scan записал новую версию.
        onUpload = { runBlocking { dao.applyScan(dao.single(image.id), size = 100, lastModified = 42) } }

        val outcome = upload()

        val row = dao.single(image.id)
        assertEquals(MediaFileStatus.PENDING, row.status)
        assertEquals(100L, row.size)
        // Счётчик сброшен scan (новая версия), а не увеличен поздним ответом.
        assertEquals(0, row.uploadMismatchCount)
        assertEquals(0, outcome.result!!.rehashQueued)
    }

    @Test
    fun deletedRowIsNotRecreatedByLateMismatch() = runBlocking {
        val image = pendingUpload(1, "photo-A")
        image.bytes = "photo-B".toByteArray()
        onUpload = { runBlocking { dao.deleteByUris(listOf(image.uri.toString())) } }

        upload()

        assertNull(dao.find(image.id))
    }

    @Test
    fun pauseAfterMismatchDoesNotStartNextTransfer() = runBlocking {
        val image = pendingUpload(1, "photo-A")
        val next = pendingUpload(2, "photo-2")
        image.bytes = "photo-B".toByteArray()
        // Ручная сверка Q-01 началась во время отправки первого файла.
        val paused = java.util.concurrent.atomic.AtomicBoolean(false)
        onUpload = { paused.set(true) }

        FileUploadRepository(dao, api, MediaStreamOpener(context))
            .uploadPendingCameraFiles(CAMERA_ID, isPaused = { paused.get() })

        assertEquals(listOf(1L), uploads.toList())
        assertEquals(MediaFileStatus.PENDING, dao.single(image.id).status)
        assertEquals(MediaFileStatus.PENDING_UPLOAD, dao.single(next.id).status)
    }

    @Test
    fun concurrentRunsSendEachFileOnce() = runBlocking {
        val images = (1L..3L).map { pendingUpload(it, "photo-$it") }
        images.first().bytes = "photo-X".toByteArray()
        val repository = FileUploadRepository(dao, api, MediaStreamOpener(context))

        listOf(
            async(Dispatchers.IO) { repository.uploadPendingCameraFiles(CAMERA_ID) },
            async(Dispatchers.IO) { repository.uploadPendingCameraFiles(CAMERA_ID) }
        ).awaitAll()

        assertEquals(listOf(1L, 2L, 3L), uploads.sorted())
        assertEquals(1, dao.single(1).uploadMismatchCount)
        assertEquals(MediaFileStatus.PENDING, dao.single(1).status)
    }

    // --- Ограничение циклов ---

    @Test
    fun firstThreeMismatchesAreRecoveredAutomatically() = runBlocking {
        for (previous in 0..2) {
            val image = pendingUpload(10L + previous, "photo-A", mismatches = previous)
            image.bytes = "photo-B".toByteArray()
            upload()
            val row = dao.single(image.id)
            assertEquals(MediaFileStatus.PENDING, row.status)
            assertEquals(previous + 1, row.uploadMismatchCount)
        }
    }

    @Test
    fun mismatchAfterThreeRecoveriesFails() = runBlocking {
        val image = pendingUpload(1, "photo-A", mismatches = 3)
        image.bytes = "photo-B".toByteArray()

        val outcome = upload()

        val row = dao.single(image.id)
        assertEquals(MediaFileStatus.FAILED, row.status)
        assertEquals(FailureReason.CHANGED_DURING_UPLOAD, row.failureReason)
        assertNull(row.checksum)
        assertNull(row.serverFileId)
        assertEquals(1, row.retryCount)
        assertTrue(row.lastFailureAt != null)
        assertEquals(4, row.uploadMismatchCount)
        assertEquals(UploadResult(attempted = 1, succeeded = 0, failed = 1, leftPending = 0, rehashQueued = 0), outcome.result)
    }

    @Test
    fun successfulHashKeepsMismatchCounter() = runBlocking {
        val image = media.add(image(1, "photo-1"))
        dao.insert(row(image, MediaFileStatus.PENDING).copy(uploadMismatchCount = 2))

        ChecksumRepository(dao, MediaStreamOpener(context), context).computePendingChecksums()

        val row = dao.single(image.id)
        assertEquals(MediaFileStatus.CHECKSUM_READY, row.status)
        assertEquals(sha256(image.bytes), row.checksum)
        assertEquals(2, row.uploadMismatchCount)
    }

    @Test
    fun mismatchCounterSurvivesDatabaseReopen() = runBlocking {
        db.close()
        db = AppDatabase.build(context, DB_NAME)
        val image = pendingUpload(1, "photo-A", mismatches = 2)
        image.bytes = "photo-B".toByteArray()
        upload()
        assertEquals(3, dao.single(image.id).uploadMismatchCount)

        // Процесс перезапущен: счётчик читается из файла БД, четвёртое несовпадение — FAILED.
        db.close()
        db = AppDatabase.build(context, DB_NAME)
        assertEquals(3, dao.single(image.id).uploadMismatchCount)
        // Scan без изменения версии счётчик не трогает.
        dao.single(image.id).let { dao.applyScan(it, it.size, it.lastModified) }
        assertEquals(3, dao.single(image.id).uploadMismatchCount)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE media_files SET status = 'PENDING_UPLOAD', checksum = '${sha256("photo-A".toByteArray())}' WHERE mediaStoreId = 1"
        )
        upload()
        assertEquals(MediaFileStatus.FAILED, dao.single(image.id).status)
    }

    @Test
    fun changedDuringUploadIsNotRestoredAutomatically() = runBlocking {
        val image = pendingUpload(1, "photo-A", mismatches = 3)
        image.bytes = "photo-B".toByteArray()
        upload()
        db.openHelper.writableDatabase.execSQL("UPDATE media_files SET lastFailureAt = 0 WHERE mediaStoreId = 1")

        val restored = ChecksumRepository(dao, MediaStreamOpener(context), context).restoreRetryableFailed()

        assertEquals(0, restored)
        assertEquals(MediaFileStatus.FAILED, dao.single(image.id).status)
    }

    @Test
    fun manualRetryStartsNewSeriesFromHashing() = runBlocking {
        val all = pendingUpload(1, "photo-A", mismatches = 3)
        val one = pendingUpload(2, "photo-C", mismatches = 3)
        all.bytes = "photo-B".toByteArray()
        one.bytes = "photo-D".toByteArray()
        upload()

        assertEquals(1, dao.resetFailedForManualRetryIfUnchanged(one.uri.toString(), one.size, one.lastModifiedMs))
        assertEquals(1, dao.resetAllFailedForManualRetry())

        listOf(all, one).forEach { image ->
            val row = dao.single(image.id)
            assertEquals(MediaFileStatus.PENDING, row.status)
            assertNull(row.checksum)
            assertNull(row.failureReason)
            assertEquals(0, row.retryCount)
            assertEquals(0, row.uploadMismatchCount)
        }
    }

    @Test
    fun newVersionFromScanAndPrecheckExistingResetCounter() = runBlocking {
        val changed = media.add(image(1, "photo-1"))
        val existing = media.add(image(2, "photo-2"))
        dao.insert(row(changed, MediaFileStatus.PENDING).copy(uploadMismatchCount = 2))
        dao.insert(row(existing, MediaFileStatus.CHECKSUM_READY, sha256(existing.bytes)).copy(uploadMismatchCount = 2))

        dao.applyScan(dao.single(changed.id), size = changed.size + 1, lastModified = changed.lastModifiedMs + 1000)
        dao.updateStatusIfUnchanged(dao.single(existing.id), MediaFileStatus.CHECKSUM_READY, MediaFileStatus.SYNCED)

        assertEquals(0, dao.single(changed.id).uploadMismatchCount)
        assertEquals(0, dao.single(existing.id).uploadMismatchCount)
    }

    // --- Некорректный ответ ---

    @Test
    fun successWithoutChecksumIsNotSyncedAndNotMismatch() = runBlocking {
        listOf<(ByteArray) -> String?>({ null }, { "" }, { "abc" }, { "z".repeat(64) }, { "a".repeat(63) }).forEach { invalid ->
            db.clearAllTables()
            uploads.clear()
            val image = pendingUpload(1, "photo-1", mismatches = 1)
            val next = pendingUpload(2, "photo-2")
            responseChecksum = invalid

            val outcome = upload()

            assertTrue(outcome is UploadOutcome.Error)
            val row = dao.single(image.id)
            assertEquals(MediaFileStatus.PENDING_UPLOAD, row.status)
            assertEquals(sha256(image.bytes), row.checksum)
            assertNull(row.serverFileId)
            assertEquals(1, row.uploadMismatchCount)
            // Прогон остановлен до повтора с backoff: следующий файл не отправлен.
            assertEquals(listOf(1L), uploads.toList())
            assertEquals(MediaFileStatus.PENDING_UPLOAD, dao.single(next.id).status)
        }
    }

    private suspend fun upload(): UploadOutcome =
        FileUploadRepository(dao, api, MediaStreamOpener(context)).uploadPendingCameraFiles(CAMERA_ID)

    private val UploadOutcome.result: UploadResult?
        get() = when (this) {
            is UploadOutcome.Success -> result
            is UploadOutcome.Error -> result
        }

    /** Фото в PENDING_UPLOAD с checksum текущего содержимого. */
    private suspend fun pendingUpload(id: Long, content: String, mismatches: Int = 0): FakeMediaStore.Image {
        val image = media.add(image(id, content))
        dao.insert(row(image, MediaFileStatus.PENDING_UPLOAD, sha256(image.bytes)).copy(uploadMismatchCount = mismatches))
        return image
    }

    private fun image(id: Long, content: String) =
        FakeMediaStore.Image(id = id, bytes = content.toByteArray(), dateModifiedSec = 1_700_000_000L + id)

    private fun respond(request: RecordedRequest): MockResponse {
        if (request.path != "/api/v1/files/upload") return MockResponse().setResponseCode(404)
        val body = request.body.readByteArray()
        val id = Regex("(?:IMG|VID)_(\\d+)\\.(?:jpg|mp4)").find(body.toString(Charsets.ISO_8859_1))!!.groupValues[1].toLong()
        uploads += id
        onUpload(id)
        val serverId = idByChecksum.computeIfAbsent(uploadedChecksum(body)) { nextId.getAndIncrement() }
        val checksum = responseChecksum(body)
        val response = if (checksum == null) """{"id":$serverId}""" else """{"id":$serverId,"checksum":"$checksum"}"""
        val json = MockResponse().setHeader("Content-Type", "application/json")
        return if (lostResponses.getAndUpdate { maxOf(0, it - 1) } > 0) {
            // Файл сохранён, но клиент не получает последний байт ответа: соединение закрыто раньше.
            json.setBody(response.dropLast(1)).setHeader("Content-Length", response.length)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        } else json.setBody(response)
    }
}

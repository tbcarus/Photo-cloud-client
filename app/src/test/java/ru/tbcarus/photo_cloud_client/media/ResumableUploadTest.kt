package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.net.Network
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.tbcarus.photo_cloud_client.api.UploadSessionService
import ru.tbcarus.photo_cloud_client.backup.BackupAccount
import ru.tbcarus.photo_cloud_client.backup.BackupAccountProvider
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ResumableUploadTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var uploader: ResumableUpload
    private val account = BackupAccount("http://localhost/", "user-1")
    private val checksum = "a".repeat(64)
    private val sessionId = UUID.randomUUID().toString()
    private val offset = AtomicLong()
    private val patches = mutableListOf<Int>()
    private val openedOffsets = mutableListOf<Long>()
    private val creates = AtomicInteger()
    private val completes = AtomicInteger()
    private val cancels = AtomicInteger()
    private val clockMs = AtomicLong()
    private var advanceClockAfterPatch = false
    private var advanceClockAfterGet = false
    private var sourceState: CameraSourceState = CameraSourceState.Current
    private var holdGet = false
    private val getStarted = CountDownLatch(1)
    private lateinit var wifi: WifiUploadNetwork
    private var loseCreate = false
    private var losePatch = false
    private var loseComplete = false
    private var challengePatch = false
    private var completed = false
    private val bytes = ByteArray(SyncConfig.RESUMABLE_CHUNK_BYTES.toInt() + 3) { (it % 251).toByte() }

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val service = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).authenticator { _, response ->
                if (response.request.header("Authorization") != null) null
                else response.request.newBuilder().header("Authorization", "Bearer refreshed").build()
            }.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(UploadSessionService::class.java)
        val api = mock(ApiServiceFactory::class.java)
        val network = mock(Network::class.java)
        `when`(api.authUploadSessionService(account.serverUrl, network)).thenReturn(service)
        wifi = mock(WifiUploadNetwork::class.java)
        `when`(wifi.current()).thenReturn(network)
        val accounts = mock(BackupAccountProvider::class.java)
        runBlocking { `when`(accounts.current()).thenReturn(account) }
        val source = object : CameraUploadSource(context, mock(MediaStreamOpener::class.java)) {
            override fun originalMode() = false
            override fun state(record: UploadSessionRecord) = sourceState
            override fun open(record: UploadSessionRecord, offset: Long): InputStream {
                openedOffsets.add(offset)
                val start = offset.toInt()
                return ByteArrayInputStream(bytes, start, bytes.size - start)
            }
        }
        uploader = ResumableUpload(db.uploadSessionDao(), db.mediaFileDao(), accounts, api, source, wifi)
    }

    @After fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test fun lostPatchAndCompleteResponsesResumeFromServerOffset() = runBlocking {
        losePatch = true
        loseComplete = true
        val file = file()
        val result = uploader.camera(file, 7, WorkBudget.UNLIMITED) { false }
        assertTrue("result=$result patches=$patches offset=${offset.get()} completes=${completes.get()}", result is ResumableResult.Complete)
        assertEquals(101L, (result as ResumableResult.Complete).file.id)
        assertEquals(listOf(SyncConfig.RESUMABLE_CHUNK_BYTES.toInt(), 3), patches)
        assertEquals(bytes.size.toLong(), offset.get())
        assertEquals(bytes.size.toLong(), db.uploadSessionDao().forAccount(account.serverUrl, account.accountId).single().confirmedOffset)
    }

    @Test fun videoUsesSameResumableProtocolAndKeepsCollectionInSession() = runBlocking {
        val video = file().copy(
            uri = "content://media/external/video/media/42", displayName = "video.mp4",
            mimeType = "video/mp4", collection = MediaCollection.VIDEO
        )

        assertTrue(uploader.camera(video, 7, WorkBudget.UNLIMITED) { false } is ResumableResult.Complete)
        assertEquals(listOf(SyncConfig.RESUMABLE_CHUNK_BYTES.toInt(), 3), patches)
        // Успешная сессия остаётся до подтверждения обработки результата Загрузчиком.
        assertEquals(MediaCollection.VIDEO,
            db.uploadSessionDao().forAccount(account.serverUrl, account.accountId).single().collection)
    }

    @Test fun lostCreateResponseReusesPersistedRequestId() = runBlocking {
        loseCreate = true
        val file = file()
        assertEquals(ResumableResult.Retry, uploader.camera(file, 7, WorkBudget.UNLIMITED) { false })
        val stored = db.uploadSessionDao().forAccount(account.serverUrl, account.accountId).single()
        assertEquals(null, stored.serverSessionId)
        assertTrue(uploader.camera(file, 7, WorkBudget.UNLIMITED) { false } is ResumableResult.Complete)
        assertTrue(creates.get() >= 2)
        assertEquals(stored.clientRequestId, db.uploadSessionDao().forAccount(account.serverUrl, account.accountId).single().clientRequestId)
    }

    @Test fun budgetStopsAfterConfirmedBlockAndNextRunUsesOffset() = runBlocking {
        advanceClockAfterPatch = true
        val file = file()
        val budget = WorkBudget(SyncClock { clockMs.get() }, 1)
        assertEquals(ResumableResult.Progress, uploader.camera(file, 7, budget) { false })
        assertEquals(SyncConfig.RESUMABLE_CHUNK_BYTES, db.uploadSessionDao().forAccount(account.serverUrl, account.accountId).single().confirmedOffset)
        assertTrue(uploader.camera(file, 7, WorkBudget.UNLIMITED) { false } is ResumableResult.Complete)
        assertEquals(listOf(SyncConfig.RESUMABLE_CHUNK_BYTES.toInt(), 3), patches)
    }

    @Test fun budgetExhaustedDuringGetDoesNotStartNextBlock() = runBlocking {
        db.uploadSessionDao().insert(UploadSessionRecord(UUID.randomUUID().toString(), account.serverUrl, account.accountId,
            "CAMERA", "content://media/42", "content://media/42", 7, "photo.jpg", bytes.size.toLong(), 2,
            false, checksum, sessionId))
        advanceClockAfterGet = true
        val budget = WorkBudget(SyncClock { clockMs.get() }, 1)
        assertEquals(ResumableResult.Retry, uploader.camera(file(), 7, budget) { false })
        assertEquals(emptyList<Int>(), patches)
    }

    @Test fun confirmedRemoteProgressAfterRestartAvoidsBackoff() = runBlocking {
        db.uploadSessionDao().insert(UploadSessionRecord(UUID.randomUUID().toString(), account.serverUrl, account.accountId,
            "CAMERA", "content://media/42", "content://media/42", 7, "photo.jpg", bytes.size.toLong(), 2,
            false, checksum, sessionId))
        offset.set(SyncConfig.RESUMABLE_CHUNK_BYTES)
        advanceClockAfterGet = true
        val budget = WorkBudget(SyncClock { clockMs.get() }, 1)
        assertEquals(ResumableResult.Progress, uploader.camera(file(), 7, budget) { false })
        assertEquals(emptyList<Int>(), patches)
        assertEquals(offset.get(), db.uploadSessionDao().forAccount(account.serverUrl, account.accountId).single().confirmedOffset)
    }

    @Test fun changedSourceCancelsSessionBeforeFurtherTransfer() = runBlocking {
        advanceClockAfterPatch = true
        val file = file()
        uploader.camera(file, 7, WorkBudget(SyncClock { clockMs.get() }, 1)) { false }
        sourceState = CameraSourceState.Changed
        assertEquals(ResumableResult.SourceChanged, uploader.camera(file, 7, WorkBudget.UNLIMITED) { false })
        assertEquals(1, cancels.get())
        assertEquals(emptyList<UploadSessionRecord>(), db.uploadSessionDao().forAccount(account.serverUrl, account.accountId))
        assertEquals(listOf(SyncConfig.RESUMABLE_CHUNK_BYTES.toInt()), patches)
    }

    @Test fun noWifiKeepsRequestIdWithoutSendingBytes() = runBlocking {
        `when`(wifi.current()).thenReturn(null)
        assertEquals(ResumableResult.Retry, uploader.camera(file(), 7, WorkBudget.UNLIMITED) { false })
        assertEquals(0, creates.get())
        assertEquals(0, patches.size)
        assertEquals(1, db.uploadSessionDao().forAccount(account.serverUrl, account.accountId).size)
    }

    @Test fun missingCameraRowDoesNotDiscardSessionBeforeServerCancel() = runBlocking {
        val record = UploadSessionRecord(UUID.randomUUID().toString(), account.serverUrl, account.accountId,
            "CAMERA", "42", "content://media/42", 7, "photo.jpg", bytes.size.toLong(), 2,
            false, checksum, sessionId)
        db.uploadSessionDao().insert(record)
        assertTrue(uploader.cleanupOrphans())
        assertEquals(1, cancels.get())
        assertEquals(emptyList<UploadSessionRecord>(), db.uploadSessionDao().forAccount(account.serverUrl, account.accountId))
    }

    @Test fun patchBodyReopensAtSameOffsetAfterAuthenticationChallenge() = runBlocking {
        challengePatch = true
        assertTrue(uploader.camera(file(), 7, WorkBudget.UNLIMITED) { false } is ResumableResult.Complete)
        assertEquals(2, openedOffsets.count { it == 0L })
        assertEquals(listOf(SyncConfig.RESUMABLE_CHUNK_BYTES.toInt(), 3), patches)
    }

    @Test fun stoppingCoroutineCancelsBlockingHttpAndKeepsSession() = runBlocking {
        holdGet = true
        val job = launch(Dispatchers.IO) { uploader.camera(file(), 7, WorkBudget.UNLIMITED) { false } }
        assertTrue(withContext(Dispatchers.IO) { getStarted.await(5, TimeUnit.SECONDS) })
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(1, db.uploadSessionDao().forAccount(account.serverUrl, account.accountId).size)
    }

    private fun file() = MediaFile(
        mediaStoreId = 42, uri = "content://media/42", displayName = "photo.jpg", relativePath = "DCIM/Camera/",
        mimeType = "image/jpeg", size = bytes.size.toLong(), createdAt = 1, lastModified = 2,
        checksum = checksum, status = MediaFileStatus.PENDING_UPLOAD
    )

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.path ?: ""
        return when {
            request.method == "POST" && path == "/api/v1/upload-sessions" -> {
                creates.incrementAndGet()
                if (loseCreate) {
                    loseCreate = false
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                } else dto()
            }
            request.method == "GET" && path.endsWith(sessionId) -> {
                if (advanceClockAfterGet) clockMs.set(1)
                if (holdGet) {
                    getStarted.countDown()
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                } else dto()
            }
            request.method == "PATCH" && path.endsWith(sessionId) -> {
                if (challengePatch && request.getHeader("Authorization") == null) {
                    return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer")
                }
                assertEquals(offset.get().toString(), request.getHeader("Upload-Offset"))
                val length = request.body.size.toInt()
                patches.add(length)
                offset.addAndGet(length.toLong())
                if (advanceClockAfterPatch) clockMs.set(1)
                if (losePatch) {
                    losePatch = false
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                } else dto()
            }
            request.method == "POST" && path.endsWith("$sessionId/complete") -> {
                completes.incrementAndGet()
                completed = true
                if (loseComplete) {
                    loseComplete = false
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                } else dto()
            }
            request.method == "DELETE" && path.endsWith(sessionId) -> {
                cancels.incrementAndGet()
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{}")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun dto(): MockResponse = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
        .setBody("""{"id":"$sessionId","confirmedOffset":${offset.get()},"state":"${if (completed) "COMPLETED" else "UPLOADING"}","maxChunkSizeBytes":16777216,"result":${if (completed) """{"id":101,"checksum":"$checksum"}""" else "null"}}""")
}

package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.server.ServerRepository
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * UploadWorker запускается через WorkManager Test APIs в процессе без UI.
 * Обычный Application вместо App: нет Hilt-графа, MainActivity и NetworkViewModel, которые могли бы заранее
 * восстановить адрес; BaseUrlProvider в каждом тесте создаётся заново, как в холодном процессе.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadWorkerServerAddressTest {

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var serverPreferences: ServerPreferences
    private val syncStatusStore = testSyncStatusStore()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Network UI принимает только IP, поэтому сервер слушает 127.0.0.1, а не "localhost".
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        serverPreferences = ServerPreferences(context)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun savedAddressIsUsedWithoutUi() = runBlocking {
        serverPreferences.saveConnection("127.0.0.1", server.port.toString())
        val baseUrlProvider = BaseUrlProvider(serverPreferences)
        assertEquals("", baseUrlProvider.baseUrl)
        server.enqueue(pingOk())

        val result = worker(baseUrlProvider).doWork()

        assertEquals("/api/v1/test", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals(serverUrl(), baseUrlProvider.baseUrl)
        // Сервер доступен: прогон дошёл до проверки разрешения, без которого фоновая задача завершается success.
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun newlySavedAddressReplacesStaleAddressInMemory() = runBlocking {
        val baseUrlProvider = BaseUrlProvider(serverPreferences).apply { baseUrl = "http://127.0.0.1:1/" }
        serverPreferences.saveConnection("127.0.0.1", server.port.toString())
        server.enqueue(pingOk())

        val result = worker(baseUrlProvider).doWork()

        assertEquals(1, server.requestCount)
        assertEquals(serverUrl(), baseUrlProvider.baseUrl)
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun invalidSavedAddressIsNotUsedAndStaleAddressIsNotContacted() = runBlocking {
        // В памяти — рабочий адрес прежнего сервера, а сохранено некорректное значение.
        val baseUrlProvider = BaseUrlProvider(serverPreferences).apply { baseUrl = serverUrl() }
        serverPreferences.saveConnection("999.1.1.1", server.port.toString())

        val result = worker(baseUrlProvider).doWork()

        assertEquals(0, server.requestCount)
        assertEquals("", baseUrlProvider.baseUrl)
        assertEquals(ListenableWorker.Result.retry(), result)
        // Некорректный адрес — то же, что незаданный: итог прогона не пишется.
        assertNull(syncStatusStore.observeRunResults().first().uploaderProblem)
    }

    @Test
    fun missingSettingsAreNotReplacedByStaleAddress() = runBlocking {
        val emptyPreferences = mock(ServerPreferences::class.java)
        `when`(emptyPreferences.getIp()).thenReturn(flowOf(null))
        `when`(emptyPreferences.getPort()).thenReturn(flowOf(null))
        val baseUrlProvider = BaseUrlProvider(emptyPreferences).apply { baseUrl = serverUrl() }

        val result = worker(baseUrlProvider).doWork()

        assertEquals(0, server.requestCount)
        assertEquals(ListenableWorker.Result.retry(), result)
        // Отсутствие адреса — не недоступность Server: UI проверяет его по настройкам, итог не сохраняется.
        assertNull(syncStatusStore.observeRunResults().first().uploaderProblem)
    }

    @Test
    fun unavailableServerKeepsExistingRetry() = runBlocking {
        serverPreferences.saveConnection("127.0.0.1", server.port.toString())
        server.enqueue(MockResponse().setResponseCode(503))

        val result = worker(BaseUrlProvider(serverPreferences)).doWork()

        assertEquals(1, server.requestCount)
        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(SyncStatus.SERVER_UNAVAILABLE, syncStatusStore.observeRunResults().first().uploaderProblem?.status)
    }

    private fun worker(baseUrlProvider: BaseUrlProvider): UploadWorker {
        val client = OkHttpClient()
        val apiServiceFactory = ApiServiceFactory(baseUrlProvider, client, client, client)
        val folderRepository = FolderRepository(apiServiceFactory)
        val dao = db.mediaFileDao()
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ) = UploadWorker(
                appContext,
                workerParameters,
                ChecksumPrecheckRepository(dao, apiServiceFactory, folderRepository),
                FileUploadRepository(dao, apiServiceFactory, folderRepository, MediaStreamOpener(appContext)),
                ServerRepository(apiServiceFactory),
                baseUrlProvider,
                syncStatusStore,
                // Сессия есть: эти тесты проверяют адрес, а не остановку фона без токенов.
                InMemoryTokenStorage(Tokens("access", "refresh")),
                mock(PeriodicSyncCoordinator::class.java),
                SyncScheduler(appContext),
                SyncClock.SYSTEM
            )
        }
        return TestListenableWorkerBuilder<UploadWorker>(context).setWorkerFactory(factory).build()
    }

    private fun serverUrl() = "http://127.0.0.1:${server.port}/"

    private fun pingOk() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"message":"ok"}""")
}

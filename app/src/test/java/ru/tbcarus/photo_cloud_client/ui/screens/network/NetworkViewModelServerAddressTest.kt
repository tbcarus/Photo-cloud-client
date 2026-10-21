package ru.tbcarus.photo_cloud_client.ui.screens.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
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
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.server.ServerRepository
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.PeriodicSyncCoordinator
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import java.net.InetAddress

/** Открытый Network UI по-прежнему восстанавливает адрес и пересматривает фон — теперь через общее правило. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NetworkViewModelServerAddressTest {

    private lateinit var server: MockWebServer
    private lateinit var serverPreferences: ServerPreferences
    private lateinit var baseUrlProvider: BaseUrlProvider
    private lateinit var serverRepository: ServerRepository
    private val periodicSyncCoordinator = mock(PeriodicSyncCoordinator::class.java)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        serverPreferences = ServerPreferences(ApplicationProvider.getApplicationContext<Application>())
        baseUrlProvider = BaseUrlProvider(serverPreferences)
        val client = OkHttpClient()
        serverRepository = ServerRepository(ApiServiceFactory(baseUrlProvider, client, client, client))
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun openedUiRestoresSavedAddressAndReconcilesBackgroundSync() = runBlocking {
        serverPreferences.saveConnection("127.0.0.1", server.port.toString())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"message":"ok"}"""))

        val viewModel = NetworkViewModel(baseUrlProvider, serverPreferences, serverRepository, periodicSyncCoordinator)
        withTimeout(5_000) { viewModel.uiState.first { it.connectionStatus == ConnectionStatus.SUCCESS } }

        assertEquals("http://127.0.0.1:${server.port}/", baseUrlProvider.baseUrl)
        assertEquals("127.0.0.1", viewModel.uiState.value.ip)
        assertEquals(server.port.toString(), viewModel.uiState.value.port)
        verify(periodicSyncCoordinator).reconcile()
    }

    @Test
    fun invalidAddressInUiIsRejectedWithoutRequest() = runBlocking {
        serverPreferences.saveConnection("127.0.0.1", server.port.toString())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"message":"ok"}"""))
        val viewModel = NetworkViewModel(baseUrlProvider, serverPreferences, serverRepository, periodicSyncCoordinator)
        withTimeout(5_000) { viewModel.uiState.first { it.connectionStatus == ConnectionStatus.SUCCESS } }

        viewModel.onPortChange("70000")
        viewModel.testConnection()

        assertEquals(ConnectionStatus.ERROR, viewModel.uiState.value.connectionStatus)
        assertEquals(1, server.requestCount)
        // Сохранённый рабочий адрес не меняется из-за отклонённого ввода.
        assertEquals("http://127.0.0.1:${server.port}/", baseUrlProvider.baseUrl)
    }
}

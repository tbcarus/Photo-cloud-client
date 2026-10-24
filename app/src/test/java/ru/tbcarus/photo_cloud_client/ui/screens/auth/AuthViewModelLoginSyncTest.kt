package ru.tbcarus.photo_cloud_client.ui.screens.auth

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
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import ru.tbcarus.photo_cloud_client.auth.AuthRepository
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.PeriodicSyncCoordinator
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus

/** Login и logout пересматривают фон; после login Загрузчик сразу получает накопленную очередь. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelLoginSyncTest {

    private val server = MockWebServer()
    private val storage = InMemoryTokenStorage()
    private val coordinator = mock(PeriodicSyncCoordinator::class.java)
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply { baseUrl = server.url("/").toString() }
        val client = OkHttpClient()
        val repository = AuthRepository(storage, baseUrlProvider, ApiServiceFactory(baseUrlProvider, client, client, client))
        viewModel = AuthViewModel(repository, coordinator)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun loginReconcilesAndRequestsUpload() = runBlocking {
        server.enqueue(json("""{"accessToken":"access","refreshToken":"refresh"}"""))

        viewModel.login()
        withTimeout(5_000) { viewModel.uiState.first { it.status == ConnectionStatus.SUCCESS } }

        assertEquals(Tokens("access", "refresh"), storage.getTokens())
        val order = inOrder(coordinator)
        order.verify(coordinator).reconcile()
        order.verify(coordinator).requestUpload()
    }

    @Test
    fun logoutReconcilesWithoutRequestingUpload() = runBlocking {
        storage.saveTokens(Tokens("access", "refresh"))
        server.enqueue(json("""{"message":"Logged out"}"""))

        viewModel.logout()
        withTimeout(5_000) { viewModel.uiState.first { it.status == ConnectionStatus.SUCCESS } }

        verify(coordinator).reconcile()
        verify(coordinator, never()).requestUpload()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}

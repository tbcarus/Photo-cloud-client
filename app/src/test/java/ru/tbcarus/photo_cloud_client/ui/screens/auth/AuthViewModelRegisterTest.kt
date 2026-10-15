package ru.tbcarus.photo_cloud_client.ui.screens.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import ru.tbcarus.photo_cloud_client.auth.AuthRepository
import ru.tbcarus.photo_cloud_client.auth.AuthUiState
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.PeriodicSyncCoordinator
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelRegisterTest {

    private companion object {
        const val EMAIL = "user@test.local"
        const val PASSWORD = "pass1"
    }

    private val server = MockWebServer()
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        // viewModelScope работает на Main; Unconfined выполняет корутины сразу, без ручного продвижения времени.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        val baseUrlProvider = BaseUrlProvider().apply { baseUrl = server.url("/").toString() }
        // Короткий readTimeout имитирует клиентский таймаут без долгого ожидания.
        val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        val repository = AuthRepository(NoTokenStorage, baseUrlProvider, ApiServiceFactory(baseUrlProvider, client, client))
        // Координатор нужен только login/logout; в регистрации не вызывается.
        viewModel = AuthViewModel(repository, mock(PeriodicSyncCoordinator::class.java))
        viewModel.onEmailChange(EMAIL)
        viewModel.onPasswordChange(PASSWORD)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun secondRegisterWhileRequestIsInFlightSendsNoSecondPost() {
        // В очереди два ответа: без проверки в ViewModel второй POST дошёл бы до сервера.
        server.enqueue(created().setHeadersDelay(200, TimeUnit.MILLISECONDS))
        server.enqueue(created())

        viewModel.register()
        viewModel.register()
        assertEquals(ConnectionStatus.LOADING, viewModel.uiState.value.status)

        assertEquals(ConnectionStatus.SUCCESS, awaitCompletion().status)
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("POST", request!!.method)
        assertEquals("/api/v1/auth/register", request.path)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun successShowsInstructionWithEmail() {
        server.enqueue(created())

        viewModel.register()

        val state = awaitCompletion()
        assertEquals(ConnectionStatus.SUCCESS, state.status)
        val message = state.message!!
        assertTrue(message, message.contains(EMAIL))
        assertTrue(message, message.contains("Спам"))
        assertTrue(message, message.contains("Login"))
        assertNull(state.repeatRegistrationEmail)
    }

    @Test
    fun repeatRegistrationOfSameEmailRequiresConfirmation() {
        server.enqueue(created())
        viewModel.register()
        awaitCompletion()
        viewModel.clearMessage()

        // Server приводит email к нижнему регистру, поэтому регистр не отличает адрес.
        val sameEmail = EMAIL.uppercase()
        viewModel.onEmailChange(sameEmail)
        viewModel.register()
        assertEquals(sameEmail, viewModel.uiState.value.repeatRegistrationEmail)
        assertEquals(1, server.requestCount)

        viewModel.cancelRepeatRegistration()
        assertNull(viewModel.uiState.value.repeatRegistrationEmail)
        assertEquals(1, server.requestCount)

        server.enqueue(created())
        viewModel.register()
        viewModel.confirmRepeatRegistration()

        val state = awaitCompletion()
        assertEquals(ConnectionStatus.SUCCESS, state.status)
        assertNull(state.repeatRegistrationEmail)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun registrationOfAnotherEmailDoesNotRequireConfirmation() {
        server.enqueue(created())
        server.enqueue(created())
        viewModel.register()
        awaitCompletion()
        viewModel.clearMessage()

        viewModel.onEmailChange("other@test.local")
        viewModel.register()

        assertNull(viewModel.uiState.value.repeatRegistrationEmail)
        val state = awaitCompletion()
        assertEquals(ConnectionStatus.SUCCESS, state.status)
        assertTrue(state.message!!.contains("other@test.local"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun timeoutShowsUnknownResultAndDoesNotRetry() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        viewModel.register()

        val state = awaitCompletion()
        assertEquals(ConnectionStatus.ERROR, state.status)
        val message = state.message!!
        assertTrue(message, message.contains("результат регистрации неизвестен"))
        assertTrue(message, message.contains(EMAIL))
        assertTrue(message, message.contains("проверьте почту"))
        assertFalse(message, message.contains("timeout", ignoreCase = true))
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)

        // После ошибки Register снова доступен; 201 не было, поэтому подтверждение не требуется.
        viewModel.clearMessage()
        server.enqueue(created())
        viewModel.register()
        assertEquals(ConnectionStatus.SUCCESS, awaitCompletion().status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun httpErrorsStillShowServerText() {
        val cases = listOf(
            Triple(400, errorBody("VALIDATION_ERROR", "Validation failed", """{"email":"Email must be valid"}"""),
                "email: Email must be valid"),
            Triple(409, errorBody("CONFLICT", "Account with this email is already activated. Please log in"),
                "Account with this email is already activated. Please log in"),
            Triple(429, errorBody("TOO_MANY_REQUESTS", "Registration attempts limit reached. Try again in 5 min."),
                "Registration attempts limit reached. Try again in 5 min."),
            Triple(503, errorBody("EMAIL_NOT_SENT", "The verification email could not be sent."),
                "The verification email could not be sent.")
        )

        for ((status, body, expected) in cases) {
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            viewModel.register()

            val state = awaitCompletion()
            assertEquals("HTTP $status", ConnectionStatus.ERROR, state.status)
            assertEquals("HTTP $status", expected, state.message)
            viewModel.clearMessage()
        }
        assertEquals(cases.size, server.requestCount)
    }

    private fun awaitCompletion(): AuthUiState = runBlocking {
        withTimeout(5_000) { viewModel.uiState.first { it.status != ConnectionStatus.LOADING } }
    }

    private fun created() = MockResponse().setResponseCode(201).setBody("""{"message":"Email was sent"}""")

    private fun errorBody(code: String, message: String, fieldErrors: String = "null") =
        """{"id":"2e7f5f91-9a9f-4e5d-b5b9-1fb1c72df9ea","code":"$code","message":"$message","fieldErrors":$fieldErrors}"""

    private object NoTokenStorage : TokenStorage {
        override val tokensFlow = MutableStateFlow<Tokens?>(null)
        override fun getTokens(): Tokens? = null
        override fun saveTokens(tokens: Tokens) = Unit
        override fun clear() = Unit
    }
}

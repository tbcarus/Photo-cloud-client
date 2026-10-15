package ru.tbcarus.photo_cloud_client.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.auth.AuthRepository
import ru.tbcarus.photo_cloud_client.auth.AuthUiState
import ru.tbcarus.photo_cloud_client.media.PeriodicSyncCoordinator
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import ru.tbcarus.photo_cloud_client.utils.JwtUtils
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repo: AuthRepository,
    private val periodicSyncCoordinator: PeriodicSyncCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState

    init {
        viewModelScope.launch {
            repo.tokensFlow.collect { tokens ->
                _uiState.update {
                    it.copy(
                        isLoggedIn = tokens != null,
                        userEmail = tokens?.accessToken?.let { token ->
                            JwtUtils.getSubject(token)
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            verifySession()
        }
    }

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }
    fun clearMessage() = _uiState.update { it.copy(message = null) }

    private fun updateStatus(status: ConnectionStatus, message: String? = null) =
        _uiState.update { it.copy(status = status, message = message) }

    private fun notReady(): Boolean {
        return if (!repo.isReady()) {
            updateStatus(ConnectionStatus.ERROR, "Адрес подключения не настроен")
            true
        } else false
    }

    fun register() {
        // Проверка в ViewModel: кнопка блокируется только после рекомпозиции, второй клик может успеть раньше.
        if (uiState.value.status == ConnectionStatus.LOADING) return
        if (notReady()) return
        val email = uiState.value.email
        if (email.equals(uiState.value.registeredEmail, ignoreCase = true)) {
            _uiState.update { it.copy(repeatRegistrationEmail = email) }
            return
        }
        sendRegistration(email)
    }

    fun confirmRepeatRegistration() {
        val email = uiState.value.repeatRegistrationEmail ?: return
        cancelRepeatRegistration()
        if (uiState.value.status == ConnectionStatus.LOADING) return
        if (notReady()) return
        sendRegistration(email)
    }

    fun cancelRepeatRegistration() = _uiState.update { it.copy(repeatRegistrationEmail = null) }

    private fun sendRegistration(email: String) {
        updateStatus(ConnectionStatus.LOADING)
        viewModelScope.launch {
            repo.register(email, uiState.value.password)
                .onSuccess {
                    _uiState.update { it.copy(registeredEmail = email) }
                    updateStatus(ConnectionStatus.SUCCESS, registrationSentMessage(email))
                }
                .onFailure { e ->
                    // Без ответа сервера исход неизвестен: регистрация и письмо могли пройти. POST не повторяем.
                    val message = if (e is IOException) registrationUnknownResultMessage(email)
                    else e.localizedMessage ?: "Ошибка"
                    updateStatus(ConnectionStatus.ERROR, message)
                }
        }
    }

    private fun registrationSentMessage(email: String) =
        "Письмо для подтверждения регистрации отправлено на $email.\n\n" +
            "Проверьте почту, в том числе папку «Спам», и откройте ссылку из письма. " +
            "После подтверждения войдите в приложение кнопкой Login."

    private fun registrationUnknownResultMessage(email: String) =
        "Не удалось получить ответ сервера, поэтому результат регистрации неизвестен. " +
            "Сервер мог сохранить регистрацию и отправить письмо на $email.\n\n" +
            "Сначала проверьте почту, в том числе папку «Спам». " +
            "Повторная регистрация аннулирует ссылку из ранее отправленного письма."

    fun login() {
        if (notReady()) return
        updateStatus(ConnectionStatus.LOADING)
        viewModelScope.launch {
            repo.login(uiState.value.email, uiState.value.password)
                .onSuccess { tokens ->
                    repo.saveTokens(tokens)
                    // Токены появились — включаем periodic sync (от текущего состояния).
                    periodicSyncCoordinator.reconcile()
                    updateStatus(ConnectionStatus.SUCCESS, "Login successful")
                }
                .onFailure { updateStatus(ConnectionStatus.ERROR, it.localizedMessage ?: "Ошибка входа") }
        }
    }

    fun testAuth() {
        if (notReady()) return
        updateStatus(ConnectionStatus.LOADING)
        viewModelScope.launch {
            repo.testAuth()
                .onSuccess { updateStatus(ConnectionStatus.SUCCESS, it) }
                .onFailure { updateStatus(ConnectionStatus.ERROR, it.localizedMessage ?: "Ошибка подключения") }
        }
    }

    fun verifySession() {
        if (!repo.isReady()) return
        if (repo.getTokens() == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isVerifying = true) }
            try {
                repo.testAuth()
            } finally {
                _uiState.update { it.copy(isVerifying = false) }
            }
        }
    }

    fun logout() {
        if (notReady()) return
        updateStatus(ConnectionStatus.LOADING)
        viewModelScope.launch {
            repo.logout()
                .onSuccess { updateStatus(ConnectionStatus.SUCCESS, "Logged out successfully") }
                .onFailure { updateStatus(ConnectionStatus.ERROR, it.localizedMessage ?: "Logout error") }
            // logout() очищает токены в обоих исходах — переоцениваем periodic sync (отменяем).
            periodicSyncCoordinator.reconcile()
        }
    }
}

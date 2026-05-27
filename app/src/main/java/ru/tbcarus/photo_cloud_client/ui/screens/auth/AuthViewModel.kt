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
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import ru.tbcarus.photo_cloud_client.utils.JwtUtils
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repo: AuthRepository
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
        if (notReady()) return
        updateStatus(ConnectionStatus.LOADING)
        viewModelScope.launch {
            repo.register(uiState.value.email, uiState.value.password)
                .onSuccess { updateStatus(ConnectionStatus.SUCCESS, it) }
                .onFailure { updateStatus(ConnectionStatus.ERROR, it.localizedMessage ?: "Ошибка") }
        }
    }

    fun login() {
        if (notReady()) return
        updateStatus(ConnectionStatus.LOADING)
        viewModelScope.launch {
            repo.login(uiState.value.email, uiState.value.password)
                .onSuccess { tokens ->
                    repo.saveTokens(tokens)
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
        }
    }
}

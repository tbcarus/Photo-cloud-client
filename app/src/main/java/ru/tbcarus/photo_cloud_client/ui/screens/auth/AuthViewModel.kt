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
import ru.tbcarus.photo_cloud_client.auth.Tokens
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
            refreshTokensOverview()
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
                    refreshTokensOverview()
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
                .onSuccess { refreshTokensOverview(); updateStatus(ConnectionStatus.SUCCESS, it) }
                .onFailure { updateStatus(ConnectionStatus.ERROR, it.localizedMessage ?: "Ошибка подключения") }
            refreshTokensOverview()
        }
    }

    fun verifySession() {
        if (!repo.isReady()) { refreshTokensOverview(); return }
        if (repo.getTokens() == null) { _uiState.update { it.copy(isLoggedIn = false) }; return }
        viewModelScope.launch {
            runCatching { repo.testAuth() }
            refreshTokensOverview()
        }
    }

    fun logout() {
        if (notReady()) return
        updateStatus(ConnectionStatus.LOADING)
        viewModelScope.launch {
            repo.logout()
                .onSuccess { refreshTokensOverview(); updateStatus(ConnectionStatus.SUCCESS, "Logged out successfully") }
                .onFailure { updateStatus(ConnectionStatus.ERROR, it.localizedMessage ?: "Logout error") }
        }
    }

    fun refreshTokensOverview() {
        val tokens = repo.getTokens()
        val access = tokens?.accessToken
        val refresh = tokens?.refreshToken
        _uiState.update {
            it.copy(
                savedAccessToken = access,
                savedRefreshToken = refresh,
                isAccessValid = !JwtUtils.isExpired(access.toString()),
                isRefreshValid = !JwtUtils.isExpired(refresh.toString()),
                isLoggedIn = tokens != null,
                userEmail = access?.let { JwtUtils.getSubject(it) }
            )
        }
    }
}

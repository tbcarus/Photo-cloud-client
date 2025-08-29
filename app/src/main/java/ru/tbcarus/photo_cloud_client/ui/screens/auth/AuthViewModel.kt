package ru.tbcarus.photo_cloud_client.ui.screens.auth

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.api.ApiClient
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.models.AuthRequest
import ru.tbcarus.photo_cloud_client.api.models.AuthUiState
import ru.tbcarus.photo_cloud_client.api.models.RefreshTokenRequest
import ru.tbcarus.photo_cloud_client.auth.EncryptedPrefsTokenStorage
import ru.tbcarus.photo_cloud_client.auth.TokenStorage
import ru.tbcarus.photo_cloud_client.auth.Tokens
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import ru.tbcarus.photo_cloud_client.utils.AppPreferences
import ru.tbcarus.photo_cloud_client.utils.JwtUtils
import ru.tbcarus.photo_cloud_client.utils.getHttpStatusDescription
import kotlin.text.clear


class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext

    private var baseUrl: String = ""
    private var isBaseUrlReady = false

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState

    private val tokenStorage: TokenStorage = EncryptedPrefsTokenStorage(context)

    init {
        viewModelScope.launch {
            val ip = preferences.getIp(context).first()
            val port = preferences.getPort(context).first()
            baseUrl = "http://$ip:$port/"
            isBaseUrlReady = true
            refreshTokensOverview()
        }
    }

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun updateStatus(status: ConnectionStatus, message: String? = null) {
        _uiState.update { it.copy(status = status, message = message) }
    }

    private fun checkBaseUrlReady(): Boolean {
        if (!isBaseUrlReady) {
            updateStatus(ConnectionStatus.ERROR, "Адрес подключения ещё не готов")
            return false
        }
        return true
    }

    fun register() {
        if (!checkBaseUrlReady()) return
        val request = AuthRequest(uiState.value.email, uiState.value.password)
        updateStatus(ConnectionStatus.LOADING)
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)


        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    service.register(request).execute()
                }
                if (response.isSuccessful) {
                    val message = response.body()?.get("message") ?: "Registered"
                    updateStatus(ConnectionStatus.SUCCESS, message)
                } else {
                    val error = response.errorBody()?.string() ?: "Unknown error"
                    updateStatus(ConnectionStatus.ERROR, error)
                }
            } catch (e: Exception) {
                updateStatus(ConnectionStatus.ERROR, e.localizedMessage ?: "Ошибка")
            }
        }
    }

    fun login() {
        if (!checkBaseUrlReady()) return
        val request = AuthRequest(uiState.value.email, uiState.value.password)
        updateStatus(ConnectionStatus.LOADING)
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    service.login(request).execute()
                }
                if (response.isSuccessful) {
                    val auth = response.body()
                    if (auth != null) {
                        tokenStorage.saveTokens(Tokens(auth.accessToken, auth.refreshToken))
                        refreshTokensOverview()
                    }
                    updateStatus(ConnectionStatus.SUCCESS, "Login successful")
                } else {
                    val error = response.errorBody()?.string() ?: "Unknown error"
                    updateStatus(ConnectionStatus.ERROR, error)
                }
            } catch (e: Exception) {
                updateStatus(ConnectionStatus.ERROR, e.localizedMessage ?: "Ошибка входа")
            }
        }
    }

    fun testAuth() {
        if (!checkBaseUrlReady()) return
        updateStatus(ConnectionStatus.LOADING)
        val service = ApiClient.getClientWithAuth(baseUrl, tokenStorage).create(AuthService::class.java)

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    service.testAuth("").execute()
                }
                if (response.isSuccessful) {
                    val body = response.body()
                    val message = body?.message ?: "OK"
                    updateStatus(ConnectionStatus.SUCCESS, message)
                } else {
                    updateStatus(ConnectionStatus.ERROR, getHttpStatusDescription(response.code()))
                }
            } catch (e: Exception) {
                updateStatus(ConnectionStatus.ERROR, e.localizedMessage ?: "Ошибка подключения")
            }
        }
    }

    fun logout() {
        if (!checkBaseUrlReady()) return
        updateStatus(ConnectionStatus.LOADING)

        val authedService = ApiClient.getClientWithAuth(baseUrl, tokenStorage).create(AuthService::class.java)

        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    // серверная ручка принимает refreshToken — формируем тело
                    val refresh = tokenStorage.getTokens()?.refreshToken ?: ""
                    authedService.logout(ru.tbcarus.photo_cloud_client.api.models.LogoutRequest(refresh)).execute().isSuccessful
                }
                if (ok) {
                    tokenStorage.clear()
                    refreshTokensOverview()
                    updateStatus(ConnectionStatus.SUCCESS, "Logged out successfully")
                } else {
                    updateStatus(ConnectionStatus.ERROR, "Logout failed")
                }
            } catch (e: Exception) {
                updateStatus(ConnectionStatus.ERROR, e.localizedMessage ?: "Logout error")
            }
        }
    }

    fun refreshTokensOverview() {
        val tokens = tokenStorage.getTokens()
        val access = tokens?.accessToken
        val refresh = tokens?.refreshToken
        _uiState.update {
            it.copy(
                savedAccessToken = access,
                savedRefreshToken = refresh,
                isAccessValid = !JwtUtils.isExpired(access.toString()),
                isRefreshValid = !JwtUtils.isExpired(refresh.toString())
            )
        }
    }

    private fun manualRefreshToken() {
        if (!checkBaseUrlReady()) return
        val refresh = tokenStorage.getTokens()?.refreshToken ?: return
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { service.refreshToken(RefreshTokenRequest(refresh)).execute() }
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        tokenStorage.saveTokens(Tokens(body.accessToken, body.refreshToken))
                        refreshTokensOverview()
                        updateStatus(ConnectionStatus.SUCCESS, "Token refreshed")
                    } else {
                        updateStatus(ConnectionStatus.ERROR, "Empty refresh response")
                    }
                } else {
                    tokenStorage.clear()
                    refreshTokensOverview()
                    updateStatus(ConnectionStatus.ERROR, "Сессия устарела")
                }
            } catch (_: Exception) {
                updateStatus(ConnectionStatus.ERROR, "Ошибка обновления токена")
            }
        }
    }
}

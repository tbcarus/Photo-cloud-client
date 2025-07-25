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
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import ru.tbcarus.photo_cloud_client.utils.AppPreferences
import ru.tbcarus.photo_cloud_client.utils.getHttpStatusDescription


class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext

    private var baseUrl: String = ""
    private var isBaseUrlReady = false

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState

    private var accessToken: String? = null
    private var refreshToken: String? = null

    init {
        viewModelScope.launch {
            val ip = preferences.getIp(context).first()
            val port = preferences.getPort(context).first()
            baseUrl = "http://$ip:$port/"
            isBaseUrlReady = true
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
                    accessToken = auth?.accessToken
                    refreshToken = auth?.refreshToken
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
        val token = accessToken ?: ""
        updateStatus(ConnectionStatus.LOADING)
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    service.testAuth("Bearer $token").execute()
                }
                if (response.isSuccessful) {
                    val body = response.body()
                    val message = body?.message ?: "OK"
                    updateStatus(ConnectionStatus.SUCCESS, message)
                } else if (response.code() == 401 && refreshToken != null) {
                    refreshToken()
                } else {
                    updateStatus(ConnectionStatus.ERROR, getHttpStatusDescription(response.code()))
                }
            } catch (e: Exception) {
                updateStatus(ConnectionStatus.ERROR, e.localizedMessage ?: "Ошибка подключения")
            }
        }
    }

    private fun refreshToken() {
        val refresh = refreshToken ?: return
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)

        viewModelScope.launch {
            try {
                val response = service.refreshToken(RefreshTokenRequest(refresh))
                if (response.isSuccessful) {
                    accessToken = response.body()?.accessToken
                    refreshToken = response.body()?.refreshToken
                    testAuth()
                } else {
                    updateStatus(ConnectionStatus.ERROR, "Сессия устарела")
                }
            } catch (e: Exception) {
                updateStatus(ConnectionStatus.ERROR, "Ошибка обновления токена")
            }
        }
    }
}

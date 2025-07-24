package ru.tbcarus.photo_cloud_client.ui.screens.auth

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.api.ApiClient
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.models.AuthRequest
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
    init {
        viewModelScope.launch {
            val ip = preferences.getIp(context).first()
            val port = preferences.getPort(context).first()
            baseUrl = "http://$ip:$port/"
            isBaseUrlReady = true
        }
    }

    var email = MutableStateFlow("")
    var password = MutableStateFlow("")
    private var accessToken: String? = null
    private var refreshToken: String? = null

    private val _status = MutableStateFlow(ConnectionStatus.NONE)
    val status: StateFlow<ConnectionStatus> = _status

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun onEmailChange(value: String) {
        email.value = value
    }

    fun onPasswordChange(value: String) {
        password.value = value
    }

    private fun checkBaseUrlReady(): Boolean {
        if (!isBaseUrlReady) {
            _status.value = ConnectionStatus.ERROR
            _message.value = "Адрес подключения ещё не готов"
            return false
        }
        return true
    }

    fun register() {
        if (!checkBaseUrlReady()) return
        _status.value = ConnectionStatus.LOADING
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)
        val request = AuthRequest(email.value, password.value)

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    service.register(request).execute()
                }
                if (response.isSuccessful) {
                    _status.value = ConnectionStatus.SUCCESS
                    _message.value = response.body()?.get("message") ?: "Registered"
                } else {
                    val error = response.errorBody()?.string() ?: "Unknown error"
                    _status.value = ConnectionStatus.ERROR
                    _message.value = error
                }
            } catch (e: Exception) {
                _status.value = ConnectionStatus.ERROR
                _message.value = e.localizedMessage ?: "Exception occurred"
            }
        }
    }

    fun login() {
        if (!checkBaseUrlReady()) return
        _status.value = ConnectionStatus.LOADING
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)
        val request = AuthRequest(email.value, password.value)

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    service.login(request).execute()
                }
                if (response.isSuccessful) {
                    val auth = response.body()
                    accessToken = auth?.accessToken
                    refreshToken = auth?.refreshToken
                    _status.value = ConnectionStatus.SUCCESS
                    _message.value = "Login successful"
                } else {
                    val error = response.errorBody()?.string() ?: "Unknown error"
                    _status.value = ConnectionStatus.ERROR
                    _message.value = error
                }
            } catch (e: Exception) {
                _status.value = ConnectionStatus.ERROR
                _message.value = e.localizedMessage ?: "Exception occurred"
            }
        }
    }

    fun testAuth() {
        if (!checkBaseUrlReady()) return
        val token = accessToken ?: ""
        _status.value = ConnectionStatus.LOADING
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    service.testAuth("Bearer $token").execute()
                }
                if (response.isSuccessful) {
                    val body = response.body()
                    val message = body?.message ?: "OK"
                    _status.value = ConnectionStatus.SUCCESS
                    _message.value = message
                } else if (response.code() == 401 && refreshToken != null) {
                    refreshToken()
                } else {
                    _status.value = ConnectionStatus.ERROR
                    _message.value = getHttpStatusDescription(response.code())
                }
            } catch (e: Exception) {
                _status.value = ConnectionStatus.ERROR
                _message.value = e.localizedMessage ?: "Connection error"
            }
        }
    }

    private fun refreshToken() {
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)
        val refresh = refreshToken ?: return

        viewModelScope.launch {
            try {
                val response = service.refreshToken(RefreshTokenRequest(refresh))
                if (response.isSuccessful) {
                    accessToken = response.body()?.accessToken
                    refreshToken = response.body()?.refreshToken
                    testAuth()
                } else {
                    _status.value = ConnectionStatus.ERROR
                    _message.value = "Session expired"
                }
            } catch (e: Exception) {
                _status.value = ConnectionStatus.ERROR
                _message.value = "Failed to refresh token"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

package ru.tbcarus.photo_cloud_client.ui.screens.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import ru.tbcarus.photo_cloud_client.api.ApiClient
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.models.AuthRequest
import ru.tbcarus.photo_cloud_client.api.models.AuthResponse
import ru.tbcarus.photo_cloud_client.api.models.RefreshTokenRequest
import ru.tbcarus.photo_cloud_client.api.models.TestResponse
import ru.tbcarus.photo_cloud_client.utils.AppPreferences
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import java.io.IOException


class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences
    private val context = application.applicationContext

    var email = MutableStateFlow("")
    var password = MutableStateFlow("")
    private var accessToken: String? = null
    private var refreshToken: String? = null

    private val _status = MutableStateFlow(ConnectionStatus.NONE)
    val status: StateFlow<ConnectionStatus> = _status

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun onEmailChange(value: String) { email.value = value }
    fun onPasswordChange(value: String) { password.value = value }

    fun register(baseUrl: String) {
        _status.value = ConnectionStatus.LOADING
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)
        val request = AuthRequest(email.value, password.value)

        viewModelScope.launch {
            try {
                val response = service.register(request)
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

    fun login(baseUrl: String) {
        _status.value = ConnectionStatus.LOADING
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)
        val request = AuthRequest(email.value, password.value)

        viewModelScope.launch {
            try {
                val response = service.login(request)
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

    fun testAuth(baseUrl: String) {
        _status.value = ConnectionStatus.LOADING
        val token = accessToken ?: return
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)

        viewModelScope.launch {
            try {
                val response = service.testAuth("Bearer $token")
                if (response.isSuccessful) {
                    val message = response.body()?.message ?: "OK"
                    _status.value = ConnectionStatus.SUCCESS
                    _message.value = message
                } else if (response.code() == 401 && refreshToken != null) {
                    refreshToken(baseUrl)
                } else {
                    _status.value = ConnectionStatus.ERROR
                    _message.value = response.errorBody()?.string() ?: "Unauthorized"
                }
            } catch (e: Exception) {
                _status.value = ConnectionStatus.ERROR
                _message.value = e.localizedMessage ?: "Connection error"
            }
        }
    }

    private fun refreshToken(baseUrl: String) {
        val service = ApiClient.getClient(baseUrl).create(AuthService::class.java)
        val refresh = refreshToken ?: return

        viewModelScope.launch {
            try {
                val response = service.refreshToken(RefreshTokenRequest(refresh))
                if (response.isSuccessful) {
                    accessToken = response.body()?.accessToken
                    refreshToken = response.body()?.refreshToken
                    testAuth(baseUrl)
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

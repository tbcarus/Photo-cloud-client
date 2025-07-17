package ru.tbcarus.photo_cloud_client.ui.screens.network

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import retrofit2.HttpException
import ru.tbcarus.photo_cloud_client.api.ApiClient
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import ru.tbcarus.photo_cloud_client.utils.isValidIpAddress
import java.io.IOException

data class NetworkUiState(
    val ip: String = "",
    val port: String = "",
    val isLoading: Boolean = false,
    val message: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.NONE
)

class NetworkViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(NetworkUiState())
    val uiState: StateFlow<NetworkUiState> = _uiState

    fun onIpChange(newIp: String) {
        _uiState.update { it.copy(ip = newIp) }
    }

    fun onPortChange(newPort: String) {
        _uiState.update { it.copy(port = newPort) }
    }

    fun testConnection() {
        val ip = uiState.value.ip.trim()
        val port = uiState.value.port.trim()

        if (!isValidIpAddress(ip) || port.isEmpty()) {
            _uiState.update {
                it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    message = "Введите корректные IP и порт"
                )
            }
            return
        }

        val baseUrl = "http://$ip:$port/"
        val api = ApiClient.getClient(baseUrl).create(AuthService::class.java)

        _uiState.update { it.copy(isLoading = true, message = null) }

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    api.testServer().execute()
                }

                if (response.isSuccessful) {
                    val message = response.body()?.message ?: "Успешное подключение"
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            connectionStatus = ConnectionStatus.SUCCESS,
                            message = message
                        )
                    }
                } else {
                    val error = response.errorBody()?.string() ?: "Неизвестная ошибка"
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            connectionStatus = ConnectionStatus.ERROR,
                            message = error
                        )
                    }
                }
            } catch (e: IOException) {
                Log.e("NetworkViewModel", "Ошибка сети", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        connectionStatus = ConnectionStatus.ERROR,
                        message = "Ошибка сети: ${e.localizedMessage}"
                    )
                }
            } catch (e: HttpException) {
                Log.e("NetworkViewModel", "HTTP ошибка", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        connectionStatus = ConnectionStatus.ERROR,
                        message = "Ошибка HTTP: ${e.code()}"
                    )
                }
            } catch (e: Exception) {
                Log.e("NetworkViewModel", "Другая ошибка", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        connectionStatus = ConnectionStatus.ERROR,
                        message = "Неизвестная ошибка: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}

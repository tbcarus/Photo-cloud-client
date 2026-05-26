package ru.tbcarus.photo_cloud_client.ui.screens.network

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.server.ServerRepository
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import ru.tbcarus.photo_cloud_client.utils.isValidIpAddress
import javax.inject.Inject

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
    private val serverPreferences: ServerPreferences,
    private val serverRepository: ServerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NetworkUiState())
    val uiState: StateFlow<NetworkUiState> = _uiState

    init {
        viewModelScope.launch {
            val ip = serverPreferences.getIp().first()
            val port = serverPreferences.getPort().first()
            if (!ip.isNullOrEmpty() && !port.isNullOrEmpty()) {
                baseUrlProvider.baseUrl = "http://$ip:$port/"
                _uiState.update { it.copy(ip = ip, port = port) }
                testConnection()
            }
        }
    }

    fun onIpChange(newIp: String) = _uiState.update { it.copy(ip = newIp) }
    fun onPortChange(newPort: String) = _uiState.update { it.copy(port = newPort) }
    fun clearMessage() = _uiState.update { it.copy(message = null) }

    fun testConnection() {
        val ip = uiState.value.ip.trim()
        val port = uiState.value.port.trim()

        if (!isValidIpAddress(ip) || port.isEmpty()) {
            _uiState.update {
                it.copy(connectionStatus = ConnectionStatus.ERROR, message = "Введите корректные IP и порт")
            }
            return
        }

        val baseUrl = "http://$ip:$port/"
        Log.d("BASE_URL", baseUrl)

        _uiState.update { it.copy(isLoading = true, message = null) }

        viewModelScope.launch {
            val result = serverRepository.testConnection(baseUrl)
            result.fold(
                onSuccess = { message ->
                    serverPreferences.saveConnection(ip, port)
                    baseUrlProvider.baseUrl = baseUrl
                    _uiState.update {
                        it.copy(isLoading = false, connectionStatus = ConnectionStatus.SUCCESS, message = message)
                    }
                },
                onFailure = { error ->
                    Log.e("NetworkViewModel", "Ошибка подключения", error)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            connectionStatus = ConnectionStatus.ERROR,
                            message = error.localizedMessage ?: "Неизвестная ошибка"
                        )
                    }
                }
            )
        }
    }
}

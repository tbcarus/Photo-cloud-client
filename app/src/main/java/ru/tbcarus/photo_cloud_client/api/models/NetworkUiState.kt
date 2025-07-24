package ru.tbcarus.photo_cloud_client.api.models

import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus

data class NetworkUiState(
    val ip: String = "",
    val port: String = "",
    val isLoading: Boolean = false,
    val message: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.NONE
)

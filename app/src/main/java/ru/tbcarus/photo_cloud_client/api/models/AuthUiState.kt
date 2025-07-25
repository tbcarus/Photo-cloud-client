package ru.tbcarus.photo_cloud_client.api.models

import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val status: ConnectionStatus = ConnectionStatus.NONE,
    val message: String? = null
)

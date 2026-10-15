package ru.tbcarus.photo_cloud_client.auth

import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val status: ConnectionStatus = ConnectionStatus.NONE,
    val message: String? = null,
    val isLoggedIn: Boolean = false,
    val userEmail: String? = null,
    val isVerifying: Boolean = false,
    // Email, для которого на этом экране получен 201: повторная регистрация аннулирует ссылку из письма.
    val registeredEmail: String? = null,
    // Не null — показать подтверждение повторной регистрации этого email.
    val repeatRegistrationEmail: String? = null
)

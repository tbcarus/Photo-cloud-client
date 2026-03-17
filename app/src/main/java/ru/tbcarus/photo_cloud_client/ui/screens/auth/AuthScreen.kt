package ru.tbcarus.photo_cloud_client.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.tbcarus.photo_cloud_client.api.models.AuthUiState
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import ru.tbcarus.photo_cloud_client.ui.components.LoadingDialog
import ru.tbcarus.photo_cloud_client.ui.components.showDialog

@Composable
fun AuthScreen(
    viewModel: AuthViewModel = viewModel()
) {
    val state = viewModel.uiState.collectAsState().value

    LaunchedEffect(Unit) {
        viewModel.verifySession()
    }

    Column(modifier = Modifier.padding(16.dp)) {
        if (state.isLoggedIn) {
            ProfileContent(
                state = state,
                onLogout = viewModel::logout,
                onTestAuth = viewModel::testAuth
            )
        } else {
            LoginContent(
                state = state,
                onEmailChange = viewModel::onEmailChange,
                onPasswordChange = viewModel::onPasswordChange,
                onRegister = viewModel::register,
                onLogin = viewModel::login,
                onTestAuth = viewModel::testAuth
            )
        }
    }

    if (state.status == ConnectionStatus.LOADING) {
        LoadingDialog()
    }

    state.message?.let {
        showDialog(message = it, status = state.status) {
            viewModel.clearMessage()
        }
    }
}

@Composable
private fun LoginContent(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRegister: () -> Unit,
    onLogin: () -> Unit,
    onTestAuth: () -> Unit
) {
    Text("Вход / Регистрация", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = state.email,
        onValueChange = onEmailChange,
        label = { Text("Email") },
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = state.password,
        onValueChange = onPasswordChange,
        label = { Text("Password") },
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Button(onClick = onRegister) { Text("Register") }
        Button(onClick = onLogin) { Text("Login") }
        Button(onClick = onTestAuth) { Text("Test Auth") }
    }
}

@Composable
private fun ProfileContent(
    state: AuthUiState,
    onLogout: () -> Unit,
    onTestAuth: () -> Unit
) {
    Text("Профиль", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = state.userEmail ?: "—",
        style = MaterialTheme.typography.bodyLarge
    )

    Spacer(Modifier.height(24.dp))
    Divider()
    Spacer(Modifier.height(12.dp))

    Text("Токены", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    TokenRow(
        label = "Access token",
        token = state.savedAccessToken,
        valid = state.isAccessValid
    )
    Spacer(Modifier.height(8.dp))
    TokenRow(
        label = "Refresh token",
        token = state.savedRefreshToken,
        valid = state.isRefreshValid
    )

    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onTestAuth) { Text("Test Auth") }
        Button(onClick = onLogout) { Text("Logout") }
    }
}

@Composable
private fun TokenRow(label: String, token: String?, valid: Boolean) {
    val validityText = if (valid) "valid" else "expired/invalid"
    val validityColor = if (valid) Color(0xFF2e7d32) else Color(0xFFc62828)

    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = shortenToken(token),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Text(
            text = validityText,
            color = validityColor,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun shortenToken(token: String?): String {
    if (token.isNullOrBlank()) return "—"
    return if (token.length <= 32) token
    else token.take(16) + "…" + token.takeLast(12)
}

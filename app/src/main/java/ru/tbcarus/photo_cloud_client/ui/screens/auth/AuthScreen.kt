package ru.tbcarus.photo_cloud_client.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.tbcarus.photo_cloud_client.auth.AuthUiState
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import ru.tbcarus.photo_cloud_client.ui.components.LoadingDialog
import ru.tbcarus.photo_cloud_client.ui.components.showDialog

@Composable
fun AuthScreen(
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state = viewModel.uiState.collectAsState().value

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

    if (state.status == ConnectionStatus.LOADING || state.isVerifying) {
        LoadingDialog()
    }

    state.message?.let {
        showDialog(message = it, status = state.status) {
            viewModel.clearMessage()
        }
    }

    state.repeatRegistrationEmail?.let { email ->
        RepeatRegistrationDialog(
            email = email,
            onConfirm = viewModel::confirmRepeatRegistration,
            onDismiss = viewModel::cancelRepeatRegistration
        )
    }
}

@Composable
private fun RepeatRegistrationDialog(
    email: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Зарегистрироваться повторно?") },
        text = {
            Text(
                "На $email уже отправлено письмо со ссылкой для подтверждения. " +
                    "Новая регистрация аннулирует ссылку из предыдущего письма.\n\n" +
                    "Продолжайте, если ссылка истекла или письмо не пришло."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Зарегистрировать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
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
    val enabled = state.status != ConnectionStatus.LOADING

    Text("Вход / Регистрация", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = state.email,
        onValueChange = onEmailChange,
        label = { Text("Email") },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = state.password,
        onValueChange = onPasswordChange,
        label = { Text("Password") },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Button(onClick = onRegister, enabled = enabled) { Text("Register") }
        Button(onClick = onLogin, enabled = enabled) { Text("Login") }
        Button(onClick = onTestAuth, enabled = enabled) { Text("Test Auth") }
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
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onTestAuth) { Text("Test Auth") }
        Button(onClick = onLogout) { Text("Logout") }
    }
}

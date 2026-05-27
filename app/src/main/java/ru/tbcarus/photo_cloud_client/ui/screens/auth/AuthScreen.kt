package ru.tbcarus.photo_cloud_client.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onTestAuth) { Text("Test Auth") }
        Button(onClick = onLogout) { Text("Logout") }
    }
}

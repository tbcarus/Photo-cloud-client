package ru.tbcarus.photo_cloud_client.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.tbcarus.photo_cloud_client.ui.components.LoadingDialog
import ru.tbcarus.photo_cloud_client.ui.components.showDialog
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color

@Composable
fun AuthScreen(
    viewModel: AuthViewModel = viewModel()
) {
    val state = viewModel.uiState.collectAsState().value

    LaunchedEffect(Unit) {
        viewModel.refreshTokensOverview()
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Authentication")

        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = { viewModel.register() }) {
                Text("Register")
            }
            Button(onClick = { viewModel.login() }) {
                Text("Login")
            }
            Button(onClick = { viewModel.testAuth() }) {
                Text("Test Auth")
            }
        }

        Spacer(Modifier.height(24.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        Text("Saved tokens", style = MaterialTheme.typography.titleMedium)

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

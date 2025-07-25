package ru.tbcarus.photo_cloud_client.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
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

@Composable
fun AuthScreen(
    viewModel: AuthViewModel = viewModel()
) {
    val state = viewModel.uiState.collectAsState().value

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

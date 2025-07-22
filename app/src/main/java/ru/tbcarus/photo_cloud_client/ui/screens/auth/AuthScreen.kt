package ru.tbcarus.photo_cloud_client.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.tbcarus.photo_cloud_client.ui.components.LoadingDialog
import ru.tbcarus.photo_cloud_client.ui.components.showDialog
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus

@Composable
fun AuthScreen(
    viewModel: AuthViewModel = viewModel(),
    baseUrl: String
) {
    val email = viewModel.email.collectAsState().value
    val password = viewModel.password.collectAsState().value
    val message = viewModel.message.collectAsState().value
    val status = viewModel.status.collectAsState().value

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Authentication")

        OutlinedTextField(
            value = email,
            onValueChange = viewModel::onEmailChange,
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
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
            Button(onClick = { viewModel.register(baseUrl) }) {
                Text("Register")
            }
            Button(onClick = { viewModel.login(baseUrl) }) {
                Text("Login")
            }
            Button(onClick = { viewModel.testAuth(baseUrl) }) {
                Text("Test Auth")
            }
        }
    }

    if (status == ConnectionStatus.LOADING) {
        LoadingDialog()
    }

    message?.let {
        showDialog(message = it, status = status) {
            viewModel.clearMessage()
        }
    }
}

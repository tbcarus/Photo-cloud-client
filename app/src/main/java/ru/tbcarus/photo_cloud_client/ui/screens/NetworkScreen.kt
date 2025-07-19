package ru.tbcarus.photo_cloud_client.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import ru.tbcarus.photo_cloud_client.ui.components.LoadingDialog
import ru.tbcarus.photo_cloud_client.ui.components.showDialog
import ru.tbcarus.photo_cloud_client.ui.screens.network.NetworkViewModel
import ru.tbcarus.photo_cloud_client.ui.screens.network.NetworkViewModelFactory

@Composable
fun NetworkScreen(viewModel: NetworkViewModel = viewModel(factory = NetworkViewModelFactory())) {
    val uiState by viewModel.uiState.collectAsState()

    val animatedColor by animateColorAsState(
        targetValue = when (uiState.connectionStatus) {
            ConnectionStatus.SUCCESS -> Color(0xFFE0FFE0)
            ConnectionStatus.ERROR -> Color(0xFFFFE0E0)
            else -> Color.White
        },
        label = "connectionColor"
    )

    Box(modifier = Modifier.fillMaxSize().background(animatedColor)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Подключение к серверу", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = uiState.ip,
                onValueChange = viewModel::onIpChange,
                label = { Text("IP Address") }
            )
            OutlinedTextField(
                value = uiState.port,
                onValueChange = viewModel::onPortChange,
                label = { Text("Port") }
            )
            Button(onClick = viewModel::testConnection) {
                Text("Test")
            }
        }

        if (uiState.isLoading) {
            LoadingDialog()
        }

        uiState.message?.let {
            showDialog(message = it, status = uiState.connectionStatus) {
                viewModel.clearMessage()
            }
        }
    }
}


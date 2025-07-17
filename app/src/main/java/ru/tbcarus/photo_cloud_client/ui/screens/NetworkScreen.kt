package ru.tbcarus.photo_cloud_client.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import ru.tbcarus.photo_cloud_client.api.ApiClient
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.models.TestResponse
import ru.tbcarus.photo_cloud_client.ui.components.ConnectionStatus
import ru.tbcarus.photo_cloud_client.ui.components.LoadingDialog
import ru.tbcarus.photo_cloud_client.utils.isValidIpAddress
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.tbcarus.photo_cloud_client.ui.components.showErrorDialog
import ru.tbcarus.photo_cloud_client.ui.components.showSuccessDialog
import ru.tbcarus.photo_cloud_client.ui.screens.network.NetworkViewModel

@Composable
fun NetworkScreen(viewModel: NetworkViewModel = viewModel()) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    var connectionStatus by remember { mutableStateOf(ConnectionStatus.NONE) }
    val animatedColor by animateColorAsState(
        targetValue = connectionStatus.backgroundColor,
        animationSpec = tween(durationMillis = 500),
        label = "backgroundColor"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedColor)
            .padding(16.dp)
    ) {
        Column {
            Text("Network Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("IP Address") }
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(onClick = {
                connectionStatus = ConnectionStatus.NONE

                if (!isValidIpAddress(ip)) {
                    errorMessage = "Некорректный IP адрес"
                    connectionStatus = ConnectionStatus.ERROR
                    return@Button
                }

                val baseUrl = "http://$ip:$port/"
                isLoading = true

                val api = ApiClient.getClient(baseUrl).create(AuthService::class.java)
                api.testServer().enqueue(object : Callback<TestResponse> {
                    override fun onResponse(call: Call<TestResponse>, response: Response<TestResponse>) {
                        isLoading = false
                        if (response.isSuccessful && response.body() != null) {
                            successMessage = response.body()?.message
                            connectionStatus = ConnectionStatus.SUCCESS
                        } else {
                            errorMessage = "Ошибка подключения: ${response.code()}"
                            connectionStatus = ConnectionStatus.ERROR
                        }
                    }

                    override fun onFailure(call: Call<TestResponse>, t: Throwable) {
                        isLoading = false
                        errorMessage = "Ошибка подключения: ${t.localizedMessage}"
                        connectionStatus = ConnectionStatus.ERROR
                    }
                })
            }) {
                Text("Test Connection")
            }
        }

        // Диалоги
        errorMessage?.let {
            showErrorDialog(message = it) { errorMessage = null }
        }

        successMessage?.let {
            showSuccessDialog(message = it) { successMessage = null }
        }

        if (isLoading) {
            LoadingDialog()
        }
    }
}

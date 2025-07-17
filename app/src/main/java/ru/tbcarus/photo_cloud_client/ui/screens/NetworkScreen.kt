package ru.tbcarus.photo_cloud_client.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import ru.tbcarus.photo_cloud_client.api.ApiClient
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.models.TestResponse
import ru.tbcarus.photo_cloud_client.ui.components.LoadingDialog
import ru.tbcarus.photo_cloud_client.utils.isValidIpAddress

@Composable
fun NetworkScreen() {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    fun testConnection(ip: String, port: String) {
        if (!isValidIpAddress(ip) || port.isBlank()) {
            errorMessage = "Введите корректный IP адрес и порт"
            return
        }

        val baseUrl = "http://$ip:$port/"
        val api = ApiClient.getClient(baseUrl).create(AuthService::class.java)

        isLoading = true
        api.testServer().enqueue(object : Callback<TestResponse> {
            override fun onResponse(call: Call<TestResponse>, response: Response<TestResponse>) {
                isLoading = false
                if (response.isSuccessful && response.body() != null) {
                    Toast.makeText(context, "Успешно: ${response.body()!!.message}", Toast.LENGTH_LONG).show()
                } else {
                    errorMessage = "Ошибка подключения: ${response.code()}"
                }
            }

            override fun onFailure(call: Call<TestResponse>, t: Throwable) {
                isLoading = false
                errorMessage = "Ошибка подключения: ${t.localizedMessage}"
            }
        })
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Настройка сети", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("IP Address") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { testConnection(ip, port) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Test")
            }
        }

        if (isLoading) {
            LoadingDialog()
        }

        errorMessage?.let {
            AlertDialog(
                onDismissRequest = { errorMessage = null },
                confirmButton = {
                    TextButton(onClick = { errorMessage = null }) {
                        Text("OK")
                    }
                },
                title = { Text("Ошибка") },
                text = { Text(it) }
            )
        }
    }
}

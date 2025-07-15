package ru.tbcarus.photo_cloud_client

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import ru.tbcarus.photo_cloud_client.api.ApiClient
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.models.AuthRequest
import ru.tbcarus.photo_cloud_client.ui.theme.PhotoCloudClientTheme
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import ru.tbcarus.photo_cloud_client.api.models.TestResponse
import ru.tbcarus.photo_cloud_client.api.models.dto.ErrorResponse

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotoCloudClientTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LoginScreen()
                }
            }
        }
    }
}

fun testConnection(baseUrl: String, context: android.content.Context) {
    val api = ApiClient.getClient(baseUrl).create(AuthService::class.java)
    api.testServer().enqueue(object : Callback<TestResponse> {
        override fun onResponse(
            call: Call<TestResponse>,
            response: Response<TestResponse>
        ) {
            if (response.isSuccessful) {
                val message = response.body()?.message ?: "Success"
                Toast.makeText(context, "Успешно: $message", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Ошибка подключения: ${response.code()}", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onFailure(call: Call<TestResponse>, t: Throwable) {
            Toast.makeText(context, "Ошибка подключения: ${t.message}", Toast.LENGTH_LONG).show()
        }
    })
}

fun register(baseUrl: String, email: String, password: String, context: android.content.Context) {
    val api = ApiClient.getClient(baseUrl).create(AuthService::class.java)
    val request = AuthRequest(email, password)

    api.register(request).enqueue(object : Callback<Map<String, String>> {
        override fun onResponse(
            call: Call<Map<String, String>>,
            response: Response<Map<String, String>>
        ) {
            if (response.isSuccessful) {
                val message = response.body()?.get("message") ?: "Успешно зарегистрирован"
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } else {
                val errorBody = response.errorBody()?.string()
                val error = try {
                    Gson().fromJson(errorBody, ErrorResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                val msg = error?.message ?: "Ошибка регистрации: ${response.code()}"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }

        override fun onFailure(call: Call<Map<String, String>>, t: Throwable) {
            Toast.makeText(context, "Ошибка сети: ${t.message}", Toast.LENGTH_LONG).show()
        }
    })
}


@Composable
fun LoginScreen() {
    val context = LocalContext.current

    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val baseUrl = remember(ip, port) {
        "http://${ip}:${port}/"
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Server Config", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP Address") })
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
        Button(onClick = { testConnection(baseUrl, context) }) {
            Text("Test")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Auth", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") })
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") })

        Row {
            Button(onClick = {
                register(baseUrl, email, password, context)
            }) {
                Text("Register")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                // TODO: Implement login
            }) {
                Text("Login")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("v0.0.1")
    }
}
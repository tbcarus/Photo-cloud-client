package ru.tbcarus.photo_cloud_client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.tbcarus.photo_cloud_client.ui.theme.PhotoCloudClientTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotoCloudClientTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LoginScreen()
                }
            }
        }
    }
}

@Composable
fun LoginScreen() {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Server Config", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP Address") })
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
        Button(onClick = { /* Test connection */ }) {
            Text("Test")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Auth", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") })
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
        Row {
            Button(onClick = { /* Login */ }) {
                Text("Login")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { /* Register */ }) {
                Text("Register")
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text("v0.0.1")
    }
}
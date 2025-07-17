package ru.tbcarus.photo_cloud_client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ru.tbcarus.photo_cloud_client.ui.MainScreen
import ru.tbcarus.photo_cloud_client.ui.theme.PhotoCloudClientTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotoCloudClientTheme {
                MainScreen()
            }
        }
    }
}

package ru.tbcarus.photo_cloud_client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import ru.tbcarus.photo_cloud_client.ui.MainScreen
import ru.tbcarus.photo_cloud_client.ui.screens.network.NetworkViewModel
import ru.tbcarus.photo_cloud_client.ui.theme.PhotoCloudClientTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val networkViewModel: NetworkViewModel = hiltViewModel()
            PhotoCloudClientTheme {
                MainScreen(networkViewModel)
            }
        }
    }
}

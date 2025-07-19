package ru.tbcarus.photo_cloud_client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.tbcarus.photo_cloud_client.ui.MainScreen
import ru.tbcarus.photo_cloud_client.ui.theme.PhotoCloudClientTheme
import ru.tbcarus.photo_cloud_client.ui.screens.network.NetworkViewModel
import ru.tbcarus.photo_cloud_client.ui.screens.network.NetworkViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val networkViewModel: NetworkViewModel = viewModel(factory = NetworkViewModelFactory(application))
            PhotoCloudClientTheme {
                MainScreen(networkViewModel)
            }
        }
    }
}

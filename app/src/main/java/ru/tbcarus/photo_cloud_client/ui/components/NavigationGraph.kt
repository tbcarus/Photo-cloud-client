package ru.tbcarus.photo_cloud_client.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import ru.tbcarus.photo_cloud_client.ui.screens.FilesScreen
import ru.tbcarus.photo_cloud_client.ui.screens.LoginScreen
import ru.tbcarus.photo_cloud_client.ui.screens.NetworkScreen
import ru.tbcarus.photo_cloud_client.ui.screens.ProfileScreen
import ru.tbcarus.photo_cloud_client.ui.screens.SettingsScreen
import ru.tbcarus.photo_cloud_client.ui.screens.auth.AuthScreen
import ru.tbcarus.photo_cloud_client.ui.screens.auth.AuthViewModel
import ru.tbcarus.photo_cloud_client.ui.screens.network.NetworkViewModel
import ru.tbcarus.photo_cloud_client.utils.Routes

@Composable
fun NavigationGraph(navController: NavHostController, networkViewModel: NetworkViewModel) {
    val uiState = networkViewModel.uiState.collectAsState().value
    val baseUrl = if (uiState.ip.isNotBlank() && uiState.port.isNotBlank()) {
        "http://${uiState.ip}:${uiState.port}/"
    } else ""
    NavHost(navController, startDestination = "network") {
        composable(Routes.Network) { NetworkScreen(viewModel = networkViewModel) }
        composable(Routes.Login) { AuthScreen() }
        composable(Routes.Settings) { SettingsScreen() }
        composable(Routes.Profile) { ProfileScreen() }
        composable(Routes.Files) { FilesScreen() }
    }
}
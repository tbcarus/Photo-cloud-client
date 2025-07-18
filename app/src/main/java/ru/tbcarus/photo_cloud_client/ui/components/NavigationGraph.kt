package ru.tbcarus.photo_cloud_client.ui.components

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import ru.tbcarus.photo_cloud_client.ui.screens.FilesScreen
import ru.tbcarus.photo_cloud_client.ui.screens.LoginScreen
import ru.tbcarus.photo_cloud_client.ui.screens.NetworkScreen
import ru.tbcarus.photo_cloud_client.ui.screens.ProfileScreen
import ru.tbcarus.photo_cloud_client.ui.screens.SettingsScreen

@Composable
fun NavigationGraph(navController: NavHostController) {
    NavHost(navController, startDestination = "network") {
        composable("network") { NetworkScreen() }
        composable("login") { LoginScreen() }
        composable("settings") { SettingsScreen() }
        composable("profile") { ProfileScreen() }
        composable("files") { FilesScreen() }
    }
}
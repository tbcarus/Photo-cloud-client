package ru.tbcarus.photo_cloud_client.ui.components

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import ru.tbcarus.photo_cloud_client.ui.screens.FilesScreen
import ru.tbcarus.photo_cloud_client.ui.screens.NetworkScreen
import ru.tbcarus.photo_cloud_client.ui.screens.ProfileScreen
import ru.tbcarus.photo_cloud_client.ui.screens.SettingsScreen
import ru.tbcarus.photo_cloud_client.ui.screens.auth.AuthScreen
import ru.tbcarus.photo_cloud_client.ui.screens.files.FilesViewModel
import ru.tbcarus.photo_cloud_client.ui.screens.library.LibraryScreen
import ru.tbcarus.photo_cloud_client.ui.screens.library.LibraryViewModel
import ru.tbcarus.photo_cloud_client.ui.screens.network.NetworkViewModel
import ru.tbcarus.photo_cloud_client.ui.screens.notifications.NotificationsScreen
import ru.tbcarus.photo_cloud_client.ui.screens.notifications.NotificationsViewModel
import ru.tbcarus.photo_cloud_client.utils.Routes

@Composable
fun NavigationGraph(
    navController: NavHostController,
    networkViewModel: NetworkViewModel,
    libraryViewModel: LibraryViewModel,
    notificationsViewModel: NotificationsViewModel
) {
    NavHost(navController, startDestination = Routes.Network) {
        composable(Routes.Network) { NetworkScreen(viewModel = networkViewModel) }
        composable(Routes.Login) { AuthScreen() }
        composable(Routes.Settings) { SettingsScreen() }
        composable(Routes.Profile) { ProfileScreen() }
        composable(Routes.Library) {
            LibraryScreen(
                viewModel = libraryViewModel,
                onOpenNetwork = { navController.navigateToTab(Routes.Network) },
                onOpenLogin = { navController.navigateToTab(Routes.Login) }
            )
        }
        composable(Routes.Files) {
            val viewModel: FilesViewModel = hiltViewModel()
            FilesScreen(viewModel = viewModel)
        }
        composable(Routes.Notifications) {
            NotificationsScreen(
                viewModel = notificationsViewModel,
                onOpenLogin = { navController.navigateToTab(Routes.Login) }
            )
        }
    }
}

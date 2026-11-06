package ru.tbcarus.photo_cloud_client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import ru.tbcarus.photo_cloud_client.ui.components.BottomNavigationBar
import ru.tbcarus.photo_cloud_client.ui.components.NavigationGraph
import ru.tbcarus.photo_cloud_client.ui.components.bottomNavItems
import ru.tbcarus.photo_cloud_client.ui.screens.library.LibraryImageViewer
import ru.tbcarus.photo_cloud_client.ui.screens.library.LibraryPdfViewer
import ru.tbcarus.photo_cloud_client.ui.screens.library.LibraryVideoPlayer
import ru.tbcarus.photo_cloud_client.ui.screens.library.LibraryViewModel
import ru.tbcarus.photo_cloud_client.ui.screens.network.NetworkViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(networkViewModel: NetworkViewModel, libraryViewModel: LibraryViewModel) {
    val navController = rememberNavController()

    val snackbarHostState = remember { SnackbarHostState() }

    // Ручная передача идёт только при живом процессе: на время неё экран не гаснет на любой вкладке.
    val transfer by libraryViewModel.transferState.collectAsState()
    val view = LocalView.current
    DisposableEffect(transfer.isActive) {
        view.keepScreenOn = transfer.isActive
        onDispose { view.keepScreenOn = false }
    }

    Box {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo Cloud") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFF8A00), // Orange
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            BottomNavigationBar(items = bottomNavItems,
                navController = navController)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding  ->
        Box(modifier = Modifier.padding(innerPadding)) {
            NavigationGraph(
                navController = navController,
                networkViewModel = networkViewModel,
                libraryViewModel = libraryViewModel
            )
        }
    }
    // Полноэкранный просмотр поверх панелей приложения; Library под ним сохраняет папку, прокрутку и выделение.
    LibraryImageViewer(libraryViewModel)
    LibraryVideoPlayer(libraryViewModel)
    LibraryPdfViewer(libraryViewModel)
    }
}

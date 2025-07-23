package ru.tbcarus.photo_cloud_client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import ru.tbcarus.photo_cloud_client.ui.components.BottomNavItem
import ru.tbcarus.photo_cloud_client.ui.components.BottomNavigationBar
import ru.tbcarus.photo_cloud_client.ui.components.NavigationGraph
import ru.tbcarus.photo_cloud_client.ui.screens.network.NetworkViewModel
import ru.tbcarus.photo_cloud_client.utils.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(networkViewModel: NetworkViewModel) {
    val navController = rememberNavController()
    val navItems = listOf(
        BottomNavItem("Network", Icons.Default.Wifi, Routes.Network),
        BottomNavItem("Login", Icons.Default.Lock, Routes.Login),
        BottomNavItem("Settings", Icons.Default.Settings, Routes.Settings),
        BottomNavItem("Profile", Icons.Default.Person, Routes.Profile),
        BottomNavItem("Files", Icons.Default.Folder, Routes.Files)
    )

    val snackbarHostState = remember { SnackbarHostState() }

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
            BottomNavigationBar(items = navItems,
                navController = navController)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding  ->
        Box(modifier = Modifier.padding(innerPadding)) {
            NavigationGraph(navController = navController, networkViewModel = networkViewModel)
        }
    }
}

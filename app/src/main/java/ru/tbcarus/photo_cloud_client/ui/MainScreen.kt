package ru.tbcarus.photo_cloud_client.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import ru.tbcarus.photo_cloud_client.ui.screens.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val navController = rememberNavController()
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
            BottomNavigationBar(navController)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "network",
            modifier = Modifier.padding(padding)
        ) {
            composable("network") { NetworkScreen() }
            composable("login") { LoginScreen() }
            composable("settings") { SettingsScreen() }
            composable("profile") { ProfileScreen() }
            composable("files") { FilesScreen() }
        }
    }
}

@Composable
fun BottomNavigationBar(navController: NavHostController) {
    val items = listOf("network", "login", "settings", "profile", "files")
    val labels = listOf("N", "L", "S", "P", "F")

    NavigationBar(
        containerColor = Color(0xFF008AFF), // Blue
        tonalElevation = 8.dp
    ) {
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

        items.forEachIndexed { index, route ->
            NavigationBarItem(
                selected = currentRoute == route,
                onClick = { navController.navigate(route) },
                label = { Text(labels[index]) },
                icon = { Icon(Icons.Rounded.Circle, contentDescription = "Circle") }
            )
        }
    }
}

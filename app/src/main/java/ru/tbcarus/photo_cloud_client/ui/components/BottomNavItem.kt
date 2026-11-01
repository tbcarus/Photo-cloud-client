package ru.tbcarus.photo_cloud_client.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import ru.tbcarus.photo_cloud_client.utils.Routes

data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val route: String
)

val bottomNavItems = listOf(
    BottomNavItem("Network", Icons.Default.Wifi, Routes.Network),
    BottomNavItem("Login", Icons.Default.Lock, Routes.Login),
    BottomNavItem("Settings", Icons.Default.Settings, Routes.Settings),
    BottomNavItem("Library", Icons.Default.Cloud, Routes.Library),
    BottomNavItem("Files", Icons.Default.Folder, Routes.Files)
)

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import ru.tbcarus.photo_cloud_client.ui.components.BottomNavigationBar
import ru.tbcarus.photo_cloud_client.ui.components.NavigationGraph
import ru.tbcarus.photo_cloud_client.ui.components.bottomNavItems
import ru.tbcarus.photo_cloud_client.ui.components.navigateToTab
import ru.tbcarus.photo_cloud_client.ui.screens.library.LibraryExternalOpen
import ru.tbcarus.photo_cloud_client.ui.screens.library.LibraryImageViewer
import ru.tbcarus.photo_cloud_client.ui.screens.library.LibraryPdfViewer
import ru.tbcarus.photo_cloud_client.ui.screens.library.LibraryVideoPlayer
import ru.tbcarus.photo_cloud_client.ui.screens.library.LibraryViewModel
import ru.tbcarus.photo_cloud_client.ui.screens.network.NetworkViewModel
import ru.tbcarus.photo_cloud_client.ui.screens.notifications.NotificationBell
import ru.tbcarus.photo_cloud_client.ui.screens.notifications.NotificationsViewModel
import ru.tbcarus.photo_cloud_client.utils.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    networkViewModel: NetworkViewModel,
    libraryViewModel: LibraryViewModel,
    notificationsViewModel: NotificationsViewModel
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    val snackbarHostState = remember { SnackbarHostState() }

    // Ручная передача идёт только при живом процессе: на время неё экран не гаснет на любой вкладке.
    val transfer by libraryViewModel.transferState.collectAsState()
    val view = LocalView.current
    DisposableEffect(transfer.isActive) {
        view.keepScreenOn = transfer.isActive
        onDispose { view.keepScreenOn = false }
    }

    // Счётчик уведомлений опрашивается, только пока приложение на переднем плане.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> notificationsViewModel.onForeground()
                Lifecycle.Event.ON_STOP -> notificationsViewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            notificationsViewModel.onBackground()
        }
    }

    // Переход из уведомления: Library открывает ресурс в «Доступно мне», если он относится к текущей сессии.
    val notifications by notificationsViewModel.uiState.collectAsState()
    val pendingOpen by notificationsViewModel.pendingOpen.collectAsState()
    LaunchedEffect(pendingOpen) {
        val target = pendingOpen ?: return@LaunchedEffect
        notificationsViewModel.consumeOpen(target)
        libraryViewModel.openSharedTarget(target)
        navController.navigateToTab(Routes.Library)
    }

    Box {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo Cloud") },
                actions = {
                    // Только при входе в аккаунт и не на экране входа.
                    if (notifications.hasSession && currentRoute != Routes.Login) {
                        NotificationBell(notifications.unreadCount) {
                            navController.navigate(Routes.Notifications) { launchSingleTop = true }
                        }
                    }
                },
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
                libraryViewModel = libraryViewModel,
                notificationsViewModel = notificationsViewModel
            )
        }
    }
    // Полноэкранный просмотр поверх панелей приложения; Library под ним сохраняет папку, прокрутку и выделение.
    LibraryImageViewer(libraryViewModel)
    LibraryVideoPlayer(libraryViewModel)
    LibraryPdfViewer(libraryViewModel)
    LibraryExternalOpen(libraryViewModel)
    }
}

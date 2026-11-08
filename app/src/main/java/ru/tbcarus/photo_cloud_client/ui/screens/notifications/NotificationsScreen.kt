package ru.tbcarus.photo_cloud_client.ui.screens.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.tbcarus.photo_cloud_client.api.models.NotificationDto
import ru.tbcarus.photo_cloud_client.ui.components.OnResumeEffect
import ru.tbcarus.photo_cloud_client.ui.screens.library.LoadMoreError
import ru.tbcarus.photo_cloud_client.ui.screens.library.LoadingMore
import ru.tbcarus.photo_cloud_client.ui.screens.library.StateMessage
import ru.tbcarus.photo_cloud_client.ui.screens.library.formatShareExpiry

internal const val NOTIFICATION_BELL_TAG = "notification-bell"
internal const val NOTIFICATION_LIST_TAG = "notification-list"
private const val LOAD_MORE_THRESHOLD = 10

/** Колокольчик верхней панели: числовой индикатор только при непрочитанных. */
@Composable
fun NotificationBell(unreadCount: Long?, onClick: () -> Unit) {
    val count = unreadCount ?: 0
    IconButton(onClick = onClick, modifier = Modifier.testTag(NOTIFICATION_BELL_TAG)) {
        BadgedBox(badge = {
            if (count > 0) Badge { Text(unreadBadgeText(count)) }
        }) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = if (count > 0) "Уведомления, непрочитанных: $count" else "Уведомления",
                tint = Color.White
            )
        }
    }
}

/**
 * Список уведомлений текущего аккаунта: от новых к старым в порядке Server, страницы подгружаются при прокрутке.
 * Нажатие отмечает уведомление прочитанным и открывает его ресурс в «Доступно мне».
 */
@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel, onOpenLogin: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    // Показ экрана и возврат на него, в том числе из свёрнутого приложения, перечитывают список. Запись навигации
    // получает ON_RESUME после анимации перехода.
    OnResumeEffect { viewModel.onScreenOpened() }
    // Новая сессия при уже открытом экране: список другого пользователя загружается заново. Первый запуск эффекта
    // пропускается: первую загрузку выполняет ON_RESUME, иначе список запрашивался бы дважды.
    var composed by remember { mutableStateOf(false) }
    LaunchedEffect(state.status) {
        if (composed) viewModel.ensureLoaded() else composed = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Уведомления", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.status == NotificationsStatus.Content && state.items.isNotEmpty()) {
                val hasUnread = (state.unreadCount ?: 0) > 0 || state.items.any { it.isUnread }
                TextButton(onClick = viewModel::markAllRead, enabled = hasUnread && !state.busy) {
                    Text("Прочитать все")
                }
            }
            IconButton(onClick = viewModel::onScreenOpened, enabled = state.hasSession && !state.busy) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
            }
        }
        state.notice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .clickable(onClick = viewModel::dismissNotice)
            )
        }
        HorizontalDivider()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (val status = state.status) {
                NotificationsStatus.Idle, NotificationsStatus.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                NotificationsStatus.NoSession -> StateMessage(
                    text = "Войдите, чтобы увидеть уведомления.",
                    action = "Выполнить вход",
                    onAction = onOpenLogin
                )
                is NotificationsStatus.Error -> StateMessage(
                    text = status.message,
                    action = "Повторить",
                    onAction = viewModel::onScreenOpened
                )
                NotificationsStatus.Content ->
                    if (state.items.isEmpty()) {
                        StateMessage(text = "Уведомлений пока нет")
                    } else {
                        NotificationList(state, viewModel)
                    }
            }
        }
    }
}

@Composable
private fun NotificationList(state: NotificationsUiState, viewModel: NotificationsViewModel) {
    val listState = remember(state.contentVersion) { LazyListState() }
    val nearEnd by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && last >= layout.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(nearEnd, state.hasNext, state.isLoadingMore, state.loadMoreError) {
        if (nearEnd) viewModel.onListEndReached()
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(NOTIFICATION_LIST_TAG)) {
        items(state.items, key = { "notification-${it.id}" }) { notification ->
            NotificationRow(
                notification = notification,
                opening = state.openingId == notification.id,
                enabled = !state.busy,
                onClick = { viewModel.open(notification) }
            )
            HorizontalDivider()
        }
        if (state.isLoadingMore) {
            item(key = "loading-more") { LoadingMore() }
        }
        state.loadMoreError?.let { error ->
            item(key = "load-more-error") { LoadMoreError(error, viewModel::loadMore) }
        }
    }
}

/** Непрочитанное — с точкой и полужирным заголовком. Текст — как его сформировал Server. */
@Composable
private fun NotificationRow(notification: NotificationDto, opening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val unread = notification.isUnread
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            if (unread) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .testTag("unread-${notification.id}")
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = notification.title.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal
            )
            notification.body?.takeIf { it.isNotBlank() }?.let { body ->
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
            }
            formatShareExpiry(notification.createdAt)?.let { date ->
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (opening) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

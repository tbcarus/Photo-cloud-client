package ru.tbcarus.photo_cloud_client.ui.screens.notifications

import ru.tbcarus.photo_cloud_client.api.models.NotificationDto

sealed interface NotificationsStatus {
    /** Сессия есть, список загрузится при открытии экрана. */
    data object Idle : NotificationsStatus
    data object Loading : NotificationsStatus
    data object Content : NotificationsStatus
    data object NoSession : NotificationsStatus
    data class Error(val message: String) : NotificationsStatus
}

/** Уведомления текущей сессии. Только в памяти: при смене пользователя или Server сбрасываются целиком. */
data class NotificationsUiState(
    val status: NotificationsStatus = NotificationsStatus.NoSession,
    val items: List<NotificationDto> = emptyList(),
    val hasNext: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    // Число непрочитанных по последнему ответу Server; null — ещё неизвестно или сессии нет.
    val unreadCount: Long? = null,
    // ID уведомления, прочтение которого отправлено по нажатию: повторные нажатия не шлют второй запрос.
    val openingId: Long? = null,
    val markingAll: Boolean = false,
    // Ошибка действия (прочтения); список при этом остаётся.
    val notice: String? = null,
    // Меняется с каждой загрузкой с первой страницы: список начинается сверху.
    val contentVersion: Int = 0
) {
    /** Колокольчик показывается только при входе в аккаунт. */
    val hasSession: Boolean get() = status != NotificationsStatus.NoSession
    val busy: Boolean get() = openingId != null || markingAll
}

/** Текст значка: больше 99 — «99+». */
fun unreadBadgeText(count: Long): String = if (count > 99) "99+" else count.toString()

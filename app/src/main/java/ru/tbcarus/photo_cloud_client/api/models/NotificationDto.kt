package ru.tbcarus.photo_cloud_client.api.models

/** Вид события уведомления, имена — как на Server. Неизвестное имя Gson разбирает в null. */
enum class NotificationType {
    SHARE_GRANTED,
    // Зарезервированы Server: показывается только текст, переходов нет.
    BACKUP_FAILED,
    SYSTEM_MESSAGE
}

enum class NotificationTargetType {
    FILE,
    FOLDER
}

/**
 * Уведомление текущего пользователя (`GET /api/v1/notifications`). Текст формирует Server.
 * [targetType]/[targetId] — указатель для перехода, а не право чтения: объект может быть уже недоступен.
 */
data class NotificationDto(
    val id: Long,
    val type: NotificationType? = null,
    val title: String? = null,
    val body: String? = null,
    val targetType: NotificationTargetType? = null,
    val targetId: Long? = null,
    // Моменты UTC в ISO-8601; readAt = null — не прочитано.
    val createdAt: String? = null,
    val readAt: String? = null
) {
    val isUnread: Boolean get() = readAt == null
}

/** Страница уведомлений: тот же PageResponse Server, что у файлов. */
data class NotificationPageDto(
    val items: List<NotificationDto>? = null,
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false
)

data class UnreadNotificationCountDto(
    val count: Long = 0
)

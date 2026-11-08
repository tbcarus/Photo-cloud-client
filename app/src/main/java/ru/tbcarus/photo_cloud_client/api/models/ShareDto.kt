package ru.tbcarus.photo_cloud_client.api.models

/**
 * Выданный текущим пользователем USER-доступ (`GET /api/v1/shares`, ответы выдачи и изменения срока).
 * [status] вычисляет Server в момент ответа: ACTIVE или EXPIRED.
 */
data class ShareDto(
    val id: Long,
    val resourceType: String,
    val resourceId: Long,
    val resourceName: String? = null,
    val recipient: ShareUserDto? = null,
    // Момент UTC в ISO-8601; null — бессрочно.
    val expiresAt: String? = null,
    val status: String? = null,
    val createdAt: String? = null
) {
    val isFolder: Boolean get() = resourceType == "FOLDER"
    val isExpired: Boolean get() = status == "EXPIRED"
}

/** Страница выданных доступов: тот же PageResponse Server. */
data class SharePageDto(
    val items: List<ShareDto>? = null,
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false
)

/**
 * Выдача USER-доступа: ровно один из [fileId] и [folderId]. Gson не пишет null-поля, поэтому отсутствующий
 * [expiresAt] Server понимает как бессрочный доступ.
 */
data class GrantShareRequest(
    val fileId: Long? = null,
    val folderId: Long? = null,
    val emails: List<String>,
    // ISO-8601 со смещением; null — бессрочно.
    val expiresAt: String? = null
)

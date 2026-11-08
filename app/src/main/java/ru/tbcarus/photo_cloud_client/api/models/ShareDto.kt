package ru.tbcarus.photo_cloud_client.api.models

/** Общее у USER-доступа и публичной ссылки: срок, статус и объект — для изменения срока и отзыва. */
interface ShareAccess {
    val id: Long
    val resourceType: String
    val resourceName: String?
    val expiresAt: String?
    val status: String?
    val createdAt: String?
    val isFolder: Boolean get() = resourceType == "FOLDER"
    val isExpired: Boolean get() = status == "EXPIRED"
}

/**
 * Выданный текущим пользователем USER-доступ (`GET /api/v1/shares`, ответы выдачи и изменения срока).
 * [status] вычисляет Server в момент ответа: ACTIVE или EXPIRED.
 */
data class ShareDto(
    override val id: Long,
    override val resourceType: String,
    val resourceId: Long,
    override val resourceName: String? = null,
    val recipient: ShareUserDto? = null,
    // Момент UTC в ISO-8601; null — бессрочно.
    override val expiresAt: String? = null,
    override val status: String? = null,
    override val createdAt: String? = null
) : ShareAccess

/**
 * Публичная ссылка владельца (`/api/v1/shares/links`). [url] Server восстанавливает из зашифрованного секрета;
 * null — секрет с текущим ключом Server не расшифровывается: адрес получить нельзя, но ссылку можно отозвать.
 */
data class LinkShareDto(
    override val id: Long,
    override val resourceType: String,
    val resourceId: Long,
    override val resourceName: String? = null,
    val url: String? = null,
    override val expiresAt: String? = null,
    override val status: String? = null,
    override val createdAt: String? = null
) : ShareAccess {
    /** Адрес для копирования и передачи: только http(s) из ответа Server, без самостоятельной сборки. */
    val shareableUrl: String? get() = url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}

/** Страница публичных ссылок: тот же PageResponse Server. */
data class LinkPageDto(
    val items: List<LinkShareDto>? = null,
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false
)

/** Создание ссылки: ровно один из [fileId] и [folderId]; отсутствующий [expiresAt] — бессрочно. */
data class CreateLinkRequest(
    val fileId: Long? = null,
    val folderId: Long? = null,
    val expiresAt: String? = null
)

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

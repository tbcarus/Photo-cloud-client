package ru.tbcarus.photo_cloud_client.api.models

/**
 * Элемент «Доступно мне» (`GET /api/v1/shared-with-me`): один прямой USER-доступ текущего пользователя.
 * [file] есть только у файла. Содержимое открывается обычными read-endpoints по [resourceId].
 */
data class SharedResourceDto(
    val shareId: Long,
    val resourceType: String,
    val resourceId: Long,
    val name: String? = null,
    val file: FileItemDto? = null,
    val owner: ShareUserDto? = null,
    // Момент UTC в ISO-8601; null — бессрочно.
    val expiresAt: String? = null,
    val sharedAt: String? = null
) {
    val isFolder: Boolean get() = resourceType == "FOLDER"
}

/** Владелец предоставленного ресурса. */
data class ShareUserDto(
    val id: Long? = null,
    val email: String? = null,
    val displayName: String? = null
)

/** Страница «Доступно мне»: тот же PageResponse Server, что у файлов. */
data class SharedPageDto(
    val items: List<SharedResourceDto>? = null,
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false
)

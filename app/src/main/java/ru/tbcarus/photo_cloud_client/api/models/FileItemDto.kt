package ru.tbcarus.photo_cloud_client.api.models

/**
 * Ответ сервера на upload (`POST /api/v1/files/upload`).
 * На 5D реально используется только [id] — он пишется в MediaFile.serverFileId.
 * Остальные поля nullable: терпимы к изменениям ответа и пригодятся будущему list-sync.
 */
data class FileItemDto(
    val id: Long?,                 // nullable, чтобы явно валидировать response
    val folderId: Long? = null,
    val originalFilename: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val checksum: String? = null,
    val fileType: String? = null,
    // Даты пока строками — LocalDateTime не нужен на 5D.
    val capturedAt: String? = null,
    val uploadedAt: String? = null,
    val deletedAt: String? = null,
    val metadata: Map<String, Any?>? = null // на 5D не используется
)

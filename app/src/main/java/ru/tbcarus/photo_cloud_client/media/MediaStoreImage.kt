package ru.tbcarus.photo_cloud_client.media

/**
 * Снимок metadata одного файла из MediaStore.Images.
 * Только локальные данные устройства — не серверная модель, не Room entity.
 * Timestamps нормализованы в миллисекунды.
 */
data class MediaStoreImage(
    val mediaStoreId: Long,
    val uri: String,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,     // ms: DATE_TAKEN, или DATE_MODIFIED*1000, или DATE_ADDED*1000
    val lastModified: Long   // ms: DATE_MODIFIED*1000
)

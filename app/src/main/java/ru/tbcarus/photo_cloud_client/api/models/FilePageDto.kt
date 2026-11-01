package ru.tbcarus.photo_cloud_client.api.models

/** Страница файлов Server (`GET /api/v1/files`). */
data class FilePageDto(
    val items: List<FileItemDto>? = null,
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false
)

package ru.tbcarus.photo_cloud_client.api.models

/** Тело `PATCH /api/v1/files/{id}`. backupSource передаёт только автоматическое резервирование. */
data class RenameFileRequest(
    val originalName: String,
    val backupSource: String? = null
)

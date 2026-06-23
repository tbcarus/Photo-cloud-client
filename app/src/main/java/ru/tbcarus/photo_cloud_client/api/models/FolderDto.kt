package ru.tbcarus.photo_cloud_client.api.models

data class FolderDto(
    val id: Long,
    val parentId: Long?,
    val name: String,
    // На этапе 5C сравниваем как строку с "CAMERA"; enum FolderType отложен.
    val folderType: String,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

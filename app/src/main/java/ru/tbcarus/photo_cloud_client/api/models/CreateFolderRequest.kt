package ru.tbcarus.photo_cloud_client.api.models

data class CreateFolderRequest(
    val parentId: Long,
    val name: String
)

package ru.tbcarus.photo_cloud_client.api.models

data class CreateFolderRequest(
    val parentId: Long,
    val name: String,
    // UUID активного источника для создания папки в его дереве; null Gson не отправляет.
    val backupSource: String? = null
)

package ru.tbcarus.photo_cloud_client.api.models

data class FolderDto(
    val id: Long,
    val parentId: Long?,
    val name: String,
    // На этапе 5C сравниваем как строку с "CAMERA"; enum FolderType отложен.
    val folderType: String,
    // UUID активного автоматического источника; только у его корня, у остальных папок null.
    val backupSource: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    val isCamera: Boolean get() = folderType == "CAMERA"
    val isRoot: Boolean get() = folderType == "ROOT"
    val isAutoUploads: Boolean get() = folderType == "AUTO_UPLOADS"
    val isUser: Boolean get() = folderType == "USER"
    val isActiveBackupSource: Boolean get() = backupSource != null
}

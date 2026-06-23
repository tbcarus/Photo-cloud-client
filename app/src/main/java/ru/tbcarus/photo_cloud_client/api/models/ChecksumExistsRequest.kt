package ru.tbcarus.photo_cloud_client.api.models

data class ChecksumExistsRequest(
    val folderId: Long,
    val checksums: List<String>
)

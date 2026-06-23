package ru.tbcarus.photo_cloud_client.api.models

data class ChecksumExistsResponse(
    val existing: List<String> = emptyList(),
    val missing: List<String> = emptyList()
)

package ru.tbcarus.photo_cloud_client.api.models

data class CreateBackupSourceRequest(
    val name: String,
    val sourceUuid: String
)

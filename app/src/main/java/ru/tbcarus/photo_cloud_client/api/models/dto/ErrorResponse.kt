package ru.tbcarus.photo_cloud_client.api.models.dto

import java.util.UUID

data class ErrorResponse(
    val uuid: UUID,
    val message: String
)
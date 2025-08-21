package ru.tbcarus.photo_cloud_client.api.models.dto

import java.util.UUID

data class ErrorResponseDto(
    val uuid: UUID?,
    val message: String?
)
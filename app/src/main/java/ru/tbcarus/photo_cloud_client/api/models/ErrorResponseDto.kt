package ru.tbcarus.photo_cloud_client.api.models

data class ErrorResponseDto(
    val id: String? = null,
    val code: String? = null,
    val message: String? = null,
    val fieldErrors: Map<String, String>? = null
)

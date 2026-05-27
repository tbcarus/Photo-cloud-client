package ru.tbcarus.photo_cloud_client.api.models

data class LoginResponseDto(
    val accessToken: String? = null,
    val refreshToken: String? = null
)

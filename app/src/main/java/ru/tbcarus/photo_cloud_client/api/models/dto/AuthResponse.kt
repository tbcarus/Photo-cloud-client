package ru.tbcarus.photo_cloud_client.api.models.dto

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String
)
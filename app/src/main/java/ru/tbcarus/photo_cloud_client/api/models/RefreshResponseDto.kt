package ru.tbcarus.photo_cloud_client.api.models

data class RefreshResponseDto(
    val accessToken: String? = null,
    // Есть только при замене refresh на Server; без замены поле отсутствует.
    val refreshToken: String? = null
)

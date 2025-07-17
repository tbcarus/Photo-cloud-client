package ru.tbcarus.photo_cloud_client.ui.components

import androidx.compose.ui.graphics.Color

enum class ConnectionStatus(val backgroundColor: Color) {
    NONE(Color.White),
    SUCCESS(Color(0xFFDFFFE0)),
    ERROR(Color(0xFFFFE0E0))
}
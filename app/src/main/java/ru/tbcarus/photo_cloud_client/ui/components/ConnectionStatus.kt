package ru.tbcarus.photo_cloud_client.ui.components

import androidx.compose.ui.graphics.Color

enum class ConnectionStatus(val backgroundColor: Color, val title: String) {
    NONE(Color.White, "none"),
    SUCCESS(Color(0xFFDFFFE0), "OK"),
    ERROR(Color(0xFFFFE0E0), "ERROR"),
    LOADING(Color(0xFF986DDE), "LOADING")
}
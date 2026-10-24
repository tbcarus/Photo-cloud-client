package ru.tbcarus.photo_cloud_client.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Разрешение чтения изображений: с Android 13 (TIRAMISU) — точечное READ_MEDIA_IMAGES. */
val mediaReadPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * Разрешения системного запроса. На Android 14+ вместе с READ_MEDIA_IMAGES запрашивается
 * READ_MEDIA_VISUAL_USER_SELECTED, как рекомендует платформа: при выборе «выбранные фото» выдаётся
 * только он, [hasMediaReadPermission] остаётся false — scan и очистка Room не выполняются.
 */
val mediaPermissionRequest: Array<String>
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else {
        arrayOf(mediaReadPermission)
    }

/**
 * true только при полном доступе к изображениям.
 * Частичный доступ Android 14+ («выбранные фото») выдаётся отдельным READ_MEDIA_VISUAL_USER_SELECTED,
 * объявленным в манифесте, и сюда не входит: выборка из него не является всей библиотекой.
 */
fun Context.hasMediaReadPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, mediaReadPermission) == PackageManager.PERMISSION_GRANTED

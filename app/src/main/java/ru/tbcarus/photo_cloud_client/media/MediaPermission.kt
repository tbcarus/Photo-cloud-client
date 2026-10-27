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

/**
 * Необязательный доступ к геоданным фото: с ним [MediaStreamOpener] читает оригинал вместе с EXIF/GPS.
 * Отказ не останавливает backup — отправляется доступная Android версия без геоданных.
 */
fun Context.hasMediaLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Разрешения для запроса при входе на экран; пустой массив — запрос не нужен.
 * Без доступа к фото запрос прежний, геоданные добавляются к нему же — отдельного диалога это не создаёт.
 * При выданном доступе к фото геоданные запрашиваются отдельно один раз: [mediaLocationRequested]
 * хранит факт запроса, чтобы не показывать системный диалог при каждом открытии экрана.
 */
fun mediaPermissionsToRequest(
    hasMediaRead: Boolean,
    hasMediaLocation: Boolean,
    mediaLocationRequested: Boolean
): Array<String> = when {
    !hasMediaRead && !hasMediaLocation -> mediaPermissionRequest + Manifest.permission.ACCESS_MEDIA_LOCATION
    !hasMediaRead -> mediaPermissionRequest
    !hasMediaLocation && !mediaLocationRequested -> arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION)
    else -> emptyArray()
}

private const val PERMISSION_PREFS = "media_permissions"
private const val KEY_MEDIA_LOCATION_REQUESTED = "media_location_requested"

/** Запрашивалось ли уже ACCESS_MEDIA_LOCATION; сбрасывается только вместе с данными приложения. */
fun Context.isMediaLocationRequested(): Boolean =
    getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MEDIA_LOCATION_REQUESTED, false)

fun Context.markMediaLocationRequested() {
    getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_MEDIA_LOCATION_REQUESTED, true)
        .apply()
}

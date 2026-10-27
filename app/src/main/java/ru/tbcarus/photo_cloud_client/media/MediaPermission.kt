package ru.tbcarus.photo_cloud_client.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Разрешение чтения изображений: с Android 13 (TIRAMISU) — точечное READ_MEDIA_IMAGES. */
val mediaReadPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * Разрешения системного запроса доступа к фото (Settings). На Android 14+ вместе с READ_MEDIA_IMAGES запрашивается
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

/** Доступ приложения к изображениям по фактическим разрешениям Android. */
enum class MediaAccess {
    /** Вся коллекция: достаточно для scan и автоматической синхронизации. */
    FULL,

    /** Android 14+: только выбранные пользователем фото — для синхронизации недостаточно. */
    PARTIAL,
    NONE
}

/** Полный доступ определяется тем же [hasMediaReadPermission], что проверяют scan и фоновые задачи. */
fun Context.mediaAccess(): MediaAccess = when {
    hasMediaReadPermission() -> MediaAccess.FULL
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
        PackageManager.PERMISSION_GRANTED -> MediaAccess.PARTIAL
    else -> MediaAccess.NONE
}

/**
 * Android больше не показывает системный диалог: доступ можно выдать только в настройках приложения.
 * Один rationale=false после отказа этого не доказывает — так же выглядят первый запрос и закрытый без ответа диалог.
 * Блокировкой считается отказ без rationale, если до запроса rationale был (пользователь только что выбрал
 * «больше не спрашивать») или запрос уже отклонялся в этом сеансе экрана.
 */
fun isPermissionRequestBlocked(
    granted: Boolean,
    rationaleBeforeRequest: Boolean,
    rationaleAfterRequest: Boolean,
    deniedBefore: Boolean
): Boolean = !granted && !rationaleAfterRequest && (rationaleBeforeRequest || deniedBefore)

/** Системный экран приложения, где пользователь сам выдаёт или отзывает разрешения. */
fun Context.appSettingsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

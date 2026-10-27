package ru.tbcarus.photo_cloud_client.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.tbcarus.photo_cloud_client.media.MediaAccess
import ru.tbcarus.photo_cloud_client.media.appSettingsIntent
import ru.tbcarus.photo_cloud_client.media.hasMediaLocationPermission
import ru.tbcarus.photo_cloud_client.media.isPermissionRequestBlocked
import ru.tbcarus.photo_cloud_client.media.mediaAccess
import ru.tbcarus.photo_cloud_client.media.mediaPermissionRequest
import ru.tbcarus.photo_cloud_client.media.mediaReadPermission
import ru.tbcarus.photo_cloud_client.ui.components.OnResumeEffect
import ru.tbcarus.photo_cloud_client.ui.screens.settings.SettingsViewModel

/**
 * Единственное место запроса разрешений: сначала доступ к фото, затем необязательные геоданные.
 * Состояние не хранится: оно читается у Android при открытии экрана, после ответа на запрос
 * и при каждом возврате в приложение. Запросы — только по нажатию; отзыв — только в настройках Android.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var photoAccess by remember { mutableStateOf(context.mediaAccess()) }
    // ACCESS_MEDIA_LOCATION проверяется только при полном доступе к фото.
    var locationGranted by remember {
        mutableStateOf(photoAccess == MediaAccess.FULL && context.hasMediaLocationPermission())
    }
    val photoRequest = remember { RequestTracker(mediaReadPermission) }
    val locationRequest = remember { RequestTracker(Manifest.permission.ACCESS_MEDIA_LOCATION) }

    fun refresh() {
        val previousAccess = photoAccess
        val previousLocation = locationGranted
        photoAccess = context.mediaAccess()
        locationGranted = photoAccess == MediaAccess.FULL && context.hasMediaLocationPermission()
        // Смена разрешения (в том числе в настройках Android) начинает историю отказов заново.
        if (photoAccess != previousAccess) {
            photoRequest.reset()
            if (photoAccess == MediaAccess.FULL) viewModel.onMediaPermissionGranted()
        }
        if (locationGranted != previousLocation) locationRequest.reset()
    }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh()
        photoRequest.onResult(photoAccess == MediaAccess.FULL, context.findActivity())
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refresh()
        locationRequest.onResult(locationGranted, context.findActivity())
    }

    OnResumeEffect { refresh() }

    Column(modifier = Modifier.padding(16.dp)) {
        PhotoAccessSection(
            access = photoAccess,
            blocked = photoRequest.blocked,
            onRequest = {
                photoRequest.beforeLaunch(context.findActivity())
                photoLauncher.launch(mediaPermissionRequest)
            },
            onOpenSettings = { context.startActivity(context.appSettingsIntent()) }
        )
        Spacer(Modifier.height(24.dp))
        MediaLocationSection(
            photoAccessGranted = photoAccess == MediaAccess.FULL,
            granted = locationGranted,
            blocked = locationRequest.blocked,
            onRequest = {
                locationRequest.beforeLaunch(context.findActivity())
                locationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            },
            onOpenSettings = { context.startActivity(context.appSettingsIntent()) }
        )
    }
}

@Composable
private fun PhotoAccessSection(
    access: MediaAccess,
    blocked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    SectionHeader(
        title = "Доступ к фотографиям",
        description = "Без доступа ко всем фотографиям резервное копирование не выполняется"
    )
    when (access) {
        MediaAccess.FULL -> GrantedButton("Доступ к фотографиям предоставлен")
        MediaAccess.PARTIAL -> {
            Text(
                text = "Доступ только к выбранным фотографиям. Для автоматической синхронизации нужен доступ ко всем.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            RequestButton("Разрешить доступ ко всем фотографиям", blocked, onRequest, onOpenSettings)
        }
        MediaAccess.NONE -> RequestButton("Разрешить доступ к фотографиям", blocked, onRequest, onOpenSettings)
    }
}

@Composable
private fun MediaLocationSection(
    photoAccessGranted: Boolean,
    granted: Boolean,
    blocked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    SectionHeader(
        title = "Геоданные фотографий",
        description = "Разрешение позволяет сохранять GPS-координаты фотографий при резервном копировании",
        enabled = photoAccessGranted
    )
    when {
        // Без полного доступа к фото геоданные бесполезны: блок неактивен, ACCESS_MEDIA_LOCATION не проверяется.
        !photoAccessGranted -> Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text("Сначала разрешите доступ к фотографиям")
        }
        granted -> GrantedButton("Доступ к геоданным предоставлен")
        else -> RequestButton("Разрешить доступ к геоданным", blocked, onRequest, onOpenSettings)
    }
}

@Composable
private fun SectionHeader(title: String, description: String, enabled: Boolean = true) {
    val disabledAlpha = if (enabled) 1f else 0.38f
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun GrantedButton(text: String) {
    Button(
        onClick = {},
        enabled = false,
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = Color(0xFF2E7D32),
            disabledContentColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}

/** Кнопка запроса; если Android перестал показывать диалог — переход в настройки приложения. */
@Composable
private fun RequestButton(text: String, blocked: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    if (blocked) {
        Text(
            text = "Android больше не показывает запрос. Разрешите доступ в настройках приложения.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Открыть настройки приложения")
        }
    } else {
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
            Text(text)
        }
    }
}

/**
 * История запросов одного разрешения в текущем сеансе экрана — не копия состояния разрешения:
 * нужна только чтобы отличить блокировку Android от первого отказа ([isPermissionRequestBlocked]).
 */
private class RequestTracker(private val permission: String) {
    var blocked by mutableStateOf(false)
        private set
    private var deniedBefore = false
    private var rationaleBeforeRequest = false

    fun beforeLaunch(activity: Activity?) {
        rationaleBeforeRequest = activity?.shouldShowRequestPermissionRationale(permission) ?: false
    }

    fun onResult(granted: Boolean, activity: Activity?) {
        if (granted) {
            reset()
            return
        }
        blocked = isPermissionRequestBlocked(
            granted = false,
            rationaleBeforeRequest = rationaleBeforeRequest,
            rationaleAfterRequest = activity?.shouldShowRequestPermissionRationale(permission) ?: false,
            deniedBefore = deniedBefore
        )
        deniedBefore = true
    }

    fun reset() {
        blocked = false
        deniedBefore = false
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

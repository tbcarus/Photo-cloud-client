package ru.tbcarus.photo_cloud_client.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.tbcarus.photo_cloud_client.media.MediaFile
import ru.tbcarus.photo_cloud_client.media.MediaFileStatus
import ru.tbcarus.photo_cloud_client.media.ScanResult
import ru.tbcarus.photo_cloud_client.media.SyncStatus
import ru.tbcarus.photo_cloud_client.media.SyncStatusRecord
import ru.tbcarus.photo_cloud_client.ui.components.LoadingDialog
import ru.tbcarus.photo_cloud_client.ui.screens.files.FilesViewModel

@Composable
fun FilesScreen(viewModel: FilesViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Разрешение зависит от версии Android: с Android 13 (TIRAMISU) — точечное READ_MEDIA_IMAGES.
    val mediaPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    // Если разрешили — включаем автосинк и делаем scan; если отказали — scanImages вернёт
    // PermissionDenied и отобразится существующая card.
    // TODO: Добавить переход в настройки приложения, если пользователь окончательно запретил доступ к фото.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Доступ выдан — включаем автосинк (periodic + observer) и запускаем scan.
            viewModel.onMediaPermissionGranted()
        } else {
            // Отказ — прежнее поведение: scan вернёт PermissionDenied и покажет card.
            viewModel.scanPhotos()
        }
    }

    // Bootstrap автосинка при входе на экран (не завязан на кнопку Scan):
    // если пользователь залогинен и baseUrl настроен — запрашиваем разрешение (или,
    // если оно уже есть, просто поднимаем observer + periodic через reconcile()).
    LaunchedEffect(Unit) {
        if (viewModel.isAutoSyncConfigured()) {
            val granted = ContextCompat.checkSelfPermission(context, mediaPermission) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.ensureAutoSyncStarted()
            } else {
                permissionLauncher.launch(mediaPermission)
            }
        }
    }

    if (state.isScanning || state.isSyncing) {
        LoadingDialog()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Экран — монитор состояния автосинхронизации. Ручные debug-кнопки удалены (Stage 5J.2);
        // permission/bootstrap автосинка выполняются в LaunchedEffect выше, синхронизацию ведут
        // MediaChangeObserver + periodic WorkManager.

        // Итог последнего прогона sync
        state.lastSyncStatus?.let { status ->
            SyncStatusCard(status)
            Spacer(Modifier.height(8.dp))
        }

        // Предупреждение об отсутствии разрешения
        if (state.permissionDenied) {
            // TODO: добавить runtime permission request и переход в настройки приложения отдельным этапом.
            PermissionDeniedCard()
            Spacer(Modifier.height(8.dp))
        }

        // Ошибка прогона (не per-file)
        state.errorMessage?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
        }

        // Результат последнего scan
        state.lastScanResult?.let { result ->
            Text(
                text = formatScanResult(result),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
        }

        // Счётчики по статусам — производные от files, не хранятся в UiState
        // TODO: запуск checksum и отображение прогресса будут добавлены отдельным этапом.
        StatusCountersRow(files = state.files)

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        // Список локальных фото
        if (state.files.isEmpty() && !state.isScanning) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("Нет локальных фото. Синхронизация запустится автоматически.")
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.files, key = { it.mediaStoreId }) { file ->
                    MediaFileListItem(file)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PermissionDeniedCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Нет доступа к фотографиям.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "Разрешение будет запрошено на следующем этапе.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SyncStatusCard(record: SyncStatusRecord) {
    val message = when (record.status) {
        SyncStatus.SUCCESS            -> "Синхронизация завершена"
        SyncStatus.SERVER_UNAVAILABLE -> "Сервер недоступен, повтор будет выполнен позже"
        SyncStatus.ERROR              -> "Ошибка синхронизации, повтор будет выполнен позже"
        SyncStatus.RETRY_SCHEDULED    -> "Синхронизация ожидает повтора"
    }
    val isProblem = record.status != SyncStatus.SUCCESS
    val container = if (isProblem) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = if (isProblem) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun StatusCountersRow(files: List<MediaFile>) {
    val total        = files.size
    val pending      = files.count { it.status == MediaFileStatus.PENDING }
    val hashing      = files.count { it.status == MediaFileStatus.HASHING }
    val checksumReady = files.count { it.status == MediaFileStatus.CHECKSUM_READY }
    val failed       = files.count { it.status == MediaFileStatus.FAILED }
    val pendingUpload = files.count { it.status == MediaFileStatus.PENDING_UPLOAD }
    val uploading    = files.count { it.status == MediaFileStatus.UPLOADING }
    val synced       = files.count { it.status == MediaFileStatus.SYNCED }

    Text(
        text = "Total: $total | Pending: $pending | Hashing: $hashing | Ready: $checksumReady | Failed: $failed",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = "Pending upload: $pendingUpload | Uploading: $uploading | Synced: $synced",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MediaFileListItem(file: MediaFile) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = file.displayName,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildSecondaryLine(file),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun buildSecondaryLine(file: MediaFile): String {
    val path     = file.relativePath ?: file.mimeType
    val size     = formatSize(file.size)
    val status   = formatStatus(file.status)
    val checksum = if (file.checksum != null) "✓ checksum" else "— checksum"
    return "$path · $size · $status · $checksum"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024     -> "%.0f KB".format(bytes / 1_024.0)
    else               -> "$bytes B"
}

private fun formatStatus(status: MediaFileStatus): String = when (status) {
    MediaFileStatus.PENDING        -> "Pending"
    MediaFileStatus.HASHING        -> "Hashing"
    MediaFileStatus.CHECKSUM_READY -> "Ready"
    MediaFileStatus.PENDING_UPLOAD -> "Pending upload"
    MediaFileStatus.UPLOADING      -> "Uploading"
    MediaFileStatus.SYNCED         -> "Synced"
    MediaFileStatus.FAILED         -> "Failed"
    MediaFileStatus.LOCAL_DELETED  -> "Deleted"
}

private fun formatScanResult(result: ScanResult): String =
    "Scan result: scanned = ${result.scanned}, inserted/updated = ${result.insertedOrUpdated}, deleted stale = ${result.deletedStale}"

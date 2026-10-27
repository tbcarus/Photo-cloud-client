package ru.tbcarus.photo_cloud_client.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.tbcarus.photo_cloud_client.media.FailureReason
import ru.tbcarus.photo_cloud_client.media.MediaFile
import ru.tbcarus.photo_cloud_client.media.MediaFileStatus
import ru.tbcarus.photo_cloud_client.media.SyncStatus
import ru.tbcarus.photo_cloud_client.media.SyncStatusRecord
import ru.tbcarus.photo_cloud_client.media.hasMediaLocationPermission
import ru.tbcarus.photo_cloud_client.media.hasMediaReadPermission
import ru.tbcarus.photo_cloud_client.ui.components.OnResumeEffect
import ru.tbcarus.photo_cloud_client.ui.screens.files.FilesViewModel

@Composable
fun FilesScreen(viewModel: FilesViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Разрешения запрашиваются только на Settings; здесь — их фактическое состояние для пояснений.
    // Перечитывается при каждом возврате на экран, в том числе из Settings и настроек Android.
    var hasMediaRead by remember { mutableStateOf(context.hasMediaReadPermission()) }
    var hasMediaLocation by remember { mutableStateOf(context.hasMediaLocationPermission()) }
    OnResumeEffect {
        hasMediaRead = context.hasMediaReadPermission()
        hasMediaLocation = context.hasMediaLocationPermission()
    }

    // Bootstrap автосинка при входе на экран (не завязан на кнопку Scan): при полном доступе к фото
    // поднимаем observer + periodic задачи через reconcile(). Системных запросов разрешений здесь нет.
    LaunchedEffect(Unit) {
        if (viewModel.isAutoSyncConfigured() && context.hasMediaReadPermission()) {
            viewModel.ensureAutoSyncStarted()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Экран — монитор состояния автосинхронизации. Ручные debug-кнопки удалены (Stage 5J.2);
        // bootstrap автосинка выполняется в LaunchedEffect выше, разрешения — на Settings, синхронизацию ведут
        // MediaChangeObserver + Поставщик и Загрузчик WorkManager.

        // Немодальный индикатор: фоновая синхронизация не блокирует экран и навигацию.
        if (state.isSyncing) {
            ProgressRow("Синхронизация выполняется…")
            Spacer(Modifier.height(8.dp))
        }

        // Итог последнего прогона sync
        state.lastSyncStatus?.let { status ->
            SyncStatusCard(status)
            Spacer(Modifier.height(8.dp))
        }

        // Предупреждение об отсутствии полного доступа к фото; запрос — на Settings.
        if (!hasMediaRead) {
            PermissionDeniedCard()
            Spacer(Modifier.height(8.dp))
        }

        // Пояснение о геоданных — только при работающем доступе к фото, иначе важнее карточка выше.
        if (!hasMediaLocation && hasMediaRead) {
            MediaLocationHintCard()
            Spacer(Modifier.height(8.dp))
        }

        // Счётчики по статусам — производные от files, не хранятся в UiState
        // TODO: запуск checksum и отображение прогресса будут добавлены отдельным этапом.
        StatusCountersRow(files = state.files)

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        // Список локальных фото
        if (state.files.isEmpty()) {
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
private fun ProgressRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                text = "Нет доступа ко всем фотографиям.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "Синхронизация невозможна: резервное копирование работает только при доступе ко всем фото. " +
                    "Разрешение предоставляется на экране Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun MediaLocationHintCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Нет доступа к геоданным фото.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "Резервное копирование продолжается, но координаты съёмки могут не сохраниться в копии. " +
                    "Доступ можно выдать на экране Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
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
    val base = "$path · $size · $status · $checksum"
    // Для FAILED показываем краткую причину (Stage 5K).
    val reason = file.failureReason?.let { " · ${formatFailureReason(it)}" } ?: ""
    return base + reason
}

private fun formatFailureReason(reason: FailureReason): String = when (reason) {
    FailureReason.CHECKSUM_IO    -> "Checksum I/O error"
    FailureReason.FILE_NOT_FOUND -> "File not found"
    FailureReason.PERMISSION     -> "Permission denied"
    FailureReason.NOT_IMAGE      -> "Not an image"
    FailureReason.HTTP_400       -> "HTTP 400"
    FailureReason.HTTP_409       -> "HTTP 409"
    FailureReason.HTTP_413       -> "HTTP 413"
    FailureReason.UNKNOWN        -> "Unknown error"
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

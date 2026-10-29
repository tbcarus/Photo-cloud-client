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
import ru.tbcarus.photo_cloud_client.media.BackupIssueKind
import ru.tbcarus.photo_cloud_client.media.BackupPhase
import ru.tbcarus.photo_cloud_client.media.BackupState
import ru.tbcarus.photo_cloud_client.media.FailureReason
import ru.tbcarus.photo_cloud_client.media.MediaFile
import ru.tbcarus.photo_cloud_client.media.MediaFileStatus
import ru.tbcarus.photo_cloud_client.media.hasMediaLocationPermission
import ru.tbcarus.photo_cloud_client.media.hasMediaReadPermission
import ru.tbcarus.photo_cloud_client.ui.components.OnResumeEffect
import ru.tbcarus.photo_cloud_client.ui.screens.files.FilesViewModel
import ru.tbcarus.photo_cloud_client.ui.screens.files.backupCountersText
import ru.tbcarus.photo_cloud_client.ui.screens.files.backupIssueText
import ru.tbcarus.photo_cloud_client.ui.screens.files.backupTitle
import ru.tbcarus.photo_cloud_client.ui.screens.files.formatBackupTime

@Composable
fun FilesScreen(viewModel: FilesViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Разрешения запрашиваются только на Settings; здесь — их фактическое состояние.
    // Перечитывается при каждом возврате на экран, в том числе из Settings и настроек Android.
    var hasMediaLocation by remember { mutableStateOf(context.hasMediaLocationPermission()) }
    OnResumeEffect {
        viewModel.onMediaAccessChecked(context.hasMediaReadPermission())
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
        // Экран — монитор состояния автосинхронизации (A7.2): одна карточка состояния backup,
        // синхронизацию ведут MediaChangeObserver + Поставщик и Загрузчик WorkManager.
        state.backupState?.let { backup ->
            BackupStateCard(backup)
            Spacer(Modifier.height(8.dp))

            // Пояснение о геоданных — только при полном доступе к фото, иначе причину показывает карточка выше.
            val hasMediaAccess = backup.issues.none { it.kind == BackupIssueKind.NO_MEDIA_PERMISSION }
            if (!hasMediaLocation && hasMediaAccess) {
                MediaLocationHintCard()
                Spacer(Modifier.height(8.dp))
            }
        }

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

/**
 * Состояние резервного копирования: фаза, все актуальные причины (проблема одной части не скрывается
 * работой другой), счётчики индекса и время последней проверки и отправки. Прогресса в процентах нет.
 */
@Composable
private fun BackupStateCard(state: BackupState) {
    val container = when (state.phase) {
        BackupPhase.ATTENTION_REQUIRED -> MaterialTheme.colorScheme.errorContainer
        BackupPhase.SYNCED -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (state.phase) {
        BackupPhase.ATTENTION_REQUIRED -> MaterialTheme.colorScheme.onErrorContainer
        BackupPhase.SYNCED -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val now = System.currentTimeMillis()
    val formatTime = { at: Long -> formatBackupTime(at, now) }

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Немодальный индикатор: прогон может идти и когда нужна реакция пользователя (Поставщик без входа).
                if (state.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = onContainer)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = backupTitle(state),
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainer
                )
            }
            state.issues.forEach { issue ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = backupIssueText(issue, state.counters, formatTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            backupCountersText(state.counters).forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodySmall, color = onContainer)
            }
            state.lastScanAt?.let {
                Text(
                    text = "Фото проверены: ${formatTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer
                )
            }
            state.lastUploadAt?.let {
                Text(
                    text = "Последняя отправка: ${formatTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer
                )
            }
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

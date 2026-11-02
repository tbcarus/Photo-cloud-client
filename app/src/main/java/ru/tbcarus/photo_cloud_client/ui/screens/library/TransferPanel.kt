package ru.tbcarus.photo_cloud_client.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.tbcarus.photo_cloud_client.transfer.TransferIssue
import ru.tbcarus.photo_cloud_client.transfer.TransferKind
import ru.tbcarus.photo_cloud_client.transfer.TransferState

/** Компактная панель текущей ручной передачи и её итога. */
@Composable
fun TransferPanel(state: TransferState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    if (state == TransferState.Idle) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (state) {
                is TransferState.Running -> RunningContent(state, onCancel)
                is TransferState.AwaitingDestination -> {
                    Text("Выберите место сохранения", style = MaterialTheme.typography.titleSmall)
                    FileName(state.fileName)
                    Actions { TextButton(onClick = onCancel) { Text("Отмена") } }
                }
                is TransferState.Finished -> FinishedContent(state, onDismiss)
                TransferState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun RunningContent(state: TransferState.Running, onCancel: () -> Unit) {
    val title = when {
        state.kind == TransferKind.DOWNLOAD -> "Скачивание"
        state.current == 0 -> "Подготовка загрузки…"
        else -> "Загрузка ${state.current} из ${state.total}"
    }
    Text(title, style = MaterialTheme.typography.titleSmall)
    state.currentName?.let { FileName(it) }
    Spacer(Modifier.padding(top = 6.dp))
    val percent = transferPercent(state.bytesDone, state.bytesTotal)
    if (percent == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.weight(1f))
            Text("  $percent%", style = MaterialTheme.typography.bodySmall)
        }
    }
    Actions { TextButton(onClick = onCancel) { Text("Отмена") } }
}

@Composable
private fun FinishedContent(state: TransferState.Finished, onDismiss: () -> Unit) {
    var showDetails by rememberSaveable(state.id) { mutableStateOf(false) }
    if (state.kind == TransferKind.DOWNLOAD) {
        val text = when {
            state.savedName != null -> "Файл сохранён: ${state.savedName}"
            state.cancelled -> "Скачивание отменено"
            else -> state.issues.firstOrNull()?.let { "Не удалось скачать «${it.name}»: ${it.reason}" } ?: "Файл не скачан"
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    } else {
        val title = when {
            state.cancelled -> "Загрузка отменена"
            state.stopReason != null -> "Загрузка остановлена: ${state.stopReason}"
            else -> "Загрузка завершена"
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text("Загружено: ${state.uploaded}", style = MaterialTheme.typography.bodySmall)
        Text("Уже существовали: ${state.existing}", style = MaterialTheme.typography.bodySmall)
        Text("Ошибок: ${state.issues.size}", style = MaterialTheme.typography.bodySmall)
    }
    Actions {
        if (state.kind == TransferKind.UPLOAD && state.issues.isNotEmpty()) {
            TextButton(onClick = { showDetails = true }) { Text("Подробности") }
        }
        TextButton(onClick = onDismiss) { Text("Закрыть") }
    }
    if (showDetails) IssuesDialog(state.issues, onClose = { showDetails = false })
}

@Composable
private fun IssuesDialog(issues: List<TransferIssue>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Подробности") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(issues) { issue ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(issue.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(issue.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } }
    )
}

@Composable
private fun FileName(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { content() }
}

/** Процент по байтам; null — общий размер неизвестен. */
internal fun transferPercent(done: Long, total: Long?): Int? {
    if (total == null || total <= 0) return null
    return (done * 100 / total).coerceIn(0, 100).toInt()
}

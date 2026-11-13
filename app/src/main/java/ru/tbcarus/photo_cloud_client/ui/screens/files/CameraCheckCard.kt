package ru.tbcarus.photo_cloud_client.ui.screens.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.tbcarus.photo_cloud_client.media.CameraCheckState
import ru.tbcarus.photo_cloud_client.media.CameraCheckView

/** Действия карточки «Проверка копий». */
data class CameraCheckActions(
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDefer: () -> Unit = {},
    val onDismiss: () -> Unit = {}
)

/**
 * Ручная сверка Camera (Q-01): кнопка запуска, прогресс по стадиям, ошибка, итог и решение по отсутствующим.
 * Всё состояние читается из Room через [view]; [busy] блокирует кнопки на время действия.
 */
@Composable
fun CameraCheckCard(view: CameraCheckView?, busy: Boolean, message: String?, actions: CameraCheckActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Проверка копий", style = MaterialTheme.typography.titleSmall)
            when (view?.check?.state) {
                null -> Idle(busy, actions)
                CameraCheckState.RUNNING -> Running(view, busy, actions)
                CameraCheckState.ERROR -> Failed(view, busy, actions)
                CameraCheckState.AWAITING_DECISION -> Awaiting(view, busy, actions)
                CameraCheckState.FINISHED -> Finished(view, busy, actions)
            }
            message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Idle(busy: Boolean, actions: CameraCheckActions) {
    Small("Сверить все фото Camera с сервером. До вашего решения новые загрузки Camera приостанавливаются.")
    Button(onClick = actions.onStart, enabled = !busy) { Text("Проверить копии") }
}

@Composable
private fun Running(view: CameraCheckView, busy: Boolean, actions: CameraCheckActions) {
    val progress = checkProgress(view)
    Small(checkProgressText(progress))
    Spacer(Modifier.height(4.dp))
    val percent = progress.percent
    if (percent == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(4.dp))
    Small(CHECK_PAUSE_TEXT)
    CancelButton(busy, actions)
}

@Composable
private fun Failed(view: CameraCheckView, busy: Boolean, actions: CameraCheckActions) {
    Small(checkProgressText(checkProgress(view)))
    Text(
        checkErrorText(view.check.error),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
    Small("Повтор выполняется автоматически; можно повторить сейчас или отменить проверку.")
    Small(CHECK_PAUSE_TEXT)
    Row {
        Button(onClick = actions.onRetry, enabled = !busy) { Text("Повторить") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = actions.onCancel, enabled = !busy) { Text("Отменить проверку") }
    }
    Small(CHECK_CANCEL_HINT)
}

@Composable
private fun Awaiting(view: CameraCheckView, busy: Boolean, actions: CameraCheckActions) {
    val missing = checkMissingCount(view)
    if (view.check.deferred) {
        // «Позже»: итог свёрнут до строки о паузе, подробности открываются по запросу.
        var expanded by rememberSaveable { mutableStateOf(false) }
        Small(checkDeferredText(missing))
        if (expanded) {
            checkSummaryLines(view).forEach { Small(it) }
            Small(CHECK_SCOPE_HINT)
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Скрыть результат" else "Показать результат") }
        Row {
            Button(onClick = actions.onConfirm, enabled = !busy) { Text(checkUploadButtonText(missing)) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = actions.onCancel, enabled = !busy) { Text("Отказаться от проверки") }
        }
        Small(CHECK_CANCEL_HINT)
    } else {
        checkSummaryLines(view).forEach { Small(it) }
        Small(CHECK_SCOPE_HINT)
        Row {
            Button(onClick = actions.onConfirm, enabled = !busy) { Text(checkUploadButtonText(missing)) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = actions.onDefer, enabled = !busy) { Text("Позже") }
        }
    }
}

@Composable
private fun Finished(view: CameraCheckView, busy: Boolean, actions: CameraCheckActions) {
    checkSummaryLines(view).forEach { Small(it) }
    Small(checkFinishedText(view))
    Row {
        Button(onClick = actions.onStart, enabled = !busy) { Text("Проверить копии") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = actions.onDismiss, enabled = !busy) { Text("Закрыть") }
    }
}

@Composable
private fun CancelButton(busy: Boolean, actions: CameraCheckActions) {
    OutlinedButton(onClick = actions.onCancel, enabled = !busy) { Text("Отменить проверку") }
    Small(CHECK_CANCEL_HINT)
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

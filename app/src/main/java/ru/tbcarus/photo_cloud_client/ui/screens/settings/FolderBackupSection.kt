package ru.tbcarus.photo_cloud_client.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.tbcarus.photo_cloud_client.backup.BackupSource
import ru.tbcarus.photo_cloud_client.backup.RegistrationState
import ru.tbcarus.photo_cloud_client.backup.SourceAccess
import ru.tbcarus.photo_cloud_client.backup.SourceFileCounters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ru.tbcarus.photo_cloud_client.ui.components.OnResumeEffect

/** Подключение Android-папок как источников автоматической загрузки и их состояние. */
@Composable
fun FolderBackupSection(viewModel: FolderBackupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onFolderPicked(uri)
    }
    OnResumeEffect { viewModel.refreshAccess() }

    Text("Автоматическая загрузка папок", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Подключённая папка получает свою папку на сервере в AutoUploads: новые файлы и папки загружаются автоматически. " +
            "Файлы, изменённые после загрузки, пока не отправляются — сервер хранит их прежнюю версию.",
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(8.dp))

    if (state.account == null) {
        Text("Войдите в аккаунт и задайте адрес сервера, чтобы подключать папки.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    val picked = state.picked
    if (picked == null) {
        Button(
            onClick = { pickFolder.launch(null) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Добавить папку") }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Выбрана папка: ${picked.localName}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = picked.serverName,
                    onValueChange = viewModel::onPickedNameChanged,
                    label = { Text("Имя на сервере") },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::connect, enabled = !state.busy) { Text("Подключить") }
                    OutlinedButton(onClick = viewModel::cancelPick, enabled = !state.busy) { Text("Отмена") }
                }
            }
        }
    }

    state.message?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    for (source in state.sources) {
        Spacer(Modifier.height(12.dp))
        SourceCard(
            source = source,
            busy = state.busy,
            counters = state.counters[source.id],
            onRetry = { name -> viewModel.retry(source.id, name) },
            onCancel = { viewModel.cancelFailed(source.id) }
        )
    }
}

@Composable
private fun SourceCard(
    source: BackupSource,
    busy: Boolean,
    counters: SourceFileCounters?,
    onRetry: (String?) -> Unit,
    onCancel: () -> Unit
) {
    // Черновик имени нужен только после окончательного отказа Server.
    var draft by rememberSaveable(source.id, source.serverName) { mutableStateOf(source.serverName) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(source.serverName, style = MaterialTheme.typography.titleSmall)
            Text("Папка на устройстве: ${source.localName}", style = MaterialTheme.typography.bodySmall)
            Text(serverFolderText(source), style = MaterialTheme.typography.bodySmall)
            Text(sourceStateText(source), style = MaterialTheme.typography.bodySmall)
            if (source.registrationState == RegistrationState.ACTIVE || source.registrationState == RegistrationState.REMOTE_DISABLED) {
                Text(lastScanText(source.lastScanAt), style = MaterialTheme.typography.bodySmall)
                Text(countersText(counters), style = MaterialTheme.typography.bodySmall)
                changedText(counters)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            source.registrationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            when (source.registrationState) {
                RegistrationState.ACTIVE, RegistrationState.REMOTE_DISABLED -> Unit
                RegistrationState.PENDING_REGISTRATION -> {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onRetry(null) }, enabled = !busy) { Text("Повторить") }
                }
                RegistrationState.REGISTRATION_FAILED -> {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Имя на сервере") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRetry(draft) }, enabled = !busy) { Text("Повторить") }
                        OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Отменить") }
                    }
                }
            }
        }
    }
}

internal fun serverFolderText(source: BackupSource): String = when (val id = source.serverRootFolderId) {
    null -> "На сервере: AutoUploads/${source.serverName} — не создана"
    else -> "На сервере: AutoUploads/${source.serverName} (ID $id)"
}

/** Одно состояние источника: регистрация важнее доступа, доступ — только у активного. */
internal fun sourceStateText(source: BackupSource): String = when (source.registrationState) {
    RegistrationState.PENDING_REGISTRATION -> "Ожидает подтверждения сервера"
    RegistrationState.REGISTRATION_FAILED -> "Не подключена: сервер отклонил имя"
    RegistrationState.REMOTE_DISABLED -> "Отключён на сервере: файлы больше не отправляются"
    RegistrationState.ACTIVE -> when (source.accessState) {
        SourceAccess.AVAILABLE -> "Активен"
        SourceAccess.UNAVAILABLE -> "Недоступен: папка на устройстве сейчас не отвечает"
        SourceAccess.PERMISSION_LOST -> "Нет доступа к папке (PERMISSION_LOST)"
    }
}

private val scanTimeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

internal fun lastScanText(at: Long?): String =
    if (at == null) "Сканирование ещё не завершалось"
    else "Последнее сканирование: " + scanTimeFormat.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

internal fun countersText(counters: SourceFileCounters?): String {
    val c = counters ?: SourceFileCounters(0, 0, 0, 0, 0, 0)
    return "Загружено: ${c.synced} · Ожидают: ${c.pending} · Ошибок: ${c.failed} · Пропущено: ${c.skipped}"
}

/** Изменённые после отправки файлы не перезаписываются: прежнюю версию сохранит следующий этап. */
internal fun changedText(counters: SourceFileCounters?): String? =
    counters?.changed?.takeIf { it > 0 }?.let { "Изменены после загрузки и пока не отправлены: $it" }

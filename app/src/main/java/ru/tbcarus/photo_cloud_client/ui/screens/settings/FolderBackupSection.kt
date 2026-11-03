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
import ru.tbcarus.photo_cloud_client.ui.components.OnResumeEffect

/** Подключение Android-папок как источников автоматической загрузки. Сами файлы на этом этапе не передаются. */
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
        "Подключённая папка получает свою папку на сервере в AutoUploads. Сейчас выполняется только подключение: " +
            "файлы папки ещё не загружаются.",
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
            onRetry = { name -> viewModel.retry(source.id, name) },
            onCancel = { viewModel.cancelFailed(source.id) }
        )
    }
}

@Composable
private fun SourceCard(source: BackupSource, busy: Boolean, onRetry: (String?) -> Unit, onCancel: () -> Unit) {
    // Черновик имени нужен только после окончательного отказа Server.
    var draft by rememberSaveable(source.id, source.serverName) { mutableStateOf(source.serverName) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(source.serverName, style = MaterialTheme.typography.titleSmall)
            Text("Папка на устройстве: ${source.localName}", style = MaterialTheme.typography.bodySmall)
            Text(serverFolderText(source), style = MaterialTheme.typography.bodySmall)
            Text(registrationText(source.registrationState), style = MaterialTheme.typography.bodySmall)
            accessText(source.accessState)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            source.registrationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            when (source.registrationState) {
                RegistrationState.ACTIVE -> Unit
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

internal fun registrationText(state: RegistrationState): String = when (state) {
    RegistrationState.ACTIVE -> "Подключена"
    RegistrationState.PENDING_REGISTRATION -> "Ожидает подтверждения сервера"
    RegistrationState.REGISTRATION_FAILED -> "Не подключена: сервер отклонил имя"
}

internal fun accessText(access: SourceAccess): String? = when (access) {
    SourceAccess.AVAILABLE -> null
    SourceAccess.UNAVAILABLE -> "Папка на устройстве сейчас недоступна"
    SourceAccess.PERMISSION_LOST -> "Доступ к папке потерян (PERMISSION_LOST)"
}

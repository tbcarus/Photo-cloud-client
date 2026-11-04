package ru.tbcarus.photo_cloud_client.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Подтверждение отключения автоматического источника: одно для Settings и Library. */
@Composable
fun DisableSourceDialog(folderName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отключить автоматическую загрузку папки «$folderName»?") },
        text = {
            Text(
                "Новые и изменённые файлы больше не будут отправляться на сервер. " +
                    "Уже сохранённые файлы и предыдущие версии останутся в AutoUploads."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text("Отключить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

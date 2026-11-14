package ru.tbcarus.photo_cloud_client.ui.screens.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.tbcarus.photo_cloud_client.transfer.ManualUploadJob

@Composable
fun ManualUploadPanel(jobs: List<ManualUploadJob>, onCancel: (String) -> Unit,
                      onRetry: (String) -> Unit, onDismiss: (String) -> Unit) {
    if (jobs.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Ручные загрузки", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(jobs, key = { it.id }) { job ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(job.name, style = MaterialTheme.typography.bodyMedium)
                        val status = when (job.status) {
                            ManualUploadJob.QUEUED -> "Ожидает загрузки"
                            ManualUploadJob.VERIFYING -> "Проверка файла"
                            ManualUploadJob.RUNNING -> "Загружается"
                            ManualUploadJob.COMPLETED -> "Загружен"
                            ManualUploadJob.FAILED -> "Ошибка: ${job.error ?: "неизвестная"}"
                            ManualUploadJob.UNCERTAIN -> "Уточняется результат Server"
                            ManualUploadJob.CANCEL_PENDING -> "Ожидает отмены на Server"
                            ManualUploadJob.RETRY_PENDING -> "Подготовка повторной попытки"
                            else -> "Отменён"
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        if (job.status == ManualUploadJob.RUNNING && job.size != null && job.size > 0) {
                            val progress = (job.confirmedOffset.toDouble() / job.size.toDouble()).toFloat().coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        }
                        Row {
                            if (job.status in setOf(ManualUploadJob.QUEUED, ManualUploadJob.RUNNING,
                                    ManualUploadJob.VERIFYING, ManualUploadJob.UNCERTAIN)) {
                                TextButton(onClick = { onCancel(job.id) }) { Text("Отмена") }
                            }
                            if (job.status == ManualUploadJob.FAILED) {
                                TextButton(onClick = { onRetry(job.id) }) { Text("Повторить") }
                            }
                            if (job.status in setOf(ManualUploadJob.FAILED, ManualUploadJob.COMPLETED, ManualUploadJob.CANCELLED)) {
                                TextButton(onClick = { onDismiss(job.id) }) { Text("Убрать") }
                            }
                        }
                    }
                }
            }
        }
    }
}

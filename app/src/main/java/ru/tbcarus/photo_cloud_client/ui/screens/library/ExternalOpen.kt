package ru.tbcarus.photo_cloud_client.ui.screens.library

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.library.external.ExternalError
import ru.tbcarus.photo_cloud_client.library.external.ExternalFile
import ru.tbcarus.photo_cloud_client.library.external.canOpenExternally
import ru.tbcarus.photo_cloud_client.library.external.externalViewIntent
import ru.tbcarus.photo_cloud_client.library.external.originalContentUri
import ru.tbcarus.photo_cloud_client.library.pdf.isPdf
import ru.tbcarus.photo_cloud_client.library.video.isPlayableVideo

/** Файл без встроенного просмотра (не изображение, видео или PDF) открывается установленным приложением Android. */
fun opensExternally(file: FileItemDto): Boolean =
    !isViewable(file) && !isPlayableVideo(file) && !isPdf(file) && canOpenExternally(file)

/** Открытие во внешнем приложении: загрузка копии, запуск, ожидание возврата или ошибка. */
sealed interface ExternalOpen {
    data class Loading(val name: String) : ExternalOpen

    /** Копия готова и защищена от вытеснения; UI запускает внешнее приложение. */
    class Ready(val file: ExternalFile.Ready) : ExternalOpen

    /** Внешнее приложение запущено; защита копии снимается после возврата в PhotoCloud. */
    data class Launched(val name: String) : ExternalOpen

    data class Failed(val name: String, val reason: ExternalError) : ExternalOpen
}

/** Диалоги и запуск внешнего приложения; Library под ними не меняется. */
@Composable
fun LibraryExternalOpen(viewModel: LibraryViewModel) {
    val state by viewModel.externalOpen.collectAsState()
    val context = LocalContext.current

    // Возврат в приложение после внешнего просмотра или отказа от выбора приложения.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onExternalReturned()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (val current = state) {
        null, is ExternalOpen.Launched -> Unit
        is ExternalOpen.Loading -> AlertDialog(
            onDismissRequest = viewModel::cancelExternal,
            title = { Text("Открытие файла") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    Text("Загрузка «${current.name}»")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::cancelExternal) { Text("Отмена") } }
        )
        is ExternalOpen.Ready -> LaunchedEffect(current) {
            val ready = current.file
            try {
                // Activity не сохраняется: адрес и Intent создаются здесь и сразу передаются системе.
                context.startActivity(externalViewIntent(originalContentUri(context, ready), ready.name, ready.mimeType))
                viewModel.onExternalLaunched()
            } catch (e: ActivityNotFoundException) {
                viewModel.onExternalFailed(ExternalError.NO_APP)
            }
        }
        is ExternalOpen.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismissExternal,
            title = { Text(current.name) },
            text = { Text(externalErrorText(current.reason)) },
            confirmButton = { TextButton(onClick = viewModel::dismissExternal) { Text("OK") } }
        )
    }
}

internal fun externalErrorText(reason: ExternalError): String = when (reason) {
    ExternalError.NO_CONNECTION -> "Нет связи с сервером"
    ExternalError.NOT_FOUND -> "Файл не найден на сервере"
    ExternalError.UNAUTHORIZED -> "Требуется вход"
    ExternalError.NO_SPACE -> "Недостаточно места на устройстве"
    ExternalError.TOO_LARGE -> "Файл больше 1 ГиБ и не помещается в кеш для открытия. Сохраните его через «Скачать»"
    ExternalError.CORRUPTED -> "Скачанные данные не совпадают с файлом на сервере"
    ExternalError.FAILED -> "Не удалось загрузить файл"
    ExternalError.NO_APP -> "Нет приложения для открытия файлов этого типа"
}

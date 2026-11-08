package ru.tbcarus.photo_cloud_client.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.rememberLazyListState
import ru.tbcarus.photo_cloud_client.api.models.ShareDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal const val GRANTED_LIST_TAG = "granted-list"
private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Окна управления доступами поверх Library: «Поделиться», изменение срока, отзыв. */
@Composable
fun LibrarySharingDialogs(viewModel: LibraryViewModel) {
    val sharing by viewModel.sharingState.collectAsState()
    sharing.dialog?.let { ShareObjectDialog(it, viewModel) }
    sharing.edit?.let { edit ->
        AlertDialog(
            onDismissRequest = viewModel::dismissEditShare,
            properties = DialogProperties(dismissOnClickOutside = !edit.saving, dismissOnBackPress = !edit.saving),
            title = { Text("Срок доступа") },
            text = {
                Column {
                    Text(edit.share.recipient?.email.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    ExpiryInput(edit.expiry, enabled = !edit.saving, onChange = viewModel::onEditShareExpiryChange)
                    edit.error?.let { ErrorText(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmEditShare, enabled = !edit.saving) {
                    Text(if (edit.saving) "Сохранение…" else "Сохранить")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissEditShare, enabled = !edit.saving) { Text("Отмена") } }
        )
    }
    sharing.revoke?.let { revoke ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRevokeShare,
            properties = DialogProperties(dismissOnClickOutside = !revoke.revoking, dismissOnBackPress = !revoke.revoking),
            title = { Text("Отозвать доступ?") },
            text = {
                Column {
                    Text(
                        "${revoke.share.recipient?.email.orEmpty()} больше не сможет открыть «${revoke.share.resourceName.orEmpty()}». " +
                            "Сам объект не изменится."
                    )
                    revoke.error?.let { ErrorText(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRevokeShare, enabled = !revoke.revoking) {
                    Text(if (revoke.revoking) "Отзыв…" else "Отозвать", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissRevokeShare, enabled = !revoke.revoking) { Text("Отмена") } }
        )
    }
}

/** «Поделиться»: форма выдачи и выданные доступы объекта; отключение доступа всем — отдельным подтверждением. */
@Composable
private fun ShareObjectDialog(dialog: ShareDialog, viewModel: LibraryViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeShare,
        properties = DialogProperties(dismissOnClickOutside = !dialog.busy, dismissOnBackPress = !dialog.busy),
        title = { Text("Поделиться «${dialog.target.name}»", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Только просмотр и скачивание", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dialog.emails,
                    onValueChange = viewModel::onShareEmailsChange,
                    enabled = !dialog.granting,
                    label = { Text("Email получателей") },
                    supportingText = { Text("Через запятую или с новой строки") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                ExpiryInput(dialog.expiry, enabled = !dialog.granting, onChange = viewModel::onShareExpiryChange)
                dialog.error?.let { ErrorText(it) }
                dialog.info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::grantShare, enabled = !dialog.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (dialog.granting) "Предоставление…" else "Предоставить доступ")
                }
                Spacer(Modifier.height(16.dp))
                Text("Доступ предоставлен", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                when {
                    dialog.loading -> CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                    dialog.loadError != null -> {
                        ErrorText(dialog.loadError)
                        OutlinedButton(onClick = viewModel::retryObjectShares) { Text("Повторить") }
                    }
                    dialog.shares.isEmpty() -> Text("Пока ни у кого", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        dialog.shares.forEach { share ->
                            ShareRow(
                                share = share,
                                title = share.recipient?.email ?: share.recipient?.displayName.orEmpty(),
                                subtitle = shareTerms(share),
                                enabled = !dialog.busy,
                                viewModel = viewModel
                            )
                            HorizontalDivider()
                        }
                        if (dialog.loadingMore) {
                            CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                        } else if (dialog.hasNext) {
                            TextButton(onClick = viewModel::loadMoreObjectShares) { Text("Показать ещё") }
                        }
                        TextButton(onClick = viewModel::requestRevokeAllShares, enabled = !dialog.busy) {
                            Text("Отключить доступ всем пользователям", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::closeShare, enabled = !dialog.busy) { Text("Закрыть") } }
    )
    if (dialog.confirmingRevokeAll) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRevokeAllShares,
            properties = DialogProperties(dismissOnClickOutside = !dialog.revokingAll, dismissOnBackPress = !dialog.revokingAll),
            title = { Text("Отключить доступ всем?") },
            text = {
                Text(
                    "Все пользователи потеряют доступ к «${dialog.target.name}». Сам объект не удаляется; " +
                        "отдельно предоставленные вложенные файлы и папки остаются доступны."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRevokeAllShares, enabled = !dialog.revokingAll) {
                    Text(if (dialog.revokingAll) "Отключение…" else "Отключить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRevokeAllShares, enabled = !dialog.revokingAll) { Text("Отмена") }
            }
        )
    }
}

/** Строка выданного доступа: срок, статус и действия «Изменить срок» и «Отозвать доступ». */
@Composable
private fun ShareRow(share: ShareDto, title: String, subtitle: String, enabled: Boolean, viewModel: LibraryViewModel, icon: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (icon) {
            Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (share.isFolder) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = if (share.isFolder) "Папка" else "Файл",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (share.isExpired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ActionsMenu(
            listOf(
                RowAction("Изменить срок", { viewModel.startEditShare(share) }, enabled = enabled),
                RowAction("Отозвать доступ", { viewModel.startRevokeShare(share) }, enabled = enabled, destructive = true)
            )
        )
    }
}

/** Раздел «Предоставлено мной»: каждый выданный доступ — отдельная строка, от новых к старым, со страницами Server. */
@Composable
fun GrantedSection(viewModel: LibraryViewModel) {
    val sharing by viewModel.sharingState.collectAsState()
    val granted = sharing.granted ?: return
    Column(Modifier.fillMaxSize()) {
        granted.info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        when {
            granted.loading && granted.entries.isEmpty() -> Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            granted.error != null -> StateMessage(granted.error, action = "Повторить", onAction = viewModel::refresh)
            granted.entries.isEmpty() -> StateMessage("Вы пока никому не предоставили доступ. Откройте меню файла или папки и выберите «Поделиться».")
            else -> {
                val listState = rememberLazyListState()
                val nearEnd by remember(listState) {
                    derivedStateOf {
                        val layout = listState.layoutInfo
                        val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                        last >= 0 && last >= layout.totalItemsCount - 10
                    }
                }
                LaunchedEffect(nearEnd, granted.hasNext, granted.loadingMore) {
                    if (nearEnd) viewModel.loadMoreGranted()
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(GRANTED_LIST_TAG)) {
                    items(granted.entries, key = { "granted-${it.id}" }) { share ->
                        ShareRow(
                            share = share,
                            title = share.resourceName.orEmpty(),
                            subtitle = listOfNotNull(
                                share.recipient?.let { "Получатель: ${it.email ?: it.displayName.orEmpty()}" },
                                shareTerms(share)
                            ).joinToString(" · "),
                            enabled = true,
                            viewModel = viewModel,
                            icon = true
                        )
                        HorizontalDivider()
                    }
                    if (granted.loadingMore) item(key = "loading-more") { LoadingMore() }
                    granted.loadMoreError?.let { error ->
                        item(key = "load-more-error") { LoadMoreError(error, viewModel::retryMoreGranted) }
                    }
                }
            }
        }
    }
}

/** Срок и статус в часовом поясе устройства: «до дд.мм.гггг чч:мм» или «Бессрочно», у истёкшего — «Истёк». */
fun shareTerms(share: ShareDto): String =
    listOfNotNull(
        formatShareExpiry(share.expiresAt)?.let { "до $it" } ?: "Бессрочно".takeIf { share.expiresAt == null },
        "Истёк".takeIf { share.isExpired }
    ).joinToString(" · ")

/** Выбор срока: бессрочно (по умолчанию), на N дней, до даты включительно (дата выбирается в календаре). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryInput(choice: ExpiryChoice, enabled: Boolean, onChange: (ExpiryChoice) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ExpiryOption("Бессрочно", choice is ExpiryChoice.Never, enabled) { onChange(ExpiryChoice.Never) }
        ExpiryOption("На несколько дней", choice is ExpiryChoice.Days, enabled) {
            if (choice !is ExpiryChoice.Days) onChange(ExpiryChoice.Days())
        }
        if (choice is ExpiryChoice.Days) {
            OutlinedTextField(
                value = choice.text,
                onValueChange = { onChange(ExpiryChoice.Days(it.filter(Char::isDigit).take(5))) },
                enabled = enabled,
                label = { Text("Число дней") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp)
            )
        }
        ExpiryOption("До даты включительно", choice is ExpiryChoice.Until, enabled) {
            if (choice !is ExpiryChoice.Until) onChange(ExpiryChoice.Until())
            picking = true
        }
        if (choice is ExpiryChoice.Until) {
            TextButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.padding(start = 40.dp)) {
                Text(choice.date?.format(dateFormat) ?: "Выбрать дату")
            }
        }
    }
    if (picking) {
        val today = LocalDate.now()
        val initial = (choice as? ExpiryChoice.Until)?.date ?: today
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // Календарь передаёт полночь UTC выбранной даты; прошедшие дни недоступны.
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            }
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        onChange(ExpiryChoice.Until(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()))
                    }
                    picking = false
                }) { Text("Выбрать") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена") } }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun ExpiryOption(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onSelect)
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}

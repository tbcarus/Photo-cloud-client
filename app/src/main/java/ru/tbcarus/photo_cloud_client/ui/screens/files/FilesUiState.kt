package ru.tbcarus.photo_cloud_client.ui.screens.files

import ru.tbcarus.photo_cloud_client.media.BackupState
import ru.tbcarus.photo_cloud_client.media.MediaFile

data class FilesUiState(
    val files: List<MediaFile> = emptyList(),
    /** Состояние резервного копирования; null — ещё не получено. */
    val backupState: BackupState? = null,
    /** Ручной повтор FAILED выполняется: кнопка неактивна. */
    val retryingFailed: Boolean = false,
    /** Итог последнего ручного повтора для строки под кнопкой; null — сообщения нет. */
    val retryMessage: RetryMessage? = null
)

/** [needsSettings] — причина в доступе к фото, рядом показывается переход в Settings. */
data class RetryMessage(val text: String, val needsSettings: Boolean = false)

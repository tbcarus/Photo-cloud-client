package ru.tbcarus.photo_cloud_client.ui.screens.files

import ru.tbcarus.photo_cloud_client.media.BackupState
import ru.tbcarus.photo_cloud_client.media.MediaFile

data class FilesUiState(
    val files: List<MediaFile> = emptyList(),
    /** Состояние резервного копирования; null — ещё не получено. */
    val backupState: BackupState? = null
)

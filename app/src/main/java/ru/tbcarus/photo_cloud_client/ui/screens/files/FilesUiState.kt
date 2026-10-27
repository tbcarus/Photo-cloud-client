package ru.tbcarus.photo_cloud_client.ui.screens.files

import ru.tbcarus.photo_cloud_client.media.MediaFile
import ru.tbcarus.photo_cloud_client.media.SyncStatusRecord

data class FilesUiState(
    val files: List<MediaFile> = emptyList(),
    val isSyncing: Boolean = false,
    val lastSyncStatus: SyncStatusRecord? = null
)

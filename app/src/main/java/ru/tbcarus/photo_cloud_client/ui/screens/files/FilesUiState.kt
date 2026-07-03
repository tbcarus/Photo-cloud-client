package ru.tbcarus.photo_cloud_client.ui.screens.files

import ru.tbcarus.photo_cloud_client.media.MediaFile
import ru.tbcarus.photo_cloud_client.media.ScanResult
import ru.tbcarus.photo_cloud_client.media.SyncStatusRecord

data class FilesUiState(
    val files: List<MediaFile> = emptyList(),
    val isScanning: Boolean = false,
    val lastScanResult: ScanResult? = null, // результат последнего успешного scan
    val permissionDenied: Boolean = false,  // нет разрешения на доступ к медиа
    val errorMessage: String? = null,       // ошибка прогона (не per-file)
    val isSyncing: Boolean = false,
    val lastSyncStatus: SyncStatusRecord? = null
)

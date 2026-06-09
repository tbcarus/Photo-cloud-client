package ru.tbcarus.photo_cloud_client.media

/**
 * Итоговая статистика синхронизации локального индекса с MediaStore.
 * [insertedOrUpdated] — количество записей, прошедших insert+update pipeline;
 * не различает «новые» и «обновлённые», так как insertAll использует OnConflict.IGNORE.
 */
data class ScanResult(
    val scanned: Int,
    val insertedOrUpdated: Int,
    val deletedStale: Int
)

/** Результат MediaFileRepository.scanImages() — после синхронизации с Room. */
sealed interface ScanOutcome {
    data class Success(val result: ScanResult) : ScanOutcome
    data object PermissionDenied : ScanOutcome
    data class Error(val message: String) : ScanOutcome
}

/** Результат MediaStoreRepository.loadImages() — только чтение из MediaStore. */
sealed interface MediaStoreScanOutcome {
    data class Success(val images: List<MediaStoreImage>) : MediaStoreScanOutcome
    data object PermissionDenied : MediaStoreScanOutcome
    data class Error(val message: String) : MediaStoreScanOutcome
}

package ru.tbcarus.photo_cloud_client.media

/**
 * Агрегированный результат одного прогона авто-sync (scan → checksum → pre-check → upload).
 * Используется для логов / outputData. Отдельного UI под результат на 5E нет.
 */
data class SyncResult(
    val scan: ScanResult? = null,
    val checksum: ChecksumResult? = null,
    val precheck: ChecksumPrecheckResult? = null,
    val upload: UploadResult? = null
)

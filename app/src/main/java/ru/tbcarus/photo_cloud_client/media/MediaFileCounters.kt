package ru.tbcarus.photo_cloud_client.media

/**
 * Число строк локального индекса по группам состояний (A7.1); [total] — сумма групп.
 * [toUpload] включает и CHECKSUM_READY без checksum: строка всё равно не обработана.
 */
data class MediaFileCounters(
    val total: Int = 0,
    /** SYNCED. */
    val synced: Int = 0,
    /** Локальная обработка Поставщика: PENDING и HASHING. */
    val preparing: Int = 0,
    /** Серверная обработка Загрузчика: CHECKSUM_READY, PENDING_UPLOAD и UPLOADING. */
    val toUpload: Int = 0,
    /** FAILED. */
    val failed: Int = 0
)

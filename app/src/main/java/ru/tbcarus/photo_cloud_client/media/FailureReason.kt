package ru.tbcarus.photo_cloud_client.media

/**
 * Причина перехода записи в [MediaFileStatus.FAILED].
 * HTTP-коды намеренно не схлопываются в один reason — конкретный код нужен для UI.
 */
enum class FailureReason {
    CHECKSUM_IO,     // временная IO-ошибка при чтении/хэшировании — единственная retryable
    FILE_NOT_FOUND,  // файл не открывается / удалён / перемещён
    PERMISSION,      // SecurityException при доступе к файлу
    NOT_IMAGE,       // не image (на текущем этапе грузим только фото)
    HTTP_400,
    HTTP_409,
    HTTP_413,
    UNKNOWN;

    /** Сейчас автоматически повторяем только временные IO-ошибки checksum. */
    val isRetryable: Boolean
        get() = this == CHECKSUM_IO
}

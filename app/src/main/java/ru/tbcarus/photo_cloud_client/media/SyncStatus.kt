package ru.tbcarus.photo_cloud_client.media

/**
 * Итог последнего прогона авто-sync — для отображения в UI.
 * Хранится в памяти (см. [SyncStatusStore]); после перезапуска приложения сбрасывается.
 */
data class SyncStatusRecord(
    val status: SyncStatus,
    val timestamp: Long
)

enum class SyncStatus {
    /** Прогон завершился успешно, очередь пуста. */
    SUCCESS,

    /** Сервер недоступен (пустой baseUrl или ping failed) — WorkManager повторит позже. */
    SERVER_UNAVAILABLE,

    /** Одна из стадий оборвалась на transient-ошибке — WorkManager повторит позже. */
    ERROR,

    /** Прогон отработал, но осталась очередь на upload — запланирован повтор. */
    RETRY_SCHEDULED
}

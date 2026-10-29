package ru.tbcarus.photo_cloud_client.media

/** Проблема прогона части синхронизации; сохраняется в [SyncStatusStore], успешный прогон её снимает. */
enum class SyncStatus {
    /** Сервер не ответил на ping — WorkManager повторит позже. */
    SERVER_UNAVAILABLE,

    /** Одна из стадий оборвалась на transient-ошибке или исключении — WorkManager повторит позже. */
    ERROR,

    /** Upload остановлен временной ошибкой — запланирован повтор. */
    RETRY_SCHEDULED
}

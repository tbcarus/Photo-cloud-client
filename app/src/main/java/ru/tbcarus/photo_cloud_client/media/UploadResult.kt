package ru.tbcarus.photo_cloud_client.media

/**
 * Итоговая статистика одного прогона upload.
 * [leftPending] — файлы, оставленные/возвращённые в PENDING_UPLOAD из-за transient-ошибки.
 */
data class UploadResult(
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
    val leftPending: Int
)

/** Результат всего запуска [FileUploadRepository.uploadPendingCameraFiles]. */
sealed interface UploadOutcome {
    data class Success(val result: UploadResult) : UploadOutcome
    data class Error(
        val message: String,
        val result: UploadResult? = null
    ) : UploadOutcome
}

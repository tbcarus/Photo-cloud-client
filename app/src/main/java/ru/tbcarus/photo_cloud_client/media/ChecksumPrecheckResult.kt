package ru.tbcarus.photo_cloud_client.media

/**
 * Итоговая статистика одного прогона pre-check.
 * [unchanged] — записи, чей checksum сервер не вернул ни в existing, ни в missing;
 * они остаются CHECKSUM_READY и попадут в следующий прогон.
 */
data class ChecksumPrecheckResult(
    val checked: Int,        // всего обработано записей
    val existing: Int,       // переведено в SYNCED (дубль уже на сервере)
    val pendingUpload: Int,  // переведено в PENDING_UPLOAD (нужен upload)
    val unchanged: Int       // осталось CHECKSUM_READY
)

/** Результат всего запуска [ChecksumPrecheckRepository.runCameraPrecheck]. */
sealed interface ChecksumPrecheckOutcome {
    data class Success(val result: ChecksumPrecheckResult) : ChecksumPrecheckOutcome
    data class Error(val message: String) : ChecksumPrecheckOutcome
}

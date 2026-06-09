package ru.tbcarus.photo_cloud_client.media

/**
 * Итоговая статистика одного прогона вычисления checksum.
 * Per-file ошибки фиксируются статусом FAILED в БД, а не здесь.
 */
data class ChecksumResult(
    val processed: Int,  // сколько файлов взято в работу (переведено в HASHING)
    val succeeded: Int,  // успешно посчитан SHA-256 и статус CHECKSUM_READY
    val failed: Int      // ошибка чтения/доступа, статус FAILED
)

/** Результат всего запуска [ChecksumRepository.computePendingChecksums]. */
sealed interface ChecksumOutcome {
    data class Success(val result: ChecksumResult) : ChecksumOutcome
    data object PermissionDenied : ChecksumOutcome
    data class Error(val message: String) : ChecksumOutcome
}

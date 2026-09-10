package ru.tbcarus.photo_cloud_client.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaFileDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(file: MediaFile)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(files: List<MediaFile>)

    @Query("SELECT * FROM media_files ORDER BY createdAt DESC")
    fun getAll(): Flow<List<MediaFile>>

    @Query("SELECT * FROM media_files WHERE status = :status ORDER BY createdAt DESC")
    fun getByStatus(status: MediaFileStatus): Flow<List<MediaFile>>

    fun getPending(): Flow<List<MediaFile>> = getByStatus(MediaFileStatus.PENDING)

    @Query("UPDATE media_files SET status = :status WHERE mediaStoreId = :mediaStoreId")
    suspend fun updateStatus(mediaStoreId: Long, status: MediaFileStatus)

    // Успешный checksum — стабильное состояние, чистим failure-метаданные (Stage 5K).
    @Query("""
        UPDATE media_files
        SET checksum = :checksum,
            status = :status,
            failureReason = NULL,
            retryCount = 0,
            lastFailureAt = NULL
        WHERE mediaStoreId = :mediaStoreId
    """)
    suspend fun updateChecksum(
        mediaStoreId: Long,
        checksum: String,
        status: MediaFileStatus = MediaFileStatus.CHECKSUM_READY
    )

    /**
     * Атомарный перевод записи в FAILED с причиной (Stage 5K).
     * Инкремент retryCount делается в SQL, без read-modify-write в Kotlin.
     */
    @Query("""
        UPDATE media_files
        SET status = 'FAILED',
            failureReason = :reason,
            retryCount = retryCount + 1,
            lastFailureAt = :failureAt
        WHERE mediaStoreId = :mediaStoreId
    """)
    suspend fun markFailed(mediaStoreId: Long, reason: FailureReason, failureAt: Long)

    /**
     * Retryable FAILED-записи, готовые к авто-повтору: конкретная причина,
     * не исчерпан лимит попыток и прошёл cooldown ([retryBefore] = now - cooldown).
     */
    @Query("""
        SELECT * FROM media_files
        WHERE status = 'FAILED'
          AND failureReason = :reason
          AND retryCount < :maxRetryCount
          AND lastFailureAt <= :retryBefore
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getRetryableFailed(
        reason: FailureReason,
        maxRetryCount: Int,
        retryBefore: Long,
        limit: Int
    ): List<MediaFile>

    /**
     * Возвращает retryable-запись в pipeline. retryCount намеренно НЕ сбрасывается —
     * иначе лимит попыток перестанет действовать.
     */
    @Query("""
        UPDATE media_files
        SET status = 'PENDING',
            failureReason = NULL,
            lastFailureAt = NULL
        WHERE mediaStoreId = :mediaStoreId
    """)
    suspend fun resetFailedForRetry(mediaStoreId: Long)

    @Query("""
        UPDATE media_files
        SET displayName = :displayName,
            relativePath = :relativePath,
            mimeType = :mimeType,
            size = :size,
            createdAt = :createdAt,
            lastModified = :lastModified,
            uri = :uri
        WHERE mediaStoreId = :mediaStoreId
    """)
    suspend fun updateLocalMetadata(
        mediaStoreId: Long,
        displayName: String,
        relativePath: String?,
        mimeType: String,
        size: Long,
        createdAt: Long,
        lastModified: Long,
        uri: String
    )

    // Успешный upload → SYNCED, чистим failure-метаданные (Stage 5K).
    @Query("""
        UPDATE media_files
        SET status = 'SYNCED',
            serverFileId = :serverFileId,
            failureReason = NULL,
            retryCount = 0,
            lastFailureAt = NULL
        WHERE mediaStoreId = :mediaStoreId
    """)
    suspend fun markUploaded(mediaStoreId: Long, serverFileId: Long)

    @Query("SELECT COUNT(*) FROM media_files WHERE mediaStoreId = :mediaStoreId")
    suspend fun exists(mediaStoreId: Long): Int

    @Query("SELECT mediaStoreId FROM media_files")
    suspend fun getAllMediaStoreIds(): List<Long>

    @Query("DELETE FROM media_files WHERE mediaStoreId IN (:ids)")
    suspend fun deleteByMediaStoreIds(ids: List<Long>)

    /**
     * Разовый snapshot записей с заданным статусом (не Flow).
     * Используется для batch-обработки checksum без загрузки всей библиотеки в память.
     */
    @Query("SELECT * FROM media_files WHERE status = :status ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByStatusOnce(status: MediaFileStatus, limit: Int): List<MediaFile>

    /**
     * Массово переводит все записи из [sourceStatus] в [targetStatus].
     * Используется для recovery: HASHING → PENDING после краша приложения.
     */
    @Query("UPDATE media_files SET status = :targetStatus WHERE status = :sourceStatus")
    suspend fun resetStatus(sourceStatus: MediaFileStatus, targetStatus: MediaFileStatus)

    /**
     * Сбрасывает checksum, serverFileId и статус для конкретной записи, если изменились size или lastModified.
     * Вызывать ДО [updateLocalMetadata] — иначе новые значения уже в БД и сравнение не сработает.
     */
    // При изменении локального файла старый serverFileId больше не соответствует содержимому.
    // Изменившийся файл — новая попытка с чистого состояния, поэтому чистим и failure-метаданные (Stage 5K).
    @Query("""
        UPDATE media_files
        SET checksum = NULL,
            serverFileId = NULL,
            status = :status,
            failureReason = NULL,
            retryCount = 0,
            lastFailureAt = NULL
        WHERE mediaStoreId = :mediaStoreId
          AND (size != :size OR lastModified != :lastModified)
    """)
    suspend fun resetChecksumIfFileChanged(
        mediaStoreId: Long,
        size: Long,
        lastModified: Long,
        status: MediaFileStatus = MediaFileStatus.PENDING
    )

    @Query("SELECT MAX(createdAt) FROM media_files")
    suspend fun getLatestCreatedAt(): Long?

    /**
     * Snapshot записей с заданным статусом и непустым checksum.
     * Используется для batch pre-check: берём только готовые к запросу на сервер.
     */
    @Query("""
        SELECT * FROM media_files
        WHERE status = :status
          AND checksum IS NOT NULL
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getByStatusWithChecksumOnce(
        status: MediaFileStatus,
        limit: Int
    ): List<MediaFile>

    /**
     * Массовое обновление статуса по списку mediaStoreId.
     * Обновляем по mediaStoreId (а не по checksum) — безопаснее для будущих multi-folder сценариев.
     */
    @Query("""
        UPDATE media_files
        SET status = :status
        WHERE mediaStoreId IN (:mediaStoreIds)
    """)
    suspend fun updateStatusByMediaStoreIds(
        mediaStoreIds: List<Long>,
        status: MediaFileStatus
    )
}

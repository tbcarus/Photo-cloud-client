package ru.tbcarus.photo_cloud_client.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaFileDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(file: MediaFile)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(files: List<MediaFile>)

    // Большой индекс читается несколькими окнами CursorWindow, каждое дочитывается повторным запросом:
    // без общей транзакции удаление строк scan между окнами обрывает чтение (IllegalStateException).
    @Transaction
    @Query("SELECT * FROM media_files ORDER BY createdAt DESC")
    fun getAll(): Flow<List<MediaFile>>

    /**
     * Счётчики индекса одной агрегирующей выборкой (A7.1), без загрузки строк. Room Flow выдаёт новое значение
     * после каждого изменения таблицы. LOCAL_DELETED активным путём не записывается и в счётчики не входит,
     * поэтому total равен сумме групп.
     */
    @Query("""
        SELECT
            COUNT(*) AS total,
            COUNT(CASE WHEN status = 'SYNCED' THEN 1 END) AS synced,
            COUNT(CASE WHEN status IN ('PENDING', 'HASHING') THEN 1 END) AS preparing,
            COUNT(CASE WHEN status IN ('CHECKSUM_READY', 'PENDING_UPLOAD', 'UPLOADING') THEN 1 END) AS toUpload,
            COUNT(CASE WHEN status = 'FAILED' THEN 1 END) AS failed
        FROM media_files
        WHERE status != 'LOCAL_DELETED'
    """)
    fun observeCounters(): Flow<MediaFileCounters>

    @Query("SELECT * FROM media_files WHERE status = :status ORDER BY createdAt DESC")
    fun getByStatus(status: MediaFileStatus): Flow<List<MediaFile>>

    fun getPending(): Flow<List<MediaFile>> = getByStatus(MediaFileStatus.PENDING)

    /**
     * Условный переход статуса (A4.1): применяется, только если строка всё ещё в [expectedStatus]
     * и описывает ту же версию файла (checksum, size, lastModified), что была прочитана перед операцией.
     * Поздний результат hash/pre-check/upload не перезаписывает строку, которую scan уже сбросил или обновил.
     * @return 1 — переход применён, 0 — строка изменилась или удалена.
     */
    @Query("""
        UPDATE media_files
        SET status = :newStatus
        WHERE mediaStoreId = :mediaStoreId
          AND status = :expectedStatus
          AND checksum IS :checksum
          AND size = :size
          AND lastModified = :lastModified
    """)
    suspend fun updateStatusIfUnchanged(
        mediaStoreId: Long,
        expectedStatus: MediaFileStatus,
        newStatus: MediaFileStatus,
        checksum: String?,
        size: Long,
        lastModified: Long
    ): Int

    // Успешный checksum — стабильное состояние, чистим failure-метаданные (Stage 5K).
    // Применяется только к той же HASHING-строке: сброс scan во время чтения файла не перезаписывается.
    @Query("""
        UPDATE media_files
        SET checksum = :checksum,
            status = 'CHECKSUM_READY',
            failureReason = NULL,
            retryCount = 0,
            lastFailureAt = NULL
        WHERE mediaStoreId = :mediaStoreId
          AND status = 'HASHING'
          AND size = :size
          AND lastModified = :lastModified
    """)
    suspend fun updateChecksumIfUnchanged(
        mediaStoreId: Long,
        checksum: String,
        size: Long,
        lastModified: Long
    ): Int

    /**
     * Атомарный перевод записи в FAILED с причиной (Stage 5K) при тех же условиях,
     * что и [updateStatusIfUnchanged]. Инкремент retryCount делается в SQL, без read-modify-write в Kotlin.
     */
    @Query("""
        UPDATE media_files
        SET status = 'FAILED',
            failureReason = :reason,
            retryCount = retryCount + 1,
            lastFailureAt = :failureAt
        WHERE mediaStoreId = :mediaStoreId
          AND status = :expectedStatus
          AND checksum IS :checksum
          AND size = :size
          AND lastModified = :lastModified
    """)
    suspend fun markFailedIfUnchanged(
        mediaStoreId: Long,
        expectedStatus: MediaFileStatus,
        checksum: String?,
        size: Long,
        lastModified: Long,
        reason: FailureReason,
        failureAt: Long
    ): Int

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

    /**
     * Применяет к известной строке данные scan одним UPDATE: обновляет локальные сведения и,
     * если изменились size или lastModified, сбрасывает обработку — PENDING, без checksum,
     * serverFileId и failure-метаданных (Stage 5K). Выражения SET в SQLite вычисляются по прежним
     * значениям строки, поэтому параллельный hash не видит промежуточного состояния.
     * Неизменённая строка сохраняет статус и checksum.
     */
    // При изменении локального файла старый serverFileId больше не соответствует содержимому.
    @Query("""
        UPDATE media_files
        SET status = CASE WHEN size != :size OR lastModified != :lastModified
                          THEN 'PENDING' ELSE status END,
            checksum = CASE WHEN size != :size OR lastModified != :lastModified
                            THEN NULL ELSE checksum END,
            serverFileId = CASE WHEN size != :size OR lastModified != :lastModified
                                THEN NULL ELSE serverFileId END,
            failureReason = CASE WHEN size != :size OR lastModified != :lastModified
                                 THEN NULL ELSE failureReason END,
            retryCount = CASE WHEN size != :size OR lastModified != :lastModified
                              THEN 0 ELSE retryCount END,
            lastFailureAt = CASE WHEN size != :size OR lastModified != :lastModified
                                 THEN NULL ELSE lastFailureAt END,
            displayName = :displayName,
            relativePath = :relativePath,
            mimeType = :mimeType,
            size = :size,
            createdAt = :createdAt,
            lastModified = :lastModified,
            uri = :uri
        WHERE mediaStoreId = :mediaStoreId
    """)
    suspend fun applyScannedMetadata(
        mediaStoreId: Long,
        displayName: String,
        relativePath: String?,
        mimeType: String,
        size: Long,
        createdAt: Long,
        lastModified: Long,
        uri: String
    )

    /**
     * Применяет одну порцию снимка MediaStore одной транзакцией (A8.1): новые строки вставляются,
     * у известных обновляются сведения по правилам [applyScannedMetadata]. Каждая строка самодостаточна,
     * поэтому прерывание между порциями оставляет согласованный индекс, а следующий scan дописывает остальное.
     */
    @Transaction
    suspend fun applyScanBatch(files: List<MediaFile>) {
        insertAll(files)
        files.forEach { file ->
            applyScannedMetadata(
                mediaStoreId = file.mediaStoreId,
                displayName  = file.displayName,
                relativePath = file.relativePath,
                mimeType     = file.mimeType,
                size         = file.size,
                createdAt    = file.createdAt,
                lastModified = file.lastModified,
                uri          = file.uri
            )
        }
    }

    // Успешный upload → SYNCED, чистим failure-метаданные (Stage 5K).
    // Только для той же UPLOADING-версии: изменённая во время отправки фотография остаётся в работе Поставщика.
    @Query("""
        UPDATE media_files
        SET status = 'SYNCED',
            serverFileId = :serverFileId,
            failureReason = NULL,
            retryCount = 0,
            lastFailureAt = NULL
        WHERE mediaStoreId = :mediaStoreId
          AND status = 'UPLOADING'
          AND checksum IS :checksum
          AND size = :size
          AND lastModified = :lastModified
    """)
    suspend fun markUploadedIfUnchanged(
        mediaStoreId: Long,
        serverFileId: Long,
        checksum: String?,
        size: Long,
        lastModified: Long
    ): Int

    /**
     * Есть ли записи для Загрузчика: CHECKSUM_READY с checksum (pre-check), PENDING_UPLOAD (upload)
     * и UPLOADING (восстановление прерванной отправки).
     */
    @Query("""
        SELECT COUNT(*) FROM media_files
        WHERE (status = 'CHECKSUM_READY' AND checksum IS NOT NULL)
           OR status IN ('PENDING_UPLOAD', 'UPLOADING')
    """)
    suspend fun countReadyForUpload(): Int

    @Query("SELECT COUNT(*) FROM media_files WHERE mediaStoreId = :mediaStoreId")
    suspend fun exists(mediaStoreId: Long): Int

    @Query("SELECT mediaStoreId FROM media_files")
    suspend fun getAllMediaStoreIds(): List<Long>

    @Query("DELETE FROM media_files WHERE mediaStoreId IN (:ids)")
    suspend fun deleteByMediaStoreIds(ids: List<Long>)

    /**
     * Партия строк с заданным статусом в порядке FIFO по первичному ключу (A8.1): очереди хеширования
     * и upload. Выборка идёт по ключу
     * и останавливается на [limit], без сортировки всей таблицы; порядок устойчив между прогонами.
     */
    @Query("SELECT * FROM media_files WHERE status = :status ORDER BY mediaStoreId ASC LIMIT :limit")
    suspend fun getByStatusInIdOrder(status: MediaFileStatus, limit: Int): List<MediaFile>

    @Query("SELECT COUNT(*) FROM media_files WHERE status = :status")
    suspend fun countByStatus(status: MediaFileStatus): Int

    /**
     * Массово переводит все записи из [sourceStatus] в [targetStatus].
     * Используется для recovery: HASHING → PENDING после краша приложения.
     */
    @Query("UPDATE media_files SET status = :targetStatus WHERE status = :sourceStatus")
    suspend fun resetStatus(sourceStatus: MediaFileStatus, targetStatus: MediaFileStatus)

    @Query("SELECT MAX(createdAt) FROM media_files")
    suspend fun getLatestCreatedAt(): Long?

    /**
     * Snapshot записей с заданным статусом и непустым checksum, FIFO по mediaStoreId (A8.2).
     * Используется для batch pre-check: берём только готовые к запросу на сервер.
     */
    @Query("""
        SELECT * FROM media_files
        WHERE status = :status
          AND checksum IS NOT NULL
        ORDER BY mediaStoreId ASC
        LIMIT :limit
    """)
    suspend fun getByStatusWithChecksumOnce(
        status: MediaFileStatus,
        limit: Int
    ): List<MediaFile>
}

/** [MediaFileDao.updateStatusIfUnchanged] для версии файла из прочитанного снимка [file]. */
suspend fun MediaFileDao.updateStatusIfUnchanged(
    file: MediaFile,
    expectedStatus: MediaFileStatus,
    newStatus: MediaFileStatus
): Int = updateStatusIfUnchanged(
    file.mediaStoreId, expectedStatus, newStatus, file.checksum, file.size, file.lastModified
)

/** [MediaFileDao.markFailedIfUnchanged] для версии файла из прочитанного снимка [file]. */
suspend fun MediaFileDao.markFailedIfUnchanged(
    file: MediaFile,
    expectedStatus: MediaFileStatus,
    reason: FailureReason
): Int = markFailedIfUnchanged(
    file.mediaStoreId, expectedStatus, file.checksum, file.size, file.lastModified,
    reason, System.currentTimeMillis()
)

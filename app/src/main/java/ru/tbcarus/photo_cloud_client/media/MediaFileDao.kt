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
     * Переход в SYNCED завершает серию восстановлений после несовпадения checksum (Q-02).
     * @return 1 — переход применён, 0 — строка изменилась или удалена.
     */
    @Query("""
        UPDATE media_files
        SET status = :newStatus,
            uploadMismatchCount = CASE WHEN :newStatus = 'SYNCED' THEN 0 ELSE uploadMismatchCount END
        WHERE uri = :uri
          AND status = :expectedStatus
          AND checksum IS :checksum
          AND size = :size
          AND lastModified = :lastModified
    """)
    suspend fun updateStatusIfUnchanged(
        uri: String,
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
        WHERE uri = :uri
          AND status = 'HASHING'
          AND size = :size
          AND lastModified = :lastModified
    """)
    suspend fun updateChecksumIfUnchanged(
        uri: String,
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
        WHERE uri = :uri
          AND status = :expectedStatus
          AND checksum IS :checksum
          AND size = :size
          AND lastModified = :lastModified
    """)
    suspend fun markFailedIfUnchanged(
        uri: String,
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
        WHERE uri = :uri
    """)
    suspend fun resetFailedForRetry(uri: String)

    /**
     * Ручной повтор по действию пользователя: все FAILED одним UPDATE возвращаются в начало обработки —
     * PENDING без checksum и serverFileId: файл будет заново прочитан, прежний checksum его актуальность не подтверждает.
     * Только здесь, кроме успешного checksum, обнуляется retryCount: лимит авто-повтора снова отсчитывается с нуля.
     * Так же с нуля начинается серия восстановлений после несовпадения checksum upload.
     * Условие status = 'FAILED' не трогает строки, которые обработка или scan уже перевели в другое состояние.
     * @return число строк, возвращённых в очередь.
     */
    @Query("""
        UPDATE media_files
        SET status = 'PENDING',
            checksum = NULL,
            serverFileId = NULL,
            failureReason = NULL,
            retryCount = 0,
            lastFailureAt = NULL,
            uploadMismatchCount = 0
        WHERE status = 'FAILED'
    """)
    suspend fun resetAllFailedForManualRetry(): Int

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
            uploadMismatchCount = CASE WHEN size != :size OR lastModified != :lastModified
                                       THEN 0 ELSE uploadMismatchCount END,
            displayName = :displayName,
            relativePath = :relativePath,
            mimeType = :mimeType,
            size = :size,
            createdAt = :createdAt,
            lastModified = :lastModified
        WHERE uri = :uri
    """)
    suspend fun applyScannedMetadata(
        uri: String,
        displayName: String,
        relativePath: String?,
        mimeType: String,
        size: Long,
        createdAt: Long,
        lastModified: Long
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
                uri          = file.uri,
                displayName  = file.displayName,
                relativePath = file.relativePath,
                mimeType     = file.mimeType,
                size         = file.size,
                createdAt    = file.createdAt,
                lastModified = file.lastModified
            )
        }
    }

    // Успешный upload → SYNCED, чистим failure-метаданные (Stage 5K) и счётчик несовпадений (Q-02).
    // Только для той же UPLOADING-версии: изменённая во время отправки фотография остаётся в работе Поставщика.
    @Query("""
        UPDATE media_files
        SET status = 'SYNCED',
            serverFileId = :serverFileId,
            failureReason = NULL,
            retryCount = 0,
            lastFailureAt = NULL,
            uploadMismatchCount = 0
        WHERE uri = :uri
          AND status = 'UPLOADING'
          AND checksum IS :checksum
          AND size = :size
          AND lastModified = :lastModified
    """)
    suspend fun markUploadedIfUnchanged(
        uri: String,
        serverFileId: Long,
        checksum: String?,
        size: Long,
        lastModified: Long
    ): Int

    /**
     * Server принял не те байты, которым принадлежит локальный checksum (Q-02). Одним UPDATE той же UPLOADING-версии
     * (включая прочитанный счётчик) checksum и serverFileId очищаются, счётчик растёт. Пока восстановлений меньше
     * [maxRecoveries], строка возвращается в PENDING к Поставщику; дальше — FAILED CHANGED_DURING_UPLOAD без авто-повтора.
     * Выражения SET вычисляются по прежнему значению счётчика.
     * @return 1 — переход применён, 0 — строка изменилась или удалена.
     */
    @Query("""
        UPDATE media_files
        SET status = CASE WHEN uploadMismatchCount < :maxRecoveries THEN 'PENDING' ELSE 'FAILED' END,
            checksum = NULL,
            serverFileId = NULL,
            failureReason = CASE WHEN uploadMismatchCount < :maxRecoveries THEN NULL ELSE 'CHANGED_DURING_UPLOAD' END,
            retryCount = CASE WHEN uploadMismatchCount < :maxRecoveries THEN retryCount ELSE retryCount + 1 END,
            lastFailureAt = CASE WHEN uploadMismatchCount < :maxRecoveries THEN NULL ELSE :failureAt END,
            uploadMismatchCount = uploadMismatchCount + 1
        WHERE uri = :uri
          AND status = 'UPLOADING'
          AND checksum IS :checksum
          AND size = :size
          AND lastModified = :lastModified
          AND uploadMismatchCount = :uploadMismatchCount
    """)
    suspend fun applyUploadMismatchIfUnchanged(
        uri: String,
        checksum: String?,
        size: Long,
        lastModified: Long,
        uploadMismatchCount: Int,
        maxRecoveries: Int,
        failureAt: Long
    ): Int

    @Query("""
        UPDATE media_files SET status = 'PENDING', checksum = NULL, serverFileId = NULL
        WHERE uri = :uri AND status = 'UPLOADING'
          AND checksum IS :checksum AND size = :size AND lastModified = :lastModified
    """)
    suspend fun resetChangedSource(uri: String, checksum: String?, size: Long, lastModified: Long): Int

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

    @Query("SELECT * FROM media_files WHERE uri = :uri")
    suspend fun getByUri(uri: String): MediaFile?

    /**
     * Повтор одной FAILED-записи, отобранной пользователем после ручной сверки (Q-01), — как
     * [resetAllFailedForManualRetry], но только для той же версии файла, что была проверена.
     */
    @Query("""
        UPDATE media_files
        SET status = 'PENDING',
            checksum = NULL,
            serverFileId = NULL,
            failureReason = NULL,
            retryCount = 0,
            lastFailureAt = NULL,
            uploadMismatchCount = 0
        WHERE uri = :uri
          AND status = 'FAILED'
          AND size = :size
          AND lastModified = :lastModified
    """)
    suspend fun resetFailedForManualRetryIfUnchanged(uri: String, size: Long, lastModified: Long): Int

    @Query("SELECT COUNT(*) FROM media_files WHERE uri = :uri")
    suspend fun exists(uri: String): Int

    @Query("SELECT uri FROM media_files WHERE collection = :collection")
    suspend fun getAllUris(collection: MediaCollection): List<String>

    @Query("DELETE FROM media_files WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    /**
     * Партия строк с заданным статусом в порядке FIFO по первичному ключу (A8.1): очереди хеширования
     * и upload. Выборка идёт по ключу
     * и останавливается на [limit], без сортировки всей таблицы; порядок устойчив между прогонами.
     */
    @Query("SELECT * FROM media_files WHERE status = :status ORDER BY mediaStoreId ASC, uri ASC LIMIT :limit")
    suspend fun getByStatusInIdOrder(status: MediaFileStatus, limit: Int): List<MediaFile>

    @Query("""
        SELECT * FROM media_files WHERE status = :status
          AND ((collection = 'IMAGE' AND :imagesAllowed) OR (collection = 'VIDEO' AND :videosAllowed))
        ORDER BY mediaStoreId ASC, uri ASC LIMIT :limit
    """)
    suspend fun getReadableByStatusInIdOrder(status: MediaFileStatus, limit: Int,
                                             imagesAllowed: Boolean, videosAllowed: Boolean): List<MediaFile>

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
        ORDER BY mediaStoreId ASC, uri ASC
        LIMIT :limit
    """)
    suspend fun getByStatusWithChecksumOnce(
        status: MediaFileStatus,
        limit: Int
    ): List<MediaFile>

    @Query("""
        SELECT * FROM media_files
        WHERE status = :status AND checksum IS NOT NULL
          AND ((collection = 'IMAGE' AND :imagesAllowed) OR (collection = 'VIDEO' AND :videosAllowed))
        ORDER BY mediaStoreId ASC, uri ASC LIMIT :limit
    """)
    suspend fun getReadableByStatusWithChecksumOnce(
        status: MediaFileStatus, limit: Int, imagesAllowed: Boolean, videosAllowed: Boolean
    ): List<MediaFile>
}

/** [MediaFileDao.updateStatusIfUnchanged] для версии файла из прочитанного снимка [file]. */
suspend fun MediaFileDao.updateStatusIfUnchanged(
    file: MediaFile,
    expectedStatus: MediaFileStatus,
    newStatus: MediaFileStatus
): Int = updateStatusIfUnchanged(
    file.uri, expectedStatus, newStatus, file.checksum, file.size, file.lastModified
)

/** [MediaFileDao.markFailedIfUnchanged] для версии файла из прочитанного снимка [file]. */
suspend fun MediaFileDao.markFailedIfUnchanged(
    file: MediaFile,
    expectedStatus: MediaFileStatus,
    reason: FailureReason
): Int = markFailedIfUnchanged(
    file.uri, expectedStatus, file.checksum, file.size, file.lastModified,
    reason, System.currentTimeMillis()
)

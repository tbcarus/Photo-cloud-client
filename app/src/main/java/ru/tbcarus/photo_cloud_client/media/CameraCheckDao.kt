package ru.tbcarus.photo_cloud_client.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val CAMERA_CHECK_COUNTS = """
    SELECT
        COUNT(*) AS total,
        COUNT(CASE WHEN checksum IS NOT NULL OR result IS NOT NULL THEN 1 END) AS prepared,
        COUNT(CASE WHEN checksum IS NOT NULL THEN 1 END) AS withChecksum,
        COUNT(CASE WHEN checksum IS NOT NULL AND result IS NOT NULL THEN 1 END) AS answered,
        COUNT(CASE WHEN result = 'EXISTS' THEN 1 END) AS confirmed,
        COUNT(CASE WHEN result = 'MISSING' THEN 1 END) AS missing,
        COUNT(CASE WHEN result = 'UNVERIFIABLE' THEN 1 END) AS unverifiable
    FROM camera_check_items
"""

/** Хранение ручной сверки Camera (Q-01). Согласованность нескольких операций обеспечивает [CameraCheckRepository]. */
@Dao
interface CameraCheckDao {

    @Query("SELECT * FROM camera_check WHERE id = 1")
    suspend fun get(): CameraCheck?

    @Query("SELECT * FROM camera_check WHERE id = 1")
    fun observe(): Flow<CameraCheck?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(check: CameraCheck)

    @Query("DELETE FROM camera_check")
    suspend fun deleteCheck()

    @Query("DELETE FROM camera_check_items")
    suspend fun deleteItems()

    /** Пауза Camera upload: проверка выполняется, остановлена ошибкой или ждёт решения. */
    @Query("SELECT COUNT(*) FROM camera_check WHERE state != 'FINISHED'")
    suspend fun countPausing(): Int

    /** Состояние выполняющейся проверки [runId]; итог и ожидание решения не меняются. */
    @Query("""
        UPDATE camera_check SET state = :state, error = :error
        WHERE runId = :runId AND state IN ('RUNNING', 'ERROR')
    """)
    suspend fun setActiveState(runId: String, state: CameraCheckState, error: CameraCheckError?): Int

    @Query("""
        UPDATE camera_check SET stage = :stage, state = 'RUNNING', error = NULL
        WHERE runId = :runId AND stage = :expected AND state IN ('RUNNING', 'ERROR')
    """)
    suspend fun advanceStage(runId: String, expected: CameraCheckStage, stage: CameraCheckStage): Int

    @Query("UPDATE camera_check SET cameraFolderId = :folderId WHERE runId = :runId AND cameraFolderId IS NULL")
    suspend fun setCameraFolder(runId: String, folderId: Long): Int

    @Query("UPDATE camera_check SET deferred = :deferred WHERE state = 'AWAITING_DECISION'")
    suspend fun setDeferred(deferred: Boolean): Int

    /**
     * Набор проверки — все строки индекса Camera после полного scan. Checksum переносится из состояний, где он
     * относится к текущей версии файла; PENDING и HASHING получат его от Поставщика.
     */
    @Query("""
        INSERT INTO camera_check_items (uri, mediaStoreId, size, lastModified, checksum, result)
        SELECT uri, mediaStoreId, size, lastModified,
               CASE WHEN status IN ('CHECKSUM_READY', 'PENDING_UPLOAD', 'UPLOADING', 'SYNCED', 'FAILED')
                    THEN checksum END,
               NULL
        FROM media_files
        WHERE status != 'LOCAL_DELETED'
    """)
    suspend fun snapshotItems()

    /** Строки без checksum после [after] в порядке ключа: проход не возвращается к ожидающим Поставщика. */
    @Query("""
        SELECT * FROM camera_check_items
        WHERE checksum IS NULL AND result IS NULL
          AND (mediaStoreId > :afterId OR (mediaStoreId = :afterId AND uri > :afterUri))
        ORDER BY mediaStoreId ASC, uri ASC LIMIT :limit
    """)
    suspend fun itemsWithoutChecksum(afterId: Long, afterUri: String, limit: Int): List<CameraCheckItem>

    @Query("SELECT COUNT(*) FROM camera_check_items WHERE checksum IS NULL AND result IS NULL")
    suspend fun countWithoutChecksum(): Int

    @Query("""
        UPDATE camera_check_items SET checksum = :checksum, size = :size, lastModified = :lastModified
        WHERE uri = :uri AND checksum IS NULL AND result IS NULL
    """)
    suspend fun setItemChecksum(uri: String, checksum: String, size: Long, lastModified: Long): Int

    /** Результат записывается один раз: повтор партии строку с результатом не меняет. */
    @Query("UPDATE camera_check_items SET result = :result WHERE uri = :uri AND result IS NULL")
    suspend fun setItemResult(uri: String, result: CameraCheckResult): Int

    @Query("DELETE FROM camera_check_items WHERE uri = :uri")
    suspend fun deleteItem(uri: String)

    @Query("""
        SELECT * FROM camera_check_items
        WHERE checksum IS NOT NULL AND result IS NULL
        ORDER BY mediaStoreId ASC, uri ASC LIMIT :limit
    """)
    suspend fun itemsAwaitingServer(limit: Int): List<CameraCheckItem>

    @Query("SELECT COUNT(*) FROM camera_check_items WHERE checksum IS NOT NULL AND result IS NULL")
    suspend fun countAwaitingServer(): Int

    @Query("SELECT * FROM camera_check_items WHERE result = 'MISSING' ORDER BY mediaStoreId ASC, uri ASC")
    suspend fun missingItems(): List<CameraCheckItem>

    @Query(CAMERA_CHECK_COUNTS)
    suspend fun counts(): CameraCheckCounts

    @Query(CAMERA_CHECK_COUNTS)
    fun observeCounts(): Flow<CameraCheckCounts>
}

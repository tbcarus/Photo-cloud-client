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

    @Query("""
        UPDATE media_files
        SET checksum = :checksum,
            status = :status
        WHERE mediaStoreId = :mediaStoreId
    """)
    suspend fun updateChecksum(
        mediaStoreId: Long,
        checksum: String,
        status: MediaFileStatus = MediaFileStatus.CHECKSUM_READY
    )

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

    @Query("UPDATE media_files SET status = 'SYNCED', serverFileId = :serverFileId WHERE mediaStoreId = :mediaStoreId")
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
     * Сбрасывает checksum и статус для конкретной записи, если изменились size или lastModified.
     * Вызывать ДО [updateLocalMetadata] — иначе новые значения уже в БД и сравнение не сработает.
     */
    @Query("""
        UPDATE media_files
        SET checksum = NULL,
            status = :status
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
}

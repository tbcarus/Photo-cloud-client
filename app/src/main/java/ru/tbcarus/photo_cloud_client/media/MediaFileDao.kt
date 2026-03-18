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

    @Query("UPDATE media_files SET status = :status WHERE mediaStoreId = :mediaStoreId")
    suspend fun updateStatus(mediaStoreId: Long, status: MediaFileStatus)

    @Query("SELECT * FROM media_files WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun getPending(): Flow<List<MediaFile>>

    @Query("UPDATE media_files SET checksum = :checksum WHERE mediaStoreId = :mediaStoreId")
    suspend fun updateChecksum(mediaStoreId: Long, checksum: String)

    @Query("UPDATE media_files SET status = 'UPLOADED', serverFileId = :serverFileId WHERE mediaStoreId = :mediaStoreId")
    suspend fun markUploaded(mediaStoreId: Long, serverFileId: Long)

    @Query("SELECT COUNT(*) FROM media_files WHERE mediaStoreId = :mediaStoreId")
    suspend fun exists(mediaStoreId: Long): Int

    @Query("SELECT MAX(createdAt) FROM media_files")
    suspend fun getLatestCreatedAt(): Long?
}

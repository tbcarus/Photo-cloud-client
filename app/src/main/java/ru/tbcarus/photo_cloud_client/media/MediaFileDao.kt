package ru.tbcarus.photo_cloud_client.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaFileDao {

    // Вставка новых файлов; если файл уже есть (по mediaStoreId) — игнорируем
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(files: List<MediaFile>)

    // Все файлы, отсортированные по дате съёмки (новые сверху)
    @Query("SELECT * FROM media_files ORDER BY createdAt DESC")
    fun getAll(): Flow<List<MediaFile>>

    // Файлы по статусу
    @Query("SELECT * FROM media_files WHERE status = :status ORDER BY createdAt DESC")
    fun getByStatus(status: MediaFileStatus): Flow<List<MediaFile>>

    // Только ожидающие загрузки
    @Query("SELECT * FROM media_files WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun getPending(): Flow<List<MediaFile>>

    // Обновить checksum
    @Query("UPDATE media_files SET checksum = :checksum WHERE mediaStoreId = :mediaStoreId")
    suspend fun updateChecksum(mediaStoreId: Long, checksum: String)

    // Отметить файл как загруженный и сохранить серверный ID
    @Query("UPDATE media_files SET status = 'UPLOADED', serverFileId = :serverFileId WHERE mediaStoreId = :mediaStoreId")
    suspend fun markUploaded(mediaStoreId: Long, serverFileId: Long)

    // Проверить, есть ли файл уже в БД
    @Query("SELECT COUNT(*) FROM media_files WHERE mediaStoreId = :mediaStoreId")
    suspend fun exists(mediaStoreId: Long): Int

    // Максимальный DATE_ADDED среди уже сохранённых файлов — для дельта-сканирования
    @Query("SELECT MAX(createdAt) FROM media_files")
    suspend fun getLatestCreatedAt(): Long?
}

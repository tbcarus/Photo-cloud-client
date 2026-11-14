package ru.tbcarus.photo_cloud_client.transfer

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Очередь ручных файлов; серверная сессия большого файла хранится в upload_sessions по тому же UUID. */
@Entity(tableName = "manual_upload_jobs", indices = [Index(value = ["serverUrl", "accountId", "status", "createdAt"])])
data class ManualUploadJob(
    @PrimaryKey val id: String,
    val serverUrl: String,
    val accountId: String,
    val uri: String,
    val name: String,
    val size: Long?,
    val mimeType: String?,
    val lastModified: Long?,
    val folderId: Long,
    val isRoot: Boolean,
    val checksum: String? = null,
    val sessionRequestId: String? = null,
    val confirmedOffset: Long = 0,
    val status: String = QUEUED,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val QUEUED = "QUEUED"
        const val RUNNING = "RUNNING"
        const val VERIFYING = "VERIFYING"
        const val COMPLETED = "COMPLETED"
        const val FAILED = "FAILED"
        const val CANCEL_PENDING = "CANCEL_PENDING"
        const val CANCELLED = "CANCELLED"
        const val UNCERTAIN = "UNCERTAIN"
        const val RETRY_PENDING = "RETRY_PENDING"
    }
}

@Dao
interface ManualUploadDao {
    @Insert
    suspend fun insert(jobs: List<ManualUploadJob>)

    @Query("SELECT * FROM manual_upload_jobs ORDER BY createdAt, id")
    fun observeAll(): Flow<List<ManualUploadJob>>

    @Query("SELECT * FROM manual_upload_jobs WHERE serverUrl = :serverUrl AND accountId = :accountId AND status IN ('QUEUED','RUNNING','VERIFYING','UNCERTAIN','CANCEL_PENDING','RETRY_PENDING') ORDER BY createdAt, id LIMIT 1")
    suspend fun next(serverUrl: String, accountId: String): ManualUploadJob?

    @Query("SELECT * FROM manual_upload_jobs WHERE id = :id")
    suspend fun get(id: String): ManualUploadJob?

    @Query("SELECT MAX(createdAt) FROM manual_upload_jobs")
    suspend fun latestCreatedAt(): Long?

    @Query("SELECT COUNT(*) FROM manual_upload_jobs WHERE uri = :uri")
    suspend fun countUri(uri: String): Int

    @Query("DELETE FROM manual_upload_jobs WHERE id = :id AND status IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun dismiss(id: String): Int

    @Query("UPDATE manual_upload_jobs SET status = :status, error = :error WHERE id = :id AND status NOT IN ('COMPLETED','CANCELLED')")
    suspend fun setStatus(id: String, status: String, error: String? = null): Int

    @Query("UPDATE manual_upload_jobs SET status = 'VERIFYING' WHERE id = :id AND status IN ('QUEUED','RUNNING','VERIFYING')")
    suspend fun markVerifying(id: String): Int

    @Query("UPDATE manual_upload_jobs SET status = 'RUNNING' WHERE id = :id AND status = 'QUEUED'")
    suspend fun markRunning(id: String): Int

    @Query("UPDATE manual_upload_jobs SET status = 'QUEUED' WHERE id = :id AND status = 'VERIFYING'")
    suspend fun resetVerifying(id: String): Int

    @Query("UPDATE manual_upload_jobs SET status = 'QUEUED' WHERE id = :id AND status IN ('RUNNING','UNCERTAIN')")
    suspend fun resetForRetry(id: String): Int

    @Query("UPDATE manual_upload_jobs SET status = 'UNCERTAIN' WHERE id = :id AND status = 'RUNNING'")
    suspend fun markUncertain(id: String): Int

    @Query("UPDATE manual_upload_jobs SET status = 'FAILED', error = :reason WHERE id = :id AND status NOT IN ('CANCEL_PENDING','COMPLETED','CANCELLED')")
    suspend fun failIfActive(id: String, reason: String): Int

    @Query("UPDATE manual_upload_jobs SET checksum = :checksum, status = 'QUEUED' WHERE id = :id AND status = 'VERIFYING'")
    suspend fun setChecksum(id: String, checksum: String): Int

    @Query("UPDATE manual_upload_jobs SET sessionRequestId = :requestId, confirmedOffset = 0 WHERE id = :id AND status = 'RUNNING'")
    suspend fun setSessionRequestId(id: String, requestId: String): Int

    @Query("UPDATE manual_upload_jobs SET sessionRequestId = NULL, confirmedOffset = 0 WHERE id = :id AND status = 'RUNNING'")
    suspend fun clearSessionRequestId(id: String): Int

    @Query("UPDATE manual_upload_jobs SET confirmedOffset = :offset WHERE id = :id AND status IN ('RUNNING','CANCEL_PENDING')")
    suspend fun setOffset(id: String, offset: Long)

    @Query("UPDATE manual_upload_jobs SET status = 'CANCEL_PENDING' WHERE id = :id AND status IN ('QUEUED','RUNNING','VERIFYING','UNCERTAIN','FAILED','RETRY_PENDING')")
    suspend fun requestCancel(id: String): Int

    @Query("UPDATE manual_upload_jobs SET status = 'CANCELLED' WHERE id = :id AND sessionRequestId IS NULL AND status IN ('QUEUED','VERIFYING','FAILED')")
    suspend fun cancelUnstarted(id: String): Int

    @Query("UPDATE manual_upload_jobs SET status = 'RETRY_PENDING', error = NULL WHERE id = :id AND status = 'FAILED'")
    suspend fun retry(id: String): Int

    @Query("UPDATE manual_upload_jobs SET status = 'QUEUED', name = :name, size = :size, mimeType = :mimeType, lastModified = :lastModified, checksum = NULL, sessionRequestId = NULL, confirmedOffset = 0, error = NULL WHERE id = :id AND status = 'RETRY_PENDING'")
    suspend fun retryReady(id: String, name: String, size: Long?, mimeType: String?, lastModified: Long?): Int
}

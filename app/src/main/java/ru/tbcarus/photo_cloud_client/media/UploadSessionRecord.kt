package ru.tbcarus.photo_cloud_client.media

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Независима от media_files: исчезновение строки Camera не теряет право отменить сессию Server. */
@Entity(
    tableName = "upload_sessions",
    indices = [Index(value = ["serverUrl", "accountId", "sourceKind", "sourceId"])]
)
data class UploadSessionRecord(
    @PrimaryKey val clientRequestId: String,
    val serverUrl: String,
    val accountId: String,
    val sourceKind: String,
    val sourceId: String,
    val uri: String,
    val folderId: Long,
    val originalFilename: String,
    val size: Long,
    val lastModified: Long,
    val originalMode: Boolean,
    val checksum: String,
    val serverSessionId: String? = null,
    val confirmedOffset: Long = 0,
    val cancelPending: Boolean = false,
    val collection: MediaCollection = MediaCollection.IMAGE
)

@Dao
interface UploadSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: UploadSessionRecord)

    @Query("SELECT * FROM upload_sessions WHERE serverUrl = :serverUrl AND accountId = :accountId AND sourceKind = :kind AND sourceId = :sourceId ORDER BY rowid DESC")
    suspend fun forSource(serverUrl: String, accountId: String, kind: String, sourceId: String): List<UploadSessionRecord>

    @Query("SELECT * FROM upload_sessions WHERE serverUrl = :serverUrl AND accountId = :accountId ORDER BY rowid ASC")
    suspend fun forAccount(serverUrl: String, accountId: String): List<UploadSessionRecord>

    @Query("UPDATE upload_sessions SET serverSessionId = :sessionId, confirmedOffset = :offset WHERE clientRequestId = :requestId AND cancelPending = 0")
    suspend fun setServerState(requestId: String, sessionId: String, offset: Long): Int

    @Query("UPDATE upload_sessions SET confirmedOffset = :offset WHERE clientRequestId = :requestId AND cancelPending = 0")
    suspend fun setOffset(requestId: String, offset: Long): Int

    @Query("UPDATE upload_sessions SET cancelPending = 1 WHERE clientRequestId = :requestId")
    suspend fun requestCancel(requestId: String)

    @Query("DELETE FROM upload_sessions WHERE clientRequestId = :requestId")
    suspend fun delete(requestId: String)
}

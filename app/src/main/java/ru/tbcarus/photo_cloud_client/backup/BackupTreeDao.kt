package ru.tbcarus.photo_cloud_client.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Сводка файлов одного источника для Settings. */
data class SourceFileCounters(
    val sourceId: Long,
    val synced: Int,
    val pending: Int,
    val changed: Int,
    val failed: Int,
    val skipped: Int
)

@Dao
interface BackupTreeDao {

    // --- Папки ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: BackupFolder): Long

    @Update
    suspend fun updateFolder(folder: BackupFolder)

    @Query("SELECT * FROM backup_folders WHERE id = :id")
    suspend fun folder(id: Long): BackupFolder?

    @Query("SELECT * FROM backup_folders WHERE sourceId = :sourceId AND relativePath = :relativePath")
    suspend fun folderByPath(sourceId: Long, relativePath: String): BackupFolder?

    @Query("SELECT * FROM backup_folders WHERE parentId = :parentId")
    suspend fun childFolders(parentId: Long): List<BackupFolder>

    /** Папки текущего обхода, ещё не прочитанные: FIFO по ID, родитель всегда раньше вложенных. */
    @Query("""
        SELECT * FROM backup_folders
        WHERE sourceId = :sourceId AND seenGeneration = :generation AND listedGeneration < :generation
        ORDER BY id LIMIT :limit
    """)
    suspend fun foldersToList(sourceId: Long, generation: Int, limit: Int): List<BackupFolder>

    @Query("UPDATE backup_folders SET listedGeneration = :generation WHERE id = :id")
    suspend fun markListed(id: Long, generation: Int)

    @Query("UPDATE backup_folders SET seenGeneration = :generation, problem = NULL WHERE id = :id")
    suspend fun markSeen(id: Long, generation: Int)

    /** Папки, найденные текущим обходом и ещё не созданные на Server, в порядке обнаружения. */
    @Query("""
        SELECT * FROM backup_folders
        WHERE sourceId = :sourceId AND seenGeneration = :generation AND serverFolderId IS NULL AND problem IS NULL
        ORDER BY id LIMIT :limit
    """)
    suspend fun foldersToCreate(sourceId: Long, generation: Int, limit: Int): List<BackupFolder>

    @Query("UPDATE backup_folders SET serverFolderId = :serverFolderId, problem = NULL WHERE id = :id")
    suspend fun setServerFolder(id: Long, serverFolderId: Long)

    @Query("UPDATE backup_folders SET serverFolderId = NULL WHERE id = :id AND parentId IS NOT NULL")
    suspend fun forgetServerFolder(id: Long)

    @Query("UPDATE backup_folders SET problem = :problem WHERE id = :id")
    suspend fun setFolderProblem(id: Long, problem: String)

    // --- Файлы ---

    @Insert
    suspend fun insertFile(file: BackupFile): Long

    @Update
    suspend fun updateFile(file: BackupFile)

    @Query("SELECT * FROM backup_files WHERE id = :id")
    suspend fun file(id: Long): BackupFile?

    @Query("SELECT * FROM backup_files WHERE folderId = :folderId")
    suspend fun filesIn(folderId: Long): List<BackupFile>

    @Query("SELECT * FROM backup_files WHERE sourceId = :sourceId ORDER BY id")
    suspend fun files(sourceId: Long): List<BackupFile>

    /**
     * Очередь: новые и изменённые файлы и незавершённые архивирования, FIFO по ID. Исчезнувшие файлы не
     * отправляются, но начатое переименование доводится до определённого результата.
     */
    @Query("""
        SELECT * FROM backup_files
        WHERE sourceId = :sourceId AND status IN ('PENDING', 'CHANGED', 'ARCHIVING', 'ARCHIVED')
          AND (missingSince IS NULL OR status = 'ARCHIVING')
        ORDER BY id LIMIT :limit
    """)
    suspend fun filesToProcess(sourceId: Long, limit: Int): List<BackupFile>

    /** Прерванные процессом или отменой отправки возвращаются в очередь. */
    @Query("UPDATE backup_files SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun resetUploading(): Int

    /** Завершение полного обхода: не найденные в нём файлы помечаются, записи и серверные копии остаются. */
    @Query("""
        UPDATE backup_files SET missingSince = :now
        WHERE sourceId = :sourceId AND seenGeneration < :generation AND missingSince IS NULL
    """)
    suspend fun markMissing(sourceId: Long, generation: Int, now: Long): Int

    @Query("""
        SELECT COUNT(*) FROM backup_files
        WHERE sourceId = :sourceId AND status IN ('PENDING', 'CHANGED', 'ARCHIVING', 'ARCHIVED')
          AND (missingSince IS NULL OR status = 'ARCHIVING')
    """)
    suspend fun countToProcess(sourceId: Long): Int

    @Query("""
        SELECT COUNT(*) FROM backup_folders
        WHERE sourceId = :sourceId AND seenGeneration = :generation
          AND (listedGeneration < :generation OR (serverFolderId IS NULL AND problem IS NULL))
    """)
    suspend fun countFolderWork(sourceId: Long, generation: Int): Int

    @Query("""
        SELECT sourceId,
            COUNT(CASE WHEN status = 'SYNCED' THEN 1 END) AS synced,
            COUNT(CASE WHEN status IN ('PENDING', 'UPLOADING') THEN 1 END) AS pending,
            COUNT(CASE WHEN status IN ('CHANGED', 'ARCHIVING', 'ARCHIVED') THEN 1 END) AS changed,
            COUNT(CASE WHEN status IN ('FAILED', 'CONFLICT') THEN 1 END) AS failed,
            COUNT(CASE WHEN status = 'SKIPPED' THEN 1 END) AS skipped
        FROM backup_files
        WHERE missingSince IS NULL
        GROUP BY sourceId
    """)
    fun observeCounters(): Flow<List<SourceFileCounters>>

    /** Одна прочитанная папка — одна транзакция: прерывание не оставляет её наполовину учтённой. */
    @Transaction
    suspend fun applyListing(
        listed: BackupFolder,
        generation: Int,
        newFolders: List<BackupFolder>,
        seenFolders: List<BackupFolder>,
        newFiles: List<BackupFile>,
        changedFiles: List<BackupFile>
    ) {
        newFolders.forEach { insertFolder(it) }
        seenFolders.forEach { updateFolder(it) }
        newFiles.forEach { insertFile(it) }
        changedFiles.forEach { updateFile(it) }
        markListed(listed.id, generation)
    }
}

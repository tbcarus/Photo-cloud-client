package ru.tbcarus.photo_cloud_client.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupSourceDao {

    @Insert
    suspend fun insert(source: BackupSource): Long

    @Query("SELECT * FROM backup_sources WHERE id = :id")
    suspend fun get(id: Long): BackupSource?

    /** Источники одного Server и аккаунта: чужие записи не показываются и не регистрируются. */
    @Query("SELECT * FROM backup_sources WHERE serverUrl = :serverUrl AND accountId = :accountId ORDER BY createdAt, id")
    fun observe(serverUrl: String, accountId: String): Flow<List<BackupSource>>

    @Query("SELECT * FROM backup_sources WHERE serverUrl = :serverUrl AND accountId = :accountId ORDER BY createdAt, id")
    suspend fun list(serverUrl: String, accountId: String): List<BackupSource>

    @Query("""
        SELECT COUNT(*) FROM backup_sources
        WHERE serverUrl = :serverUrl AND accountId = :accountId AND treeUri = :treeUri
    """)
    suspend fun countWithTree(serverUrl: String, accountId: String, treeUri: String): Int

    /** Имя меняется только после окончательного отказа Server: при неизвестном исходе оно должно остаться прежним. */
    @Query("""
        UPDATE backup_sources SET serverName = :serverName
        WHERE id = :id AND registrationState = 'REGISTRATION_FAILED'
    """)
    suspend fun renameFailed(id: Long, serverName: String): Int

    @Query("""
        UPDATE backup_sources
        SET registrationState = 'ACTIVE', serverRootFolderId = :serverRootFolderId, registrationError = NULL
        WHERE id = :id AND registrationState IN ('PENDING_REGISTRATION', 'REGISTRATION_FAILED')
    """)
    suspend fun markActive(id: Long, serverRootFolderId: Long): Int

    /** Подтверждённый или отключённый на Server источник не возвращается в ожидание: повторной регистрации у него нет. */
    @Query("""
        UPDATE backup_sources SET registrationState = :state, registrationError = :error
        WHERE id = :id AND registrationState IN ('PENDING_REGISTRATION', 'REGISTRATION_FAILED')
    """)
    suspend fun markNotRegistered(id: Long, state: RegistrationState, error: String?): Int

    @Query("UPDATE backup_sources SET accessState = :accessState WHERE id = :id")
    suspend fun updateAccess(id: Long, accessState: SourceAccess)

    /** Активные источники аккаунта в порядке подключения: их обрабатывает FolderBackupWorker. */
    @Query("""
        SELECT * FROM backup_sources
        WHERE serverUrl = :serverUrl AND accountId = :accountId AND registrationState = 'ACTIVE'
        ORDER BY createdAt, id
    """)
    suspend fun listActive(serverUrl: String, accountId: String): List<BackupSource>

    /** Удалённое отключение фиксируется один раз; запись и связи сохраняются. */
    @Query("""
        UPDATE backup_sources SET registrationState = 'REMOTE_DISABLED', registrationError = :reason
        WHERE id = :id AND registrationState = 'ACTIVE'
    """)
    suspend fun markRemoteDisabled(id: Long, reason: String): Int

    /**
     * Отключение пользователем, подтверждённое Server: запись источника того же Server и аккаунта по ID корня
     * (и UUID, если известен). Очередь, URI и UUID сохраняются; reason пуст — это не ошибка.
     */
    @Query("""
        UPDATE backup_sources SET registrationState = 'REMOTE_DISABLED', registrationError = NULL
        WHERE serverUrl = :serverUrl AND accountId = :accountId AND serverRootFolderId = :folderId
          AND (:sourceUuid IS NULL OR LOWER(sourceUuid) = LOWER(:sourceUuid)) AND registrationState = 'ACTIVE'
    """)
    suspend fun markDisabled(serverUrl: String, accountId: String, folderId: Long, sourceUuid: String?): Int

    @Query("UPDATE backup_sources SET scanGeneration = :generation, scanErrors = 0 WHERE id = :id")
    suspend fun startScan(id: Long, generation: Int)

    @Query("UPDATE backup_sources SET scanErrors = scanErrors + 1 WHERE id = :id")
    suspend fun countScanError(id: Long)

    @Query("UPDATE backup_sources SET lastScanAt = :at WHERE id = :id")
    suspend fun finishScan(id: Long, at: Long)

    /** Удаляет только запись, которую Server окончательно отклонил: на Server такого источника нет. */
    @Query("DELETE FROM backup_sources WHERE id = :id AND registrationState = 'REGISTRATION_FAILED'")
    suspend fun deleteFailed(id: Long): Int
}

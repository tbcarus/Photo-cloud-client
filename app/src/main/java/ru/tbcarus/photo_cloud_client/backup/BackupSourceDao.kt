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
        WHERE id = :id AND registrationState != 'ACTIVE'
    """)
    suspend fun markActive(id: Long, serverRootFolderId: Long): Int

    /** Подтверждённый источник не возвращается в ожидание: повторной регистрации у него нет. */
    @Query("""
        UPDATE backup_sources SET registrationState = :state, registrationError = :error
        WHERE id = :id AND registrationState != 'ACTIVE'
    """)
    suspend fun markNotRegistered(id: Long, state: RegistrationState, error: String?): Int

    @Query("UPDATE backup_sources SET accessState = :accessState WHERE id = :id")
    suspend fun updateAccess(id: Long, accessState: SourceAccess)

    /** Удаляет только запись, которую Server окончательно отклонил: на Server такого источника нет. */
    @Query("DELETE FROM backup_sources WHERE id = :id AND registrationState = 'REGISTRATION_FAILED'")
    suspend fun deleteFailed(id: Long): Int
}

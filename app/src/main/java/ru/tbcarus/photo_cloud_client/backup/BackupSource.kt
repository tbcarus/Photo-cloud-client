package ru.tbcarus.photo_cloud_client.backup

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Выбранная Android-папка — источник автоматического резервирования на конкретном Server и в конкретном аккаунте.
 * Запись создаётся до первого запроса регистрации и не удаляется при выходе или смене сервера:
 * sourceUuid один на весь срок источника, повтор регистрации идёт с ним же.
 */
@Entity(tableName = "backup_sources", indices = [Index(value = ["sourceUuid"], unique = true)])
data class BackupSource(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceUuid: String,
    val treeUri: String,               // URI дерева SAF с сохранённым разрешением на чтение
    val localName: String,             // имя выбранной папки на устройстве
    val serverName: String,            // имя корня источника в AutoUploads
    val serverRootFolderId: Long?,     // ID корня на Server, только после подтверждения
    val serverUrl: String,             // адрес Server на момент подключения
    val accountId: String,             // subject access token пользователя
    val registrationState: RegistrationState,
    val registrationError: String?,    // последняя причина неуспеха регистрации
    val accessState: SourceAccess,
    val createdAt: Long
)

enum class RegistrationState {
    /** Подтверждённого ID нет; результат последнего запроса мог остаться неизвестным — имя менять нельзя. */
    PENDING_REGISTRATION,

    /** Server отказал окончательно (409, 400): источник не создан, имя можно изменить и повторить. */
    REGISTRATION_FAILED,

    /** Server подтвердил источник, serverRootFolderId сохранён. */
    ACTIVE
}

enum class SourceAccess {
    AVAILABLE,

    /** Разрешение есть, но провайдер или папка сейчас не отвечают: не отзыв доступа. */
    UNAVAILABLE,

    /** Сохранённого разрешения на дерево больше нет. */
    PERMISSION_LOST
}

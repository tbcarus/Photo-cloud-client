package ru.tbcarus.photo_cloud_client.backup

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Папка дерева источника. Идентичность внутри источника — относительный путь от выбранной папки
 * ("" — сам корень): ID документа SAF после переименования может смениться.
 * Обход идёт по поколениям: папка, найденная в поколении (seenGeneration), читается один раз за поколение
 * (listedGeneration), поэтому прерванный обход продолжается с непрочитанных папок.
 */
@Entity(
    tableName = "backup_folders",
    foreignKeys = [ForeignKey(
        entity = BackupSource::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["sourceId", "relativePath"], unique = true)]
)
data class BackupFolder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceId: Long,
    val parentId: Long?,               // null только у корня источника
    val name: String,
    val relativePath: String,
    val documentId: String,            // документ SAF при последнем чтении родителя
    val serverFolderId: Long?,         // папка на Server; у корня — serverRootFolderId источника
    val problem: String?,              // почему папку нельзя создать на Server; сбрасывается новым обходом
    val seenGeneration: Int,
    val listedGeneration: Int
)

/** Файл дерева источника и его связь с RemoteFile. */
@Entity(
    tableName = "backup_files",
    foreignKeys = [
        ForeignKey(
            entity = BackupSource::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BackupFolder::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sourceId", "relativePath"], unique = true),
        Index(value = ["sourceId", "status", "id"]),
        Index(value = ["folderId"])
    ]
)
data class BackupFile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceId: Long,
    val folderId: Long,
    val name: String,
    val relativePath: String,
    val documentId: String,
    val mimeType: String?,
    val size: Long?,                   // как сообщил провайдер; null — неизвестен
    val lastModified: Long?,
    val checksum: String?,             // SHA-256 последнего чтения
    val serverFileId: Long?,
    val syncedChecksum: String?,       // checksum копии на Server, на которую ссылается serverFileId
    val status: BackupFileStatus,
    val failureReason: String?,
    val retryCount: Int = 0,
    val lastFailureAt: Long?,
    val seenGeneration: Int,
    val missingSince: Long?,           // нет в полном обходе; серверная копия сохраняется
    // Архивирование прежней версии (этап 5.4): заполняется до PATCH, очищается после подтверждения новой версии.
    val archiveName: String? = null,   // выбранное имя прежней версии; не меняется при повторах
    val archiveFileId: Long? = null,   // ID прежней версии; сохраняется при переименовании
    val archiveChecksum: String? = null
)

enum class BackupFileStatus {
    /** Нужно прочитать содержимое: новый файл, изменились сведения или нужна сверка с прежней копией. */
    PENDING,

    /** Начатые hash и upload; при следующем прогоне возвращается в PENDING. */
    UPLOADING,

    /** На Server есть копия с syncedChecksum, равным checksum последнего чтения. */
    SYNCED,

    /** Содержимое отличается от связанной копии на Server: архивирование прежней версии ещё не начато. */
    CHANGED,

    /** Архивное имя сохранено, результат PATCH может быть неизвестен: перед повтором состояние сверяется с Server. */
    ARCHIVING,

    /** Прежняя версия переименована (подтверждено), новая под исходным именем ещё не подтверждена. */
    ARCHIVED,

    /** Отправить нельзя до изменения файла: пустой, больше лимита, недопустимое имя, виртуальный документ. */
    SKIPPED,

    /** Данные Server не совпали с ожидаемыми (имя занято чужим объектом, прежняя версия не найдена или другая): Server не меняется. */
    CONFLICT,

    /** Ошибка чтения или папки назначения: повтор при следующем обходе. */
    FAILED
}

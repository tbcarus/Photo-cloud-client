package ru.tbcarus.photo_cloud_client.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.tbcarus.photo_cloud_client.backup.BackupFile
import ru.tbcarus.photo_cloud_client.backup.BackupFileStatus
import ru.tbcarus.photo_cloud_client.backup.BackupFolder
import ru.tbcarus.photo_cloud_client.backup.BackupSource
import ru.tbcarus.photo_cloud_client.backup.BackupSourceDao
import ru.tbcarus.photo_cloud_client.backup.BackupTreeDao
import ru.tbcarus.photo_cloud_client.backup.RegistrationState
import ru.tbcarus.photo_cloud_client.backup.SourceAccess
import ru.tbcarus.photo_cloud_client.media.CameraCheck
import ru.tbcarus.photo_cloud_client.media.CameraCheckDao
import ru.tbcarus.photo_cloud_client.media.CameraCheckError
import ru.tbcarus.photo_cloud_client.media.CameraCheckItem
import ru.tbcarus.photo_cloud_client.media.CameraCheckResult
import ru.tbcarus.photo_cloud_client.media.CameraCheckStage
import ru.tbcarus.photo_cloud_client.media.CameraCheckState
import ru.tbcarus.photo_cloud_client.media.FailureReason
import ru.tbcarus.photo_cloud_client.media.MediaFile
import ru.tbcarus.photo_cloud_client.media.MediaFileDao
import ru.tbcarus.photo_cloud_client.media.MediaFileStatus
import ru.tbcarus.photo_cloud_client.media.UploadSessionRecord
import ru.tbcarus.photo_cloud_client.media.UploadSessionDao

@Database(
    entities = [
        MediaFile::class, BackupSource::class, BackupFolder::class, BackupFile::class,
        CameraCheck::class, CameraCheckItem::class, UploadSessionRecord::class
    ],
    version = 10,
    exportSchema = true
)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaFileDao(): MediaFileDao
    abstract fun uploadSessionDao(): UploadSessionDao

    abstract fun backupSourceDao(): BackupSourceDao

    abstract fun backupTreeDao(): BackupTreeDao

    abstract fun cameraCheckDao(): CameraCheckDao

    class Converters {
        @TypeConverter
        fun fromStatus(status: MediaFileStatus): String = status.name

        @TypeConverter
        fun toStatus(value: String): MediaFileStatus = MediaFileStatus.valueOf(value)

        @TypeConverter
        fun fromFailureReason(reason: FailureReason?): String? = reason?.name

        @TypeConverter
        fun toFailureReason(value: String?): FailureReason? = value?.let { FailureReason.valueOf(it) }

        @TypeConverter
        fun fromRegistrationState(state: RegistrationState): String = state.name

        @TypeConverter
        fun toRegistrationState(value: String): RegistrationState = RegistrationState.valueOf(value)

        @TypeConverter
        fun fromSourceAccess(access: SourceAccess): String = access.name

        @TypeConverter
        fun toSourceAccess(value: String): SourceAccess = SourceAccess.valueOf(value)

        @TypeConverter
        fun fromBackupFileStatus(status: BackupFileStatus): String = status.name

        @TypeConverter
        fun toBackupFileStatus(value: String): BackupFileStatus = BackupFileStatus.valueOf(value)

        @TypeConverter
        fun fromCameraCheckState(state: CameraCheckState): String = state.name

        @TypeConverter
        fun toCameraCheckState(value: String): CameraCheckState = CameraCheckState.valueOf(value)

        @TypeConverter
        fun fromCameraCheckStage(stage: CameraCheckStage): String = stage.name

        @TypeConverter
        fun toCameraCheckStage(value: String): CameraCheckStage = CameraCheckStage.valueOf(value)

        @TypeConverter
        fun fromCameraCheckError(error: CameraCheckError?): String? = error?.name

        @TypeConverter
        fun toCameraCheckError(value: String?): CameraCheckError? = value?.let { CameraCheckError.valueOf(it) }

        @TypeConverter
        fun fromCameraCheckResult(result: CameraCheckResult?): String? = result?.name

        @TypeConverter
        fun toCameraCheckResult(value: String?): CameraCheckResult? = value?.let { CameraCheckResult.valueOf(it) }
    }

    companion object {

        /**
         * Схема не меняется. Прежний HTTP_400 мог означать upload без folderId, отличить его от настоящего отказа
         * нельзя, поэтому такие строки один раз проходят pre-check и upload заново уже с явной папкой.
         * Повторный 400 снова терминален: миграция выполняется единожды.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE media_files
                    SET status = 'CHECKSUM_READY',
                        failureReason = NULL,
                        lastFailureAt = NULL
                    WHERE status = 'FAILED'
                      AND failureReason = 'HTTP_400'
                      AND checksum IS NOT NULL
                    """
                )
            }
        }

        /** Только добавляет таблицу источников автоматического резервирования: очередь Camera не меняется. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `backup_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sourceUuid` TEXT NOT NULL, `treeUri` TEXT NOT NULL, `localName` TEXT NOT NULL, " +
                        "`serverName` TEXT NOT NULL, `serverRootFolderId` INTEGER, `serverUrl` TEXT NOT NULL, " +
                        "`accountId` TEXT NOT NULL, `registrationState` TEXT NOT NULL, `registrationError` TEXT, " +
                        "`accessState` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_backup_sources_sourceUuid` ON `backup_sources` (`sourceUuid`)"
                )
            }
        }

        /** Добавляет учёт дерева источников и сведения обхода; прежние таблицы и строки не меняются. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `backup_sources` ADD COLUMN `scanGeneration` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `backup_sources` ADD COLUMN `scanErrors` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `backup_sources` ADD COLUMN `lastScanAt` INTEGER")
                MIGRATION_5_6_TABLES.forEach(db::execSQL)
            }
        }

        /** Архивирование прежних версий (этап 5.4): только новые nullable-столбцы, состояния и связи файлов сохраняются. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `backup_files` ADD COLUMN `archiveName` TEXT")
                db.execSQL("ALTER TABLE `backup_files` ADD COLUMN `archiveFileId` INTEGER")
                db.execSQL("ALTER TABLE `backup_files` ADD COLUMN `archiveChecksum` TEXT")
            }
        }

        /** Ручная сверка Camera (Q-01): только новые таблицы, очередь Camera и источники не меняются. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `camera_check` (`id` INTEGER NOT NULL, `runId` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, `stage` TEXT NOT NULL, `error` TEXT, `serverUrl` TEXT NOT NULL, " +
                        "`accountId` TEXT NOT NULL, `cameraFolderId` INTEGER, `startedAt` INTEGER NOT NULL, " +
                        "`deferred` INTEGER NOT NULL, `finishedAt` INTEGER, `checked` INTEGER NOT NULL, " +
                        "`confirmed` INTEGER NOT NULL, `missing` INTEGER NOT NULL, `unverifiable` INTEGER NOT NULL, " +
                        "`queuedForUpload` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `camera_check_items` (`mediaStoreId` INTEGER NOT NULL, " +
                        "`size` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, `checksum` TEXT, `result` TEXT, " +
                        "PRIMARY KEY(`mediaStoreId`))"
                )
            }
        }

        /** Счётчик несовпадений checksum после upload (Q-02): только новый столбец, состояния строк сохраняются. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_files` ADD COLUMN `uploadMismatchCount` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `upload_sessions` (`clientRequestId` TEXT NOT NULL, `serverUrl` TEXT NOT NULL, `accountId` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `uri` TEXT NOT NULL, `folderId` INTEGER NOT NULL, `originalFilename` TEXT NOT NULL, `size` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, `originalMode` INTEGER NOT NULL, `checksum` TEXT NOT NULL, `serverSessionId` TEXT, `confirmedOffset` INTEGER NOT NULL, `cancelPending` INTEGER NOT NULL, PRIMARY KEY(`clientRequestId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_sessions_serverUrl_accountId_sourceKind_sourceId` ON `upload_sessions` (`serverUrl`, `accountId`, `sourceKind`, `sourceId`)")
            }
        }

        private val MIGRATION_5_6_TABLES = listOf(
            "CREATE TABLE IF NOT EXISTS `backup_folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, `parentId` INTEGER, `name` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `documentId` TEXT NOT NULL, `serverFolderId` INTEGER, `problem` TEXT, `seenGeneration` INTEGER NOT NULL, `listedGeneration` INTEGER NOT NULL, FOREIGN KEY(`sourceId`) REFERENCES `backup_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_backup_folders_sourceId_relativePath` ON `backup_folders` (`sourceId`, `relativePath`)",
            "CREATE TABLE IF NOT EXISTS `backup_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, `folderId` INTEGER NOT NULL, `name` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `documentId` TEXT NOT NULL, `mimeType` TEXT, `size` INTEGER, `lastModified` INTEGER, `checksum` TEXT, `serverFileId` INTEGER, `syncedChecksum` TEXT, `status` TEXT NOT NULL, `failureReason` TEXT, `retryCount` INTEGER NOT NULL, `lastFailureAt` INTEGER, `seenGeneration` INTEGER NOT NULL, `missingSince` INTEGER, FOREIGN KEY(`sourceId`) REFERENCES `backup_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`folderId`) REFERENCES `backup_folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_backup_files_sourceId_relativePath` ON `backup_files` (`sourceId`, `relativePath`)",
            "CREATE INDEX IF NOT EXISTS `index_backup_files_sourceId_status_id` ON `backup_files` (`sourceId`, `status`, `id`)",
            "CREATE INDEX IF NOT EXISTS `index_backup_files_folderId` ON `backup_files` (`folderId`)"
        )

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                build(context, "photo_cloud.db").also { INSTANCE = it }
            }
        }

        /** Боевая конфигурация БД; тесты миграций открывают ею файл прежней версии. */
        internal fun build(context: Context, name: String): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                // Без пути миграции Room отказывает в открытии БД: очередь Camera и источники не удаляются молча.
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build()
    }
}

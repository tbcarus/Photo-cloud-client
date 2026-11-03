package ru.tbcarus.photo_cloud_client.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.tbcarus.photo_cloud_client.backup.BackupSource
import ru.tbcarus.photo_cloud_client.backup.BackupSourceDao
import ru.tbcarus.photo_cloud_client.backup.RegistrationState
import ru.tbcarus.photo_cloud_client.backup.SourceAccess
import ru.tbcarus.photo_cloud_client.media.FailureReason
import ru.tbcarus.photo_cloud_client.media.MediaFile
import ru.tbcarus.photo_cloud_client.media.MediaFileDao
import ru.tbcarus.photo_cloud_client.media.MediaFileStatus

@Database(entities = [MediaFile::class, BackupSource::class], version = 5, exportSchema = true)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaFileDao(): MediaFileDao

    abstract fun backupSourceDao(): BackupSourceDao

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
                // TODO: временное решение на этапе разработки — при изменении схемы без миграции
                // все данные удаляются. Перед production заменить на полноценные миграции Room.
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

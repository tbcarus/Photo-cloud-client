package ru.tbcarus.photo_cloud_client.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.tbcarus.photo_cloud_client.media.FailureReason
import ru.tbcarus.photo_cloud_client.media.MediaFile
import ru.tbcarus.photo_cloud_client.media.MediaFileDao
import ru.tbcarus.photo_cloud_client.media.MediaFileStatus

@Database(entities = [MediaFile::class], version = 4, exportSchema = true)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaFileDao(): MediaFileDao

    class Converters {
        @TypeConverter
        fun fromStatus(status: MediaFileStatus): String = status.name

        @TypeConverter
        fun toStatus(value: String): MediaFileStatus = MediaFileStatus.valueOf(value)

        @TypeConverter
        fun fromFailureReason(reason: FailureReason?): String? = reason?.name

        @TypeConverter
        fun toFailureReason(value: String?): FailureReason? = value?.let { FailureReason.valueOf(it) }
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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "photo_cloud.db"
                )
                    // TODO: временное решение на этапе разработки — при изменении схемы БД
                    // все данные удаляются. Перед production заменить на полноценные миграции Room.
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { INSTANCE = it }
            }
        }
    }
}

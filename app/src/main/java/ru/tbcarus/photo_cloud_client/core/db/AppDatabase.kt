package ru.tbcarus.photo_cloud_client.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.tbcarus.photo_cloud_client.media.MediaFile
import ru.tbcarus.photo_cloud_client.media.MediaFileDao
import ru.tbcarus.photo_cloud_client.media.MediaFileStatus

@Database(entities = [MediaFile::class], version = 2, exportSchema = true)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaFileDao(): MediaFileDao

    class Converters {
        @TypeConverter
        fun fromStatus(status: MediaFileStatus): String = status.name

        @TypeConverter
        fun toStatus(value: String): MediaFileStatus = MediaFileStatus.valueOf(value)
    }

    companion object {

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_files ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_files ADD COLUMN relativePath TEXT")
                db.execSQL("ALTER TABLE media_files ADD COLUMN lastModified INTEGER NOT NULL DEFAULT 0")
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
                    .addMigrations(MIGRATION_1_2)
                    // TODO: временное решение на этапе разработки — при изменении схемы БД
                    // все данные удаляются. Перед production заменить на полноценные миграции Room.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { INSTANCE = it }
            }
        }
    }
}

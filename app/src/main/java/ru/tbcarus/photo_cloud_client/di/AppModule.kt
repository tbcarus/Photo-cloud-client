package ru.tbcarus.photo_cloud_client.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.media.MediaFileDao
import ru.tbcarus.photo_cloud_client.media.SyncClock
import ru.tbcarus.photo_cloud_client.media.SyncStatusStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideMediaFileDao(db: AppDatabase): MediaFileDao = db.mediaFileDao()

    @Provides
    fun provideSyncClock(): SyncClock = SyncClock.SYSTEM

    // Отдельный файл DataStore: частые итоги прогонов не задевают наблюдателей настроек Server.
    @Provides
    @Singleton
    fun provideSyncStatusStore(@ApplicationContext context: Context): SyncStatusStore =
        SyncStatusStore(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("sync_status") })
}

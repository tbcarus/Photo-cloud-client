package ru.tbcarus.photo_cloud_client.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.tbcarus.photo_cloud_client.media.AppDatabase
import ru.tbcarus.photo_cloud_client.media.MediaFileDao
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
}

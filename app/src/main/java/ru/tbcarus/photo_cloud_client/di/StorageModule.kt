package ru.tbcarus.photo_cloud_client.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.tbcarus.photo_cloud_client.core.storage.EncryptedPrefsTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindTokenStorage(impl: EncryptedPrefsTokenStorage): TokenStorage
}

package ru.tbcarus.photo_cloud_client.library.thumbnail

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Доступ Compose-экрана к синглтону миниатюр без изменения ViewModel Library. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThumbnailsEntryPoint {
    fun libraryThumbnails(): LibraryThumbnails
}

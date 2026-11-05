package ru.tbcarus.photo_cloud_client.library.thumbnail

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.tbcarus.photo_cloud_client.library.original.OriginalImages

/** Доступ Compose-экранов к синглтонам миниатюр и оригиналов без изменения ViewModel Library. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThumbnailsEntryPoint {
    fun libraryThumbnails(): LibraryThumbnails

    fun originalImages(): OriginalImages
}

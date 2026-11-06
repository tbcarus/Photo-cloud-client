package ru.tbcarus.photo_cloud_client.library.thumbnail

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.library.original.OriginalImages
import ru.tbcarus.photo_cloud_client.library.pdf.PdfDocuments
import javax.inject.Named

/** Доступ Compose-экранов к синглтонам миниатюр, оригиналов и сети без изменения ViewModel Library. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThumbnailsEntryPoint {
    fun libraryThumbnails(): LibraryThumbnails

    fun originalImages(): OriginalImages

    fun pdfDocuments(): PdfDocuments

    /** Авторизованный клиент приложения: плеер видео читает download с теми же токенами. */
    @Named("auth")
    fun authClient(): OkHttpClient

    fun baseUrlProvider(): BaseUrlProvider
}

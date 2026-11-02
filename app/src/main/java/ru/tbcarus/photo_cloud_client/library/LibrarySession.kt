package ru.tbcarus.photo_cloud_client.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.utils.JwtUtils

/** Сервер и пользователь, к которым относятся данные библиотеки. user = null — сессии нет. */
data class LibrarySession(val baseUrl: String, val user: String?)

/** Refresh меняет токены, но не subject: сессия при нём не меняется. */
fun librarySessionFlow(baseUrlProvider: BaseUrlProvider, tokenStorage: TokenStorage): Flow<LibrarySession> =
    combine(baseUrlProvider.baseUrlFlow, tokenStorage.tokensFlow) { baseUrl, tokens ->
        LibrarySession(baseUrl, tokens?.let { JwtUtils.getSubject(it.accessToken) })
    }

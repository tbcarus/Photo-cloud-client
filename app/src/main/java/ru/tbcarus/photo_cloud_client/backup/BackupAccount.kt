package ru.tbcarus.photo_cloud_client.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.library.librarySessionFlow
import ru.tbcarus.photo_cloud_client.utils.JwtUtils
import javax.inject.Inject
import javax.inject.Singleton

/** Server и пользователь, к которым относятся источники: адрес из сохранённых настроек и subject access token. */
data class BackupAccount(val serverUrl: String, val accountId: String)

@Singleton
open class BackupAccountProvider @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
    private val tokenStorage: TokenStorage
) {

    /** Текущий аккаунт; null — нет адреса Server или входа. Адрес перечитывается из настроек, а не из памяти. */
    open suspend fun current(): BackupAccount? {
        val serverUrl = baseUrlProvider.reload()
        val accountId = tokenStorage.getTokens()?.let { JwtUtils.getSubject(it.accessToken) }
        return if (serverUrl.isBlank() || accountId == null) null else BackupAccount(serverUrl, accountId)
    }

    /** Refresh меняет токены, но не subject: аккаунт при нём не меняется. */
    open fun observe(): Flow<BackupAccount?> =
        librarySessionFlow(baseUrlProvider, tokenStorage)
            .map { session ->
                val user = session.user
                if (session.baseUrl.isBlank() || user == null) null else BackupAccount(session.baseUrl, user)
            }
            .distinctUntilChanged()
}

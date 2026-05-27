package ru.tbcarus.photo_cloud_client.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.api.models.AuthRequest
import ru.tbcarus.photo_cloud_client.api.models.LogoutRequest
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.utils.getHttpStatusDescription
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val storage: TokenStorage,
    private val baseUrlProvider: BaseUrlProvider,
    private val apiServiceFactory: ApiServiceFactory
) {
    val tokensFlow: Flow<Tokens?> get() = storage.tokensFlow

    fun isReady(): Boolean = baseUrlProvider.isReady

    fun getTokens(): Tokens? = storage.getTokens()
    fun saveTokens(tokens: Tokens) = storage.saveTokens(tokens)
    fun clearTokens() = storage.clear()

    suspend fun register(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = apiServiceFactory.plainAuthService().register(AuthRequest(email, password)).execute()
            if (resp.isSuccessful) resp.body()?.get("message") ?: "Registered"
            else throw Exception(resp.errorBody()?.string() ?: "Unknown error")
        }
    }

    suspend fun login(email: String, password: String): Result<Tokens> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = apiServiceFactory.plainAuthService().login(AuthRequest(email, password)).execute()
            if (resp.isSuccessful) {
                val body = resp.body() ?: throw Exception("Empty response")
                Tokens(body.accessToken, body.refreshToken ?: "")
            } else throw Exception(resp.errorBody()?.string() ?: "Unknown error")
        }
    }

    suspend fun logout(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val refresh = storage.getTokens()?.refreshToken ?: throw Exception("No tokens")
            val resp = apiServiceFactory.authAuthService().logout(LogoutRequest(refresh)).execute()
            if (resp.isSuccessful) storage.clear()
            else throw Exception("Logout failed")
        }
    }

    suspend fun testAuth(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = apiServiceFactory.authTestService().testAuth().execute()
            if (resp.isSuccessful) resp.body()?.message ?: "OK"
            else throw Exception(getHttpStatusDescription(resp.code()))
        }
    }
}

package ru.tbcarus.photo_cloud_client.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.models.AuthRequest
import ru.tbcarus.photo_cloud_client.api.models.LogoutRequest
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.utils.getHttpStatusDescription
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val storage: TokenStorage,
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val plainClient: OkHttpClient,
    @Named("auth") private val authClient: OkHttpClient
) {
    fun isReady(): Boolean = baseUrlProvider.isReady

    fun getTokens(): Tokens? = storage.getTokens()
    fun saveTokens(tokens: Tokens) = storage.saveTokens(tokens)
    fun clearTokens() = storage.clear()

    private fun plainService(): AuthService = Retrofit.Builder()
        .baseUrl(baseUrlProvider.baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(plainClient)
        .build()
        .create(AuthService::class.java)

    private fun authService(): AuthService = Retrofit.Builder()
        .baseUrl(baseUrlProvider.baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(authClient)
        .build()
        .create(AuthService::class.java)

    suspend fun register(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = plainService().register(AuthRequest(email, password)).execute()
            if (resp.isSuccessful) resp.body()?.get("message") ?: "Registered"
            else throw Exception(resp.errorBody()?.string() ?: "Unknown error")
        }
    }

    suspend fun login(email: String, password: String): Result<Tokens> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = plainService().login(AuthRequest(email, password)).execute()
            if (resp.isSuccessful) {
                val body = resp.body() ?: throw Exception("Empty response")
                Tokens(body.accessToken, body.refreshToken ?: "")
            } else throw Exception(resp.errorBody()?.string() ?: "Unknown error")
        }
    }

    suspend fun logout(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val refresh = storage.getTokens()?.refreshToken ?: throw Exception("No tokens")
            val resp = authService().logout(LogoutRequest(refresh)).execute()
            if (resp.isSuccessful) storage.clear()
            else throw Exception("Logout failed")
        }
    }

    suspend fun testAuth(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = authService().testAuth("").execute()
            if (resp.isSuccessful) resp.body()?.message ?: "OK"
            else throw Exception(getHttpStatusDescription(resp.code()))
        }
    }
}

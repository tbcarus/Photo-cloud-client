package ru.tbcarus.photo_cloud_client.core.network

import android.util.Log
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.models.RefreshTokenRequest
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import javax.inject.Inject
import javax.inject.Named

class TokenAuthenticator @Inject constructor(
    private val storage: TokenStorage,
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val okHttpClient: OkHttpClient
) : Authenticator {

    private val lock = Any()

    private fun refreshService(baseUrl: String): AuthService = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(okHttpClient)
        .build()
        .create(AuthService::class.java)

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null

        synchronized(lock) {
            val baseUrl = baseUrlProvider.baseUrl
            if (baseUrl.isBlank()) {
                Log.w("TokenAuthenticator", "Base URL is not configured; skipping token refresh")
                return null
            }

            // Extract the access token that was used in the failed request
            val failedAccessToken = response.request.header("Authorization")
                ?.removePrefix("Bearer ")

            val current = storage.getTokens() ?: return null

            // If the stored token differs from the failed one, another thread already refreshed —
            // retry the request with the updated token without performing a new refresh
            if (failedAccessToken != null && failedAccessToken != current.accessToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${current.accessToken}")
                    .build()
            }

            val refreshResp = refreshService(baseUrl)
                .refreshToken(RefreshTokenRequest(current.refreshToken))
                .execute()

            if (!refreshResp.isSuccessful) {
                storage.clear()
                return null
            }

            val body = refreshResp.body() ?: return null
            val newAccess = body.accessToken ?: return null

            // Server does not rotate the refresh token; keep the existing one
            val newTokens = Tokens(newAccess, current.refreshToken)
            storage.saveTokens(newTokens)

            return response.request.newBuilder()
                .header("Authorization", "Bearer ${newTokens.accessToken}")
                .build()
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var r = response.priorResponse
        while (r != null) {
            count++
            r = r.priorResponse
        }
        return count
    }
}

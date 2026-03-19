package ru.tbcarus.photo_cloud_client.network

import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.models.RefreshTokenRequest
import ru.tbcarus.photo_cloud_client.auth.TokenStorage
import ru.tbcarus.photo_cloud_client.auth.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import javax.inject.Inject
import javax.inject.Named

class TokenAuthenticator @Inject constructor(
    private val storage: TokenStorage,
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val okHttpClient: OkHttpClient
) : Authenticator {

    private val lock = Any()

    private fun refreshService(): AuthService = Retrofit.Builder()
        .baseUrl(baseUrlProvider.baseUrl.ifBlank { "http://localhost/" })
        .addConverterFactory(GsonConverterFactory.create())
        .client(okHttpClient)
        .build()
        .create(AuthService::class.java)

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null

        synchronized(lock) {
            val current = storage.getTokens() ?: return null

            val latest = storage.getTokens() ?: return null
            if (latest.accessToken != current.accessToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${latest.accessToken}")
                    .build()
            }

            val refreshResp = refreshService().refreshToken(RefreshTokenRequest(current.refreshToken)).execute()
            if (!refreshResp.isSuccessful) {
                storage.clear()
                return null
            }
            val body = refreshResp.body() ?: return null
            val newAccess = body.accessToken ?: return null
            val newRefresh = body.refreshToken ?: current.refreshToken
            val newTokens = Tokens(newAccess, newRefresh)
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

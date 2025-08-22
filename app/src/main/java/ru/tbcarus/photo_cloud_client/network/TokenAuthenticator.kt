package ru.tbcarus.photo_cloud_client.network

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.models.RefreshTokenRequest
import ru.tbcarus.photo_cloud_client.auth.TokenStorage
import ru.tbcarus.photo_cloud_client.auth.Tokens

class TokenAuthenticator(
    private val storage: TokenStorage,
    private val service: AuthService
) : Authenticator {

    private val lock = Any() // простой вариант, можно Mutex

    override fun authenticate(route: Route?, response: Response): Request? {
        // Избежать бесконечного цикла
        if (responseCount(response) >= 2) return null

        synchronized(lock) {
            val current = storage.getTokens() ?: return null

            // Если другой поток уже обновил — не рефрешим
            val latest = storage.getTokens() ?: return null
            if (latest.accessToken != current.accessToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${latest.accessToken}")
                    .build()
            }

            // Пытаемся обновить
            val refreshResp = service.refreshToken(RefreshTokenRequest(current.refreshToken)).execute()
            if (!refreshResp.isSuccessful) {
                storage.clear()
                return null
            }
            val body = refreshResp.body() ?: return null
            val newTokens = Tokens(body.accessToken, body.refreshToken)
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

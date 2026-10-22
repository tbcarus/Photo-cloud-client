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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named

class TokenAuthenticator @Inject constructor(
    private val storage: TokenStorage,
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val okHttpClient: OkHttpClient
) : Authenticator {

    private companion object {
        // Коды ErrorResponse Server для окончательного отказа refresh (GlobalExceptionHandler).
        const val INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN"
        const val REFRESH_TOKEN_REVOKED = "REFRESH_TOKEN_REVOKED"
    }

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
                return withAccessToken(response, current.accessToken)
            }

            // null — окончательный отказ refresh; временный отказ выходит отсюда как IOException.
            val refreshed = requestNewTokens(baseUrl, current)
            if (refreshed == null) {
                // replaceIf не тронет пару, если её уже сменили login/logout: стирается только отвергнутая сессия.
                storage.replaceIf(current, null)
                return null
            }

            // Новый refresh пишется синхронно; неподтверждённая запись выходит отсюда как IOException.
            if (storage.replaceIf(current, refreshed)) {
                return withAccessToken(response, refreshed.accessToken)
            }
            // Пара сменилась во время refresh: новую не перезаписываем, а после logout старую не восстанавливаем.
            val actual = storage.getTokens() ?: return null
            return withAccessToken(response, actual.accessToken)
        }
    }

    /**
     * Новая пара по refresh: новый access и новый refresh, если Server заменил его, иначе прежний.
     * null — окончательный отказ Server: refresh недействителен или отозван.
     * Любой другой исход временный: IOException без токенов в тексте, пара при этом не меняется.
     */
    private fun requestNewTokens(baseUrl: String, current: Tokens): Tokens? {
        // Сетевые ошибки и timeout пробрасываются как есть — это IOException.
        val refreshResp = try {
            refreshService(baseUrl).refreshToken(RefreshTokenRequest(current.refreshToken)).execute()
        } catch (e: RuntimeException) {
            // Тело 2xx не разобралось как ответ refresh. Причину не прикладываем: в ней мог быть фрагмент тела.
            throw IOException("Token refresh returned an invalid response")
        }
        if (refreshResp.isSuccessful) {
            val body = refreshResp.body()
            val access = body?.accessToken?.takeIf { it.isNotBlank() }
                ?: throw IOException("Token refresh returned no access token")
            // Поля нет — Server не заменял refresh; пустая строка — некорректный ответ.
            val refresh = body.refreshToken ?: current.refreshToken
            if (refresh.isBlank()) throw IOException("Token refresh returned an empty refresh token")
            return Tokens(access, refresh)
        }
        val status = refreshResp.code()
        // По коду ErrorResponse, а не только по статусу: 401 может прийти и от необработанной ошибки Server.
        val code = ApiErrorParser.errorCode(refreshResp)
        val rejected = (status == 401 && code == INVALID_REFRESH_TOKEN) ||
            (status == 403 && code == REFRESH_TOKEN_REVOKED)
        if (rejected) return null
        throw IOException("Token refresh temporarily failed: HTTP $status")
    }

    private fun withAccessToken(response: Response, accessToken: String): Request =
        response.request.newBuilder()
            .header("Authorization", "Bearer $accessToken")
            .build()

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

package ru.tbcarus.photo_cloud_client.auth

import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.models.LogoutRequest
import ru.tbcarus.photo_cloud_client.api.models.RefreshTokenRequest

class AuthRepository(
    private val service: AuthService,
    private val storage: TokenStorage
) {
    fun saveTokens(access: String, refresh: String) {
        storage.saveTokens(Tokens(access, refresh))
    }

    fun getTokens(): Tokens? = storage.getTokens()

    fun clearTokens() = storage.clear()

    fun refreshToken(): Boolean {
        val refresh = storage.getTokens()?.refreshToken ?: return false
        val resp = service.refreshToken(RefreshTokenRequest(refresh)).execute()
        return if (resp.isSuccessful) {
            val body = resp.body() ?: return false
            storage.saveTokens(Tokens(body.accessToken, body.refreshToken))
            true
        } else {
            storage.clear()
            false
        }
    }

    fun logout(): Boolean {
        val refresh = storage.getTokens()?.refreshToken ?: return false
        val resp = service.logout(LogoutRequest(refresh)).execute()
        return if (resp.isSuccessful) {
            storage.clear()
            true
        } else false
    }
}

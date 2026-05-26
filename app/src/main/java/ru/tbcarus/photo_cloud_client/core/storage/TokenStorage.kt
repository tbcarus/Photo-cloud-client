package ru.tbcarus.photo_cloud_client.core.storage

interface TokenStorage {
    fun getTokens(): Tokens?
    fun saveTokens(tokens: Tokens)
    fun clear()

    fun getAccess(): String? = getTokens()?.accessToken
    fun getRefresh(): String? = getTokens()?.refreshToken
    fun saveAccess(access: String) {
        val current = getTokens() ?: return
        saveTokens(current.copy(accessToken = access))
    }
}

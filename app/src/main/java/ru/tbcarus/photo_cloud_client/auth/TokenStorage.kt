package ru.tbcarus.photo_cloud_client.auth


interface TokenStorage {
    fun getTokens(): Tokens?
    fun saveTokens(tokens: Tokens)
    fun clear()

    // Удобные геттеры/сеттеры по-месту
    fun getAccess(): String? = getTokens()?.accessToken
    fun getRefresh(): String? = getTokens()?.refreshToken
    fun saveAccess(access: String) {
        val current = getTokens() ?: return
        saveTokens(current.copy(accessToken = access))
    }
}
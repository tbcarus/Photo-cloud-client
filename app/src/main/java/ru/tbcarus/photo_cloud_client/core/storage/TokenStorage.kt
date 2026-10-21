package ru.tbcarus.photo_cloud_client.core.storage

import kotlinx.coroutines.flow.Flow

interface TokenStorage {
    val tokensFlow: Flow<Tokens?>

    fun getTokens(): Tokens?
    fun saveTokens(tokens: Tokens)
    fun clear()

    /**
     * Атомарно заменяет пару, только если в хранилище сейчас ровно [expected] (и access, и refresh);
     * replacement = null очищает пару. Нужен refresh: запоздалый результат не должен затронуть пару,
     * сменившуюся после login/logout. Возвращает true, если замена выполнена.
     */
    fun replaceIf(expected: Tokens, replacement: Tokens?): Boolean

    fun getAccess(): String? = getTokens()?.accessToken
    fun getRefresh(): String? = getTokens()?.refreshToken
    fun saveAccess(access: String) {
        val current = getTokens() ?: return
        saveTokens(current.copy(accessToken = access))
    }
}

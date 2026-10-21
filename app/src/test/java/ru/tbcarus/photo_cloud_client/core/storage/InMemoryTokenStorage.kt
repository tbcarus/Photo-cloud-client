package ru.tbcarus.photo_cloud_client.core.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * TokenStorage для JVM-тестов с тем же контрактом, что у EncryptedPrefsTokenStorage
 * (та требует Android Keystore): одна блокировка, replaceIf сравнивает всю пару.
 */
class InMemoryTokenStorage(initial: Tokens? = null) : TokenStorage {

    private val lock = Any()
    private val state = MutableStateFlow(initial)
    override val tokensFlow: StateFlow<Tokens?> = state

    override fun getTokens(): Tokens? = synchronized(lock) { state.value }

    override fun saveTokens(tokens: Tokens) = synchronized(lock) { state.value = tokens }

    override fun clear() = synchronized(lock) { state.value = null }

    override fun replaceIf(expected: Tokens, replacement: Tokens?): Boolean = synchronized(lock) {
        if (state.value != expected) return false
        state.value = replacement
        true
    }
}

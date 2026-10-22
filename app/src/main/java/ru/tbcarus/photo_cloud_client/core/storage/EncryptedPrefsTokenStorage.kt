package ru.tbcarus.photo_cloud_client.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import javax.inject.Inject

// SharedPreferences передаются отдельно, чтобы запись можно было проверить на JVM без Android Keystore.
class EncryptedPrefsTokenStorage internal constructor(
    private val prefs: SharedPreferences
) : TokenStorage {

    @Inject
    constructor(@ApplicationContext context: Context) : this(createPrefs(context))

    // Одна блокировка на чтение и все изменения: пара читается целиком, replaceIf сравнивает и пишет атомарно,
    // а tokensFlow меняется вместе с хранилищем. apply() обновляет данные в памяти сразу, на диск — асинхронно.
    private val lock = Any()

    private val _tokensFlow = MutableStateFlow<Tokens?>(getTokens())
    override val tokensFlow: StateFlow<Tokens?> = _tokensFlow

    override fun getTokens(): Tokens? = synchronized(lock) {
        val access = prefs.getString("accessToken", null)
        val refresh = prefs.getString("refreshToken", null)
        if (access != null && refresh != null) Tokens(access, refresh) else null
    }

    override fun saveTokens(tokens: Tokens) {
        synchronized(lock) { write(tokens, durable = false) }
    }

    override fun clear() {
        synchronized(lock) { write(null, durable = false) }
    }

    override fun replaceIf(expected: Tokens, replacement: Tokens?): Boolean = synchronized(lock) {
        if (getTokens() != expected) return false
        // Новый refresh — commit(): прежний уже отозван Server, асинхронная запись потеряла бы сессию
        // при гибели процесса.
        val durable = replacement != null && replacement.refreshToken != expected.refreshToken
        if (!write(replacement, durable)) throw IOException("Token storage did not confirm the write")
        true
    }

    // Обе строки пары — в одном Editor. Память SharedPreferences меняется и при неудачной записи на диск,
    // поэтому tokensFlow следует ей; false — commit() не подтвердил запись.
    private fun write(tokens: Tokens?, durable: Boolean): Boolean {
        val editor = if (tokens == null) {
            prefs.edit().clear()
        } else {
            prefs.edit().putString("accessToken", tokens.accessToken)
                .putString("refreshToken", tokens.refreshToken)
        }
        val written = if (durable) editor.commit() else {
            editor.apply()
            true
        }
        _tokensFlow.value = tokens
        return written
    }

    private companion object {
        fun createPrefs(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            "secure_tokens",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

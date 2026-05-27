package ru.tbcarus.photo_cloud_client.core.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class EncryptedPrefsTokenStorage @Inject constructor(
    @ApplicationContext context: Context
) : TokenStorage {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _tokensFlow = MutableStateFlow<Tokens?>(getTokens())
    override val tokensFlow: StateFlow<Tokens?> = _tokensFlow

    override fun getTokens(): Tokens? {
        val access = prefs.getString("accessToken", null)
        val refresh = prefs.getString("refreshToken", null)
        return if (access != null && refresh != null) Tokens(access, refresh) else null
    }

    override fun saveTokens(tokens: Tokens) {
        prefs.edit().putString("accessToken", tokens.accessToken)
            .putString("refreshToken", tokens.refreshToken)
            .apply()
        _tokensFlow.value = tokens
    }

    override fun clear() {
        prefs.edit().clear().apply()
        _tokensFlow.value = null
    }
}

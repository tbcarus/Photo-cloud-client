package ru.tbcarus.photo_cloud_client.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStorage(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(tokens: Tokens) {
        prefs.edit()
            .putString("accessToken", tokens.accessToken)
            .putString("refreshToken", tokens.refreshToken)
            .apply()
    }

    fun get(): Tokens? {
        val access = prefs.getString("accessToken", null)
        val refresh = prefs.getString("refreshToken", null)
        return if (access != null && refresh != null) Tokens(access, refresh) else null
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
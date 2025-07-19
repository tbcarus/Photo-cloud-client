package ru.tbcarus.photo_cloud_client.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

object AppPreferences {

    private val IP_KEY = stringPreferencesKey("ip")
    private val PORT_KEY = stringPreferencesKey("port")

    fun getIp(context: Context): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[IP_KEY]
        }
    }

    fun getPort(context: Context): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[PORT_KEY]
        }
    }

    suspend fun saveConnection(context: Context, ip: String, port: String) {
        context.dataStore.edit { preferences ->
            preferences[IP_KEY] = ip
            preferences[PORT_KEY] = port
        }
    }
}


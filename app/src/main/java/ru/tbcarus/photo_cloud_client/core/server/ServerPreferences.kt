package ru.tbcarus.photo_cloud_client.core.server

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupNetworkMode { WIFI_ONLY, WIFI_AND_MOBILE }

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class ServerPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val IP_KEY = stringPreferencesKey("ip")
    private val PORT_KEY = stringPreferencesKey("port")
    private val BACKUP_NETWORK_KEY = stringPreferencesKey("backup_network_mode")

    fun getIp(): Flow<String?> = context.dataStore.data.map { it[IP_KEY] }

    fun getPort(): Flow<String?> = context.dataStore.data.map { it[PORT_KEY] }

    fun backupNetworkMode(): Flow<BackupNetworkMode> = context.dataStore.data.map {
        if (it[BACKUP_NETWORK_KEY] == BackupNetworkMode.WIFI_AND_MOBILE.name) BackupNetworkMode.WIFI_AND_MOBILE
        else BackupNetworkMode.WIFI_ONLY
    }

    suspend fun saveBackupNetworkMode(mode: BackupNetworkMode) {
        context.dataStore.edit { it[BACKUP_NETWORK_KEY] = mode.name }
    }

    suspend fun saveConnection(ip: String, port: String) {
        context.dataStore.edit { preferences ->
            preferences[IP_KEY] = ip
            preferences[PORT_KEY] = port
        }
    }
}

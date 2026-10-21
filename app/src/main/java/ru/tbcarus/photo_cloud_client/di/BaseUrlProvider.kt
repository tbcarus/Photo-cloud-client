package ru.tbcarus.photo_cloud_client.di

import kotlinx.coroutines.flow.first
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.server.buildBaseUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BaseUrlProvider @Inject constructor(
    private val serverPreferences: ServerPreferences
) {
    // Пишется из корутин UI и Worker, читается из потоков OkHttp.
    @Volatile
    var baseUrl: String = ""
    val isReady: Boolean get() = baseUrl.isNotBlank()

    /**
     * Перечитывает сохранённые IP/port из ServerPreferences и обновляет адрес в памяти.
     * Нужен процессу без UI (WorkManager) и после смены адреса. Если настроек нет или они некорректны,
     * адрес очищается, чтобы не обратиться к прежнему серверу. DataStore читается асинхронно, без блокировки потока.
     */
    suspend fun reload(): String {
        val url = buildBaseUrl(serverPreferences.getIp().first(), serverPreferences.getPort().first()).orEmpty()
        baseUrl = url
        return url
    }
}

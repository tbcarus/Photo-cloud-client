package ru.tbcarus.photo_cloud_client.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.server.buildBaseUrl
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Собирает пользовательское состояние backup (A7.1) из реактивных источников: Room, WorkManager, хранилища токенов,
 * настроек Server и сохранённых итогов прогонов. Опросов и собственных фоновых задач нет — значение
 * пересчитывается, когда меняется один из источников.
 */
@Singleton
class BackupStateMonitor @Inject constructor(
    private val mediaFileRepository: MediaFileRepository,
    private val syncScheduler: SyncScheduler,
    private val syncStatusStore: SyncStatusStore,
    private val tokenStorage: TokenStorage,
    private val serverPreferences: ServerPreferences
) {
    /**
     * [hasMediaAccess] — фактический полный доступ к фото. Android не уведомляет об изменении разрешения,
     * поэтому значение перечитывает экран при открытии и возврате в приложение.
     */
    fun observe(hasMediaAccess: Flow<Boolean>): Flow<BackupState> {
        val access = combine(
            hasMediaAccess,
            tokenStorage.tokensFlow.map { it != null },
            hasServerAddress()
        ) { media, signedIn, address -> Access(media, signedIn, address) }

        return combine(
            mediaFileRepository.observeCounters(),
            syncScheduler.observeActivity(),
            access,
            syncStatusStore.observeRunResults()
        ) { counters, activity, current, results ->
            backupStateOf(
                counters = counters,
                activity = activity,
                hasMediaAccess = current.media,
                isSignedIn = current.signedIn,
                hasServerAddress = current.address,
                results = results
            )
        }.distinctUntilChanged()
    }

    /** Тот же критерий, что у Загрузчика: адрес строится по сохранённым IP/port по правилу [buildBaseUrl]. */
    private fun hasServerAddress(): Flow<Boolean> =
        combine(serverPreferences.getIp(), serverPreferences.getPort()) { ip, port -> buildBaseUrl(ip, port) != null }

    private data class Access(val media: Boolean, val signedIn: Boolean, val address: Boolean)
}

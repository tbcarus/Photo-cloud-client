package ru.tbcarus.photo_cloud_client.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Хранит итог последнего прогона авто-sync и отдаёт его как [Flow] для UI.
 * In-memory: результат не переживает перезапуск процесса — для текущего этапа этого достаточно.
 * WorkManager не умеет отдавать причину retry через WorkInfo, поэтому [SyncWorker] пишет статус сюда напрямую.
 */
@Singleton
class SyncStatusStore @Inject constructor() {

    private val _lastStatus = MutableStateFlow<SyncStatusRecord?>(null)

    fun observe(): Flow<SyncStatusRecord?> = _lastStatus.asStateFlow()

    fun record(status: SyncStatus) {
        _lastStatus.value = SyncStatusRecord(
            status = status,
            timestamp = System.currentTimeMillis()
        )
    }
}

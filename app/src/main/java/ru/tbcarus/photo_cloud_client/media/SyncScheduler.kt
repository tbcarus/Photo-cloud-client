package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ставит автоматический sync в очередь WorkManager и наблюдает за его состоянием.
 * Изолирует WorkManager API от ViewModel/UI.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStatusStore: SyncStatusStore
) {

    companion object {
        const val UNIQUE_SYNC_WORK_NAME = "photo_sync"
        private const val UNIQUE_PERIODIC_SYNC_WORK_NAME = "photo_sync_periodic"
        private const val PERIODIC_INTERVAL_HOURS = 1L
    }

    fun enqueueOneTimeSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        // KEEP не запускает второй sync параллельно уже выполняющемуся.
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Периодический фоновый sync (тот же [SyncWorker], отдельное unique-имя).
     * Интервал 1 час; для periodic дополнительно требуем «батарея не низкая», чтобы
     * фоновый прогон не запускал тяжёлый pipeline при низком заряде.
     */
    fun enqueuePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        // UPDATE подхватывает новые параметры расписания, не сбрасывая отсчитанный период.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPeriodicSync() {
        WorkManager.getInstance(context)
            .cancelUniqueWork(UNIQUE_PERIODIC_SYNC_WORK_NAME)
    }

    /**
     * true только пока worker реально выполняется (RUNNING).
     * ENQUEUED / BLOCKED означают «запланировано / ждёт retry», а не активный прогон —
     * иначе UI показывал бы бесконечный loader между попытками retry.
     */
    fun observeIsSyncing(): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(UNIQUE_SYNC_WORK_NAME)
            .map { infos ->
                infos.any { info -> info.state == WorkInfo.State.RUNNING }
            }

    /** Итог последнего завершённого прогона sync (null, если ещё не запускался в этой сессии). */
    fun observeLastSyncStatus(): Flow<SyncStatusRecord?> = syncStatusStore.observe()
}

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
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ставит фоновые части синхронизации в очередь WorkManager и наблюдает за их состоянием (A4.1):
 *  - Поставщик ([ProviderWorker]) — локальная обработка, без требования сети;
 *  - Загрузчик ([UploadWorker]) — pre-check и upload, при подключённой сети.
 * У каждой части своя разовая и своя периодическая задача. Изолирует WorkManager API от ViewModel/UI.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStatusStore: SyncStatusStore
) {

    companion object {
        const val PROVIDER_WORK_NAME = "photo_provider"
        const val PROVIDER_PERIODIC_WORK_NAME = "photo_provider_periodic"
        const val UPLOAD_WORK_NAME = "photo_upload"
        const val UPLOAD_PERIODIC_WORK_NAME = "photo_upload_periodic"

        private const val PERIODIC_INTERVAL_HOURS = 1L
    }

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Разовый прогон Поставщика. KEEP не запускает второй параллельно уже ожидающему или выполняющемуся. */
    fun enqueueProvider() {
        val request = OneTimeWorkRequestBuilder<ProviderWorker>().build()
        workManager.enqueueUniqueWork(PROVIDER_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Периодический Поставщик: 1 час, без требования сети.
     * «Батарея не низкая» — чтобы фоновый hash не запускался при низком заряде.
     */
    fun enqueueProviderPeriodic() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<ProviderWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        // UPDATE подхватывает новые параметры расписания, не сбрасывая отсчитанный период.
        workManager.enqueueUniquePeriodicWork(
            PROVIDER_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelProviderPeriodic() {
        workManager.cancelUniqueWork(PROVIDER_PERIODIC_WORK_NAME)
    }

    /** Разовый прогон Загрузчика при подключённой сети; KEEP — как у Поставщика. */
    fun enqueueUpload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(UPLOAD_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Периодический Загрузчик: 1 час, сеть и «батарея не низкая».
     * Не зависит от сигнала Поставщика и от backoff разовой задачи — подхватывает очередь сам.
     */
    fun enqueueUploadPeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<UploadWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UPLOAD_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelUploadPeriodic() {
        workManager.cancelUniqueWork(UPLOAD_PERIODIC_WORK_NAME)
    }

    /**
     * true только пока одна из разовых задач (Поставщик или Загрузчик) реально выполняется (RUNNING).
     * ENQUEUED / BLOCKED означают «запланировано / ждёт retry», а не активный прогон —
     * иначе UI показывал бы бесконечный loader между попытками retry.
     */
    fun observeIsSyncing(): Flow<Boolean> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(PROVIDER_WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(UPLOAD_WORK_NAME)
        ) { provider, upload ->
            (provider + upload).any { info -> info.state == WorkInfo.State.RUNNING }
        }

    /** Итог последнего завершённого прогона sync (null, если ещё не запускался в этой сессии). */
    fun observeLastSyncStatus(): Flow<SyncStatusRecord?> = syncStatusStore.observe()
}

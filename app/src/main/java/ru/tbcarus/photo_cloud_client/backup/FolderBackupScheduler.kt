package ru.tbcarus.photo_cloud_client.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Очередь WorkManager автоматического резервирования папок; с задачами Camera/A8 не пересекается.
 * Правила A8: внешние запросы — KEEP, продолжение — APPEND_OR_REPLACE к разовой цепочке.
 */
@Singleton
open class FolderBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val WORK_NAME = "folder_backup"
        const val PERIODIC_WORK_NAME = "folder_backup_periodic"
        const val ONE_TIME_TAG = "folder_backup_one_time"
        private const val PERIODIC_INTERVAL_MINUTES = 30L
    }

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Подключение источника, старт процесса, вход: новый обход, если прежний завершён. */
    open fun enqueue() {
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request(fullScan = true))
    }

    /**
     * Продолжение прогона, остановленного бюджетом: начнётся после успешного завершения идущей разовой работы,
     * дочитывает начатый обход и очередь, нового обхода не начинает. Подтверждается записью в базу WorkManager.
     */
    open fun continueRun(): Operation =
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(fullScan = false))

    open fun enqueuePeriodic() {
        val request = PeriodicWorkRequestBuilder<FolderBackupWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        // UPDATE подхватывает параметры расписания, не сбрасывая отсчитанный период.
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    open fun cancelPeriodic() {
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    private fun request(fullScan: Boolean) = OneTimeWorkRequestBuilder<FolderBackupWorker>()
        .setConstraints(network)
        .setInputData(workDataOf(FolderBackupWorker.KEY_FULL_SCAN to fullScan))
        .addTag(ONE_TIME_TAG)
        .build()
}

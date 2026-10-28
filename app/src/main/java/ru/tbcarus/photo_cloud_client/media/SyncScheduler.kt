package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
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
 * У каждой части своя разовая и своя периодическая задача; Поставщика также запускает наблюдатель
 * MediaStore ([MediaTriggerWorker]). Изолирует WorkManager API от ViewModel/UI.
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
        const val MEDIA_TRIGGER_WORK_NAME = "photo_media_trigger"

        /** Тег разовых запросов: по нему Worker отличает разовый прогон от периодического. */
        const val ONE_TIME_TAG = "one_time"

        private const val PERIODIC_INTERVAL_MINUTES = 30L

        // Серия событий одной съёмки объединяется: запуск после 3 с тишины, но не позже 30 с от первого.
        private const val TRIGGER_UPDATE_DELAY_SECONDS = 3L
        private const val TRIGGER_MAX_DELAY_SECONDS = 30L
    }

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Разовый прогон Поставщика. KEEP не запускает второй параллельно уже ожидающему или выполняющемуся. */
    fun enqueueProvider() {
        workManager.enqueueUniqueWork(PROVIDER_WORK_NAME, ExistingWorkPolicy.KEEP, providerRequest())
    }

    /**
     * Продолжение Поставщика после прогона, остановленного бюджетом времени (A8.1).
     *
     * Из разового прогона — APPEND_OR_REPLACE к той же цепочке: следующий запуск зависит от текущего
     * и начнётся только после его успешного завершения; текущий не отменяется. Внешние KEEP-сигналы
     * в это время видят RUNNING/ENQUEUED и ничего не добавляют.
     * Из периодического — обычный KEEP: идущая разовая цепочка продолжит себя сама, иначе ставится новая.
     * Возвращённая [Operation] подтверждает запись в базу WorkManager, не немедленный запуск.
     */
    fun continueProvider(fromOneTimeRun: Boolean): Operation {
        val policy = if (fromOneTimeRun) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        return workManager.enqueueUniqueWork(PROVIDER_WORK_NAME, policy, providerRequest())
    }

    private fun providerRequest() = OneTimeWorkRequestBuilder<ProviderWorker>()
        .addTag(ONE_TIME_TAG)
        .build()

    /**
     * Периодический Поставщик: 30 минут, без требования сети.
     * «Батарея не низкая» — чтобы фоновый hash не запускался при низком заряде.
     */
    fun enqueueProviderPeriodic() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<ProviderWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
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
     * Периодический Загрузчик: 30 минут, сеть и «батарея не низкая».
     * Не зависит от сигнала Поставщика и от backoff разовой задачи — подхватывает очередь сам.
     */
    fun enqueueUploadPeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<UploadWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
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
     * Включает ожидание изменений MediaStore. KEEP не трогает уже ожидающего или выполняющегося наблюдателя;
     * завершённые задания с этим именем WorkManager удаляет и ставит новое — так наблюдение восстанавливается.
     */
    fun enqueueMediaTrigger() {
        workManager.enqueueUniqueWork(MEDIA_TRIGGER_WORK_NAME, ExistingWorkPolicy.KEEP, mediaTriggerRequest())
    }

    /**
     * Следующее ожидание из выполняющегося [MediaTriggerWorker]. REPLACE в одной транзакции отменяет и удаляет
     * текущее задание и вставляет новое: под именем всегда одно задание, цепочек зависимостей нет.
     */
    fun rearmMediaTrigger() {
        workManager.enqueueUniqueWork(MEDIA_TRIGGER_WORK_NAME, ExistingWorkPolicy.REPLACE, mediaTriggerRequest())
    }

    fun cancelMediaTrigger() {
        workManager.cancelUniqueWork(MEDIA_TRIGGER_WORK_NAME)
    }

    /** Одноразовое ожидание изменений Images и вложенных URI; сеть и прочие условия не нужны. */
    private fun mediaTriggerRequest() = OneTimeWorkRequestBuilder<MediaTriggerWorker>()
        .setConstraints(
            Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(TRIGGER_UPDATE_DELAY_SECONDS, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
        )
        .build()

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

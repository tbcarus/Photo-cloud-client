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
import androidx.work.WorkRequest
import androidx.work.await
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    @ApplicationContext private val context: Context
) {

    companion object {
        const val PROVIDER_WORK_NAME = "photo_provider"
        const val PROVIDER_PERIODIC_WORK_NAME = "photo_provider_periodic"
        const val UPLOAD_WORK_NAME = "photo_upload"
        const val UPLOAD_PERIODIC_WORK_NAME = "photo_upload_periodic"
        /** Ручной повтор серверной стадии сверки Camera — см. [retryUploadNow]. */
        const val UPLOAD_RETRY_WORK_NAME = "photo_upload_retry"
        const val MEDIA_TRIGGER_WORK_NAME = "photo_media_trigger"

        /** Тег разовых запросов: отличает их от периодических в базе WorkManager. */
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
     * APPEND_OR_REPLACE к разовой цепочке: следующий запуск зависит от текущего разового прогона и начнётся
     * только после его успешного завершения; текущий не отменяется. Внешние KEEP-сигналы в это время видят
     * RUNNING/ENQUEUED и ничего не добавляют. Без активной цепочки ставится новая.
     * Периодический прогон регистрирует продолжение так же: разовый прогон, простоявший весь бюджет на Mutex,
     * завершится без прогресса и своего продолжения не поставит, поэтому KEEP потерял бы продолжение.
     * Возвращённая [Operation] подтверждает запись в базу WorkManager, не немедленный запуск.
     */
    fun continueProvider(): Operation =
        workManager.enqueueUniqueWork(PROVIDER_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, providerRequest())

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
        workManager.enqueueUniqueWork(UPLOAD_WORK_NAME, ExistingWorkPolicy.KEEP, uploadRequest())
    }

    /**
     * Сигнал Поставщика Загрузчику (Q-02.1). Ждущий запуск разовой цепочки (ENQUEUED/BLOCKED, в том числе backoff
     * после ошибки) не трогается: он сам дочитает очередь из Room. Выполняющийся прогон мог уже пройти последнюю
     * проверку очереди, и KEEP потерял бы сигнал до periodic, поэтому к нему добавляется один следующий запуск
     * ([continueUpload]); последующие сигналы видят его ожидающим и ничего не добавляют. Без активной цепочки — KEEP.
     */
    suspend fun signalUpload() {
        val states = workManager.getWorkInfosForUniqueWork(UPLOAD_WORK_NAME).await().map { it.state }
        when {
            states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> Unit
            WorkInfo.State.RUNNING in states -> continueUpload().await()
            else -> enqueueUpload()
        }
    }

    /**
     * Повтор после временной ошибки ручного прогона (Q-01.3): разовая цепочка с KEEP — ждущую backoff не трогает,
     * а новая стартует через первую задержку backoff WorkManager, а не сразу: немедленная попытка уже была.
     */
    fun enqueueUploadAfterFailure() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .addTag(ONE_TIME_TAG)
            .build()
        workManager.enqueueUniqueWork(UPLOAD_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Продолжение Загрузчика после прогона, остановленного бюджетом времени (A8.2), — по тем же правилам,
     * что [continueProvider]. Продолжению, как и любому разовому Загрузчику, нужна подключённая сеть.
     */
    fun continueUpload(): Operation =
        workManager.enqueueUniqueWork(UPLOAD_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, uploadRequest())

    /**
     * «Повторить» серверную стадию ручной сверки (Q-01.1): прогон Загрузчика без накопленного backoff.
     *
     * Отдельное имя, а не REPLACE разовой цепочки: ожидающий backoff или уже работающий прогон не отменяется и не
     * заменяется, гонки ENQUEUED → RUNNING нет. KEEP держит не больше одного ручного прогона. Параллельные прогоны
     * Загрузчика под разными именами уже бывают (разовый и periodic): серверную стадию сериализует stageMutex
     * [CameraCheckRepository], pre-check и upload — свои Mutex. Временную ошибку ручной прогон не повторяет сам
     * (иначе KEEP ждал бы его backoff): автоматический повтор остаётся за разовой цепочкой, которую он при
     * необходимости ставит с KEEP и задержкой первого backoff ([enqueueUploadAfterFailure]), и periodic.
     */
    fun retryUploadNow(): Operation =
        workManager.enqueueUniqueWork(UPLOAD_RETRY_WORK_NAME, ExistingWorkPolicy.KEEP, uploadRequest(manualRetry = true))

    /**
     * Продолжение в цепочке ручных прогонов (Q-01.2): серверная стадия сверки после исчерпания бюджета и
     * обычная синхронизация после отмены проверки в ERROR. Прогоны этой цепочки не уходят в backoff, поэтому
     * продолжение ждёт только текущий ручной прогон, а не backoff разовой цепочки. APPEND_OR_REPLACE, как у
     * [continueUpload]: работающий прогон не отменяется, одна регистрация добавляет один запуск.
     */
    fun continueUploadNow(): Operation =
        workManager.enqueueUniqueWork(UPLOAD_RETRY_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, uploadRequest(manualRetry = true))

    private fun uploadRequest(manualRetry: Boolean = false) = OneTimeWorkRequestBuilder<UploadWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .addTag(ONE_TIME_TAG)
        .apply { if (manualRetry) setInputData(workDataOf(UploadWorker.MANUAL_RETRY_KEY to true)) }
        .build()

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
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(TRIGGER_UPDATE_DELAY_SECONDS, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
        )
        .build()

    /**
     * Выполняющиеся прогоны частей (A7.1): RUNNING разовой задачи или periodic. Источник — база WorkManager,
     * поэтому после гибели процесса «выполняется» не залипает: WorkManager сам возвращает прерванную работу в очередь.
     */
    fun observeActivity(): Flow<SyncActivity> =
        combine(
            isRunning(PROVIDER_WORK_NAME),
            isRunning(PROVIDER_PERIODIC_WORK_NAME),
            isRunning(UPLOAD_WORK_NAME),
            isRunning(UPLOAD_PERIODIC_WORK_NAME),
            isRunning(UPLOAD_RETRY_WORK_NAME)
        ) { provider, providerPeriodic, upload, uploadPeriodic, uploadRetry ->
            SyncActivity(
                providerRunning = provider || providerPeriodic,
                uploaderRunning = upload || uploadPeriodic || uploadRetry
            )
        }.distinctUntilChanged()

    private fun isRunning(uniqueWorkName: String): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
}

/** Выполняется ли сейчас прогон Поставщика и Загрузчика — разовый или periodic. */
data class SyncActivity(
    val providerRunning: Boolean = false,
    val uploaderRunning: Boolean = false
)

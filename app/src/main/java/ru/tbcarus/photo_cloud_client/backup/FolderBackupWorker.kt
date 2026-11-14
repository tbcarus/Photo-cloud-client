package ru.tbcarus.photo_cloud_client.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject
import ru.tbcarus.photo_cloud_client.media.AutomaticBackupNetwork
import ru.tbcarus.photo_cloud_client.media.SyncClock
import ru.tbcarus.photo_cloud_client.media.WorkBudget
import kotlin.coroutines.cancellation.CancellationException

/**
 * Автоматическое резервирование выбранных папок (этап 5.3): обход SAF, папки на Server и отправка новых файлов
 * активных источников текущего аккаунта. Прогон ограничен бюджетом A8; остаток продолжает следующий прогон.
 */
@HiltWorker
class FolderBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: FolderBackupEngine,
    private val scheduler: FolderBackupScheduler,
    private val clock: SyncClock
) : CoroutineWorker(appContext, params) {
    @Inject lateinit var automaticBackupNetwork: AutomaticBackupNetwork

    companion object {
        private const val TAG = "FolderBackupWorker"
        /** false — продолжение: дочитать начатый обход и очередь, новый обход не начинать. */
        const val KEY_FULL_SCAN = "full_scan"
        const val KEY_RESULT = "result"
    }

    override suspend fun doWork(): Result {
        // Бюджет отсчитывается от начала прогона, проверка Server и обход тоже его расходуют.
        val budget = WorkBudget(clock)
        return try {
            if (::automaticBackupNetwork.isInitialized && automaticBackupNetwork.current() == null) {
                return Result.success(workDataOf(KEY_RESULT to "network_paused"))
            }
            val run = engine.run(budget, inputData.getBoolean(KEY_FULL_SCAN, true))
                ?: return Result.success(workDataOf(KEY_RESULT to "busy"))
            when {
                // Временная ошибка Server или сети: повтор WorkManager с backoff, продолжение не регистрируется.
                run.retry -> Result.retry()
                run.progress && run.remaining -> {
                    scheduler.continueRun().await()
                    Result.success(workDataOf(KEY_RESULT to "continued"))
                }
                // Без прогресса продолжения нет: бесконечной цепочки не будет, остаток ждёт periodic или события.
                else -> Result.success(workDataOf(KEY_RESULT to if (run.remaining) "idle" else "done"))
            }
        } catch (e: CancellationException) {
            // Остановка системой: начатая отправка вернётся в очередь в начале следующего прогона.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Резервирование папок завершилось исключением", e)
            Result.retry()
        }
    }
}

package ru.tbcarus.photo_cloud_client.transfer

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import ru.tbcarus.photo_cloud_client.media.SyncClock
import ru.tbcarus.photo_cloud_client.media.WorkBudget

@HiltWorker
class ManualUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: ManualUploadQueue,
    private val clock: SyncClock
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (queue.run(WorkBudget(clock))) Result.retry() else Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("ManualUploadWorker", "Ошибка ручной очереди", e)
        Result.retry()
    }
}

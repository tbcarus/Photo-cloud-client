package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Наблюдатель MediaStore (A4.2): одноразовая задача с ContentUriTrigger, которую JobScheduler запускает
 * при изменении Images даже без живого процесса. Сам ничего не сканирует и к Room/Server не обращается.
 *
 * По срабатыванию: ставит следующее ожидание и запрашивает разовый [ProviderWorker].
 */
@HiltWorker
class MediaTriggerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncScheduler: SyncScheduler
) : Worker(appContext, params) {

    private companion object {
        const val TAG = "MediaTriggerWorker"
    }

    override fun doWork(): Result {
        // Без полного доступа наблюдение прекращается: следующее ожидание не ставится.
        // Его восстановят reconcile или periodic Поставщика после выдачи разрешения.
        if (!applicationContext.hasMediaReadPermission()) {
            Log.w(TAG, "Нет полного доступа к фото — наблюдение MediaStore остановлено")
            return Result.success()
        }
        try {
            // Сначала следующее ожидание: REPLACE в одной транзакции заменяет это задание новым,
            // и новое регистрируется в JobScheduler ещё до завершения текущего. Отмена текущего
            // не мешает: после двух постановок Worker больше ничего не делает.
            syncScheduler.rearmMediaTrigger()
            syncScheduler.enqueueProvider()
        } catch (e: Exception) {
            // retry ждал бы нового изменения MediaStore; наблюдение восстановят periodic Поставщика или reconcile.
            Log.e(TAG, "Не удалось поставить следующее ожидание или Поставщика", e)
        }
        return Result.success()
    }
}

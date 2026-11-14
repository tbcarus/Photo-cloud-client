package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.util.Log
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Итог ручного повтора FAILED. */
sealed interface FailedRetryResult {
    /** Нет полного доступа к фото: FAILED не сброшены. */
    data object NoPermission : FailedRetryResult

    /** FAILED уже нет: их обработали или сбросил параллельный повтор. */
    data object NothingToRetry : FailedRetryResult

    /** [count] записей возвращены в очередь; [scheduled] — продолжение Поставщика записано в WorkManager. */
    data class Queued(val count: Int, val scheduled: Boolean) : FailedRetryResult
}

/**
 * Ручной повтор всех FAILED Camera по кнопке на Files: записи возвращаются в существующую очередь Поставщика,
 * отдельного механизма синхронизации нет. Автоматический retry (CHECKSUM_IO, лимит и cooldown) не меняется.
 */
@Singleton
class FailedRetry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaFileDao: MediaFileDao,
    private val syncScheduler: SyncScheduler
) {

    private companion object {
        const val TAG = "FailedRetry"
    }

    // Второе нажатие ждёт первое и затем не находит FAILED: продолжение ставится не больше одного раза.
    private val mutex = Mutex()

    suspend fun retryAll(): FailedRetryResult = mutex.withLock {
        // Без полного доступа файл снова упадёт с PERMISSION: сброс только сжёг бы попытку пользователя.
        if (!context.hasAnyMediaReadPermission()) return FailedRetryResult.NoPermission
        val count = mediaFileDao.resetAllFailedForManualRetry()
        if (count == 0) return FailedRetryResult.NothingToRetry
        // Записи уже PENDING в Room. Продолжение, а не KEEP: работающий Поставщик не заменяется, и даже если он
        // уже прошёл выборку PENDING, следующий прогон начнётся после него. Без постановки записи подхватят
        // старт процесса и periodic Поставщика.
        val scheduled = try {
            syncScheduler.continueProvider().await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Продолжение Поставщика не записано: ${e.message}")
            false
        }
        FailedRetryResult.Queued(count, scheduled)
    }
}

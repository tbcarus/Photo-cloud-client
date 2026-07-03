package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Наблюдает за изменениями в MediaStore Images и запускает one-time sync с debounce.
 *
 * Изменение в галерее → debounce 3 c → [SyncScheduler.enqueueOneTimeSync].
 * Periodic sync остаётся fallback (см. [PeriodicSyncCoordinator]).
 *
 * Жизненный цикл управляется координатором ([PeriodicSyncCoordinator]), а не напрямую из App.
 */
@Singleton
class MediaChangeObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncScheduler: SyncScheduler
) {
    private companion object {
        const val TAG = "MediaChangeObserver"
        const val DEBOUNCE_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounceJob: Job? = null
    private var registered = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onMediaChanged()
        }
    }

    fun start() {
        if (registered) return
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer
        )
        registered = true
        Log.i(TAG, "MediaChangeObserver зарегистрирован")
    }

    fun stop() {
        if (!registered) return
        context.contentResolver.unregisterContentObserver(observer)
        debounceJob?.cancel()
        registered = false
        Log.i(TAG, "MediaChangeObserver остановлен")
    }

    private fun onMediaChanged() {
        // MediaStore может прислать несколько событий на одно фото, поэтому схлопываем их в один sync.
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            syncScheduler.enqueueOneTimeSync()
        }
    }
}

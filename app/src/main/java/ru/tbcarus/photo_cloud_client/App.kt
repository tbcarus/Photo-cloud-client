package ru.tbcarus.photo_cloud_client

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import ru.tbcarus.photo_cloud_client.media.ProcessStartSync
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var processStartSync: ProcessStartSync

    override fun onCreate() {
        // Hilt внедряет зависимости в super.onCreate(), до этого поля не инициализированы.
        super.onCreate()
        // Холодный старт: событие MediaStore, поднявшее процесс, могло быть отменено WorkManager (A4.3).
        processStartSync.onProcessStart()
    }

    // HiltWorkerFactory нужен, чтобы WorkManager умел инжектить зависимости в @HiltWorker.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}

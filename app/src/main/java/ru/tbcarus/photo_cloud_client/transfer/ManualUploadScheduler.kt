package ru.tbcarus.photo_cloud_client.transfer

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.backup.BackupAccountProvider
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Собственная работа Library; настройка сети Camera/SAF к ней не применяется. */
@Singleton
class ManualUploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: BackupAccountProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val manager get() = WorkManager.getInstance(context)

    fun enqueue() {
        manager.enqueueUniqueWork("manual_library_upload", ExistingWorkPolicy.KEEP,
            request())
    }

    fun start() {
        periodic()
        scope.launch {
            accounts.observe().collect { account ->
                if (account != null) manager.enqueueUniqueWork("manual_library_upload", ExistingWorkPolicy.REPLACE, request())
            }
        }
    }

    fun periodic() {
        manager.enqueueUniquePeriodicWork("manual_library_upload_periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ManualUploadWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
    }

    private fun request() = OneTimeWorkRequestBuilder<ManualUploadWorker>()
        .setConstraints(constraints).build()
}

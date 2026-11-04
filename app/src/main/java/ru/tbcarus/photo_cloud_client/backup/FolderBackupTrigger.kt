package ru.tbcarus.photo_cloud_client.backup

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Держит задачи резервирования папок в согласии с аккаунтом: старт процесса, вход и смена Server.
 * Есть активные источники текущего аккаунта — periodic включён и ставится разовый прогон (KEEP); иначе periodic отменён.
 * Регистрации источников не выполняет.
 */
@Singleton
class FolderBackupTrigger @Inject constructor(
    private val accounts: BackupAccountProvider,
    private val sources: BackupSourceDao,
    private val scheduler: FolderBackupScheduler
) {
    private companion object {
        const val TAG = "FolderBackupTrigger"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            // Процесс без UI: адрес Server восстанавливается из сохранённых настроек.
            accounts.current()
            accounts.observe().collect { account -> reconcile(account) }
        }
    }

    internal suspend fun reconcile(account: BackupAccount?) {
        val active = account?.let { sources.listActive(it.serverUrl, it.accountId).size } ?: 0
        Log.i(TAG, "Активных источников текущего аккаунта: $active (сервер ${account?.serverUrl ?: "не задан"})")
        if (active > 0) {
            scheduler.enqueuePeriodic()
            scheduler.enqueue()
        } else {
            scheduler.cancelPeriodic()
        }
    }
}

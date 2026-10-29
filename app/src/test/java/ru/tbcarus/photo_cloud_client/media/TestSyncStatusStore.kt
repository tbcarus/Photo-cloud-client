package ru.tbcarus.photo_cloud_client.media

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Новый файл DataStore для итогов прогонов: состояние не переходит между тестами. */
fun newSyncStatusFile(): File = File.createTempFile("sync_status", ".preferences_pb").also { it.delete() }

/**
 * [SyncStatusStore] над файлом [file]. Один файл в процессе может обслуживать только один активный DataStore:
 * чтобы открыть его заново («перезапуск процесса»), предыдущий [scope] нужно отменить.
 */
fun testSyncStatusStore(
    file: File = newSyncStatusFile(),
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    wallClock: () -> Long = System::currentTimeMillis
): SyncStatusStore = SyncStatusStore(PreferenceDataStoreFactory.create(scope = scope) { file }, wallClock)

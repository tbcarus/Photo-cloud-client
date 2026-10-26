package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Разовый Поставщик при каждом старте процесса (A4.3).
 *
 * После `am kill` или смахивания из недавних WorkManager 2.9 (ForceStopRunnable) при холодной
 * инициализации принимает завершение за force-stop и отменяет все job приложения — в том числе job
 * наблюдателя MediaStore, который и поднял процесс. Изменение, разбудившее процесс, подхватит этот прогон.
 */
@Singleton
class ProcessStartSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncScheduler: SyncScheduler
) {
    fun onProcessStart() {
        // Без полного доступа Поставщик ничего не обработает — не планируем.
        if (!context.hasMediaReadPermission()) return
        // Постановка попадает в ту же последовательную очередь WorkManager, где при инициализации первым
        // стоит ForceStopRunnable, поэтому выполняется уже после его отмены и перепланирования job.
        // KEEP: ожидающий или выполняющийся Поставщик не дублируется.
        syncScheduler.enqueueProvider()
    }
}

package ru.tbcarus.photo_cloud_client.media

import android.os.SystemClock

/** Монотонные часы в миллисекундах; в тестах подменяются, чтобы не ждать реальных минут. */
fun interface SyncClock {
    fun nowMs(): Long

    companion object {
        /** Время с загрузки устройства: не зависит от перевода системных часов. */
        val SYSTEM = SyncClock { SystemClock.elapsedRealtime() }
    }
}

/**
 * Бюджет времени одного прогона Worker. Проверяется перед началом очередной операции;
 * начатую операцию не прерывает.
 */
class WorkBudget(
    private val clock: SyncClock,
    private val durationMs: Long = SyncConfig.WORKER_TIME_BUDGET_MS
) {
    private val startedAtMs = clock.nowMs()

    fun isExhausted(): Boolean = clock.nowMs() - startedAtMs >= durationMs

    companion object {
        /** Без ограничения — для вызовов вне Worker. */
        val UNLIMITED = WorkBudget(SyncClock { 0L }, Long.MAX_VALUE)
    }
}

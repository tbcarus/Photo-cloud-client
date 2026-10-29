package ru.tbcarus.photo_cloud_client.ui.screens.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tbcarus.photo_cloud_client.media.BackupIssue
import ru.tbcarus.photo_cloud_client.media.BackupIssueKind
import ru.tbcarus.photo_cloud_client.media.BackupPhase
import ru.tbcarus.photo_cloud_client.media.BackupState
import ru.tbcarus.photo_cloud_client.media.MediaFileCounters
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Тексты карточки состояния backup на Files (A7.2). */
class BackupStateTextTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val now = at(2026, 10, 29, 18, 30)
    private val counters = MediaFileCounters(total = 12, synced = 7, preparing = 1, toUpload = 2, failed = 2)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun text(kind: BackupIssueKind, since: Long? = null) =
        backupIssueText(BackupIssue(kind, since), counters) { formatBackupTime(it, now, zone) }

    private fun state(phase: BackupPhase, counters: MediaFileCounters = this.counters) =
        BackupState(phase, emptyList(), isRunning = false, counters = counters, lastScanAt = null, lastUploadAt = null)

    @Test
    fun missingAddressAndUnavailableServerAreDifferentMessages() {
        val noAddress = text(BackupIssueKind.NO_SERVER_ADDRESS)
        val unavailable = text(BackupIssueKind.SERVER_UNAVAILABLE, since = at(2026, 10, 29, 14, 5))

        assertNotEquals(noAddress, unavailable)
        assertTrue(noAddress.contains("Network"))
        assertTrue(unavailable.contains("с сегодня, 14:05"))
        assertTrue(unavailable.contains("автоматически"))
    }

    @Test
    fun failedFilesMessageShowsCount() {
        assertTrue(text(BackupIssueKind.FILES_FAILED).contains("2"))
    }

    @Test
    fun everyIssueHasText() {
        BackupIssueKind.entries.forEach { assertFalse(text(it).isBlank()) }
    }

    @Test
    fun titlesFollowPhase() {
        assertEquals("Все обнаруженные фото сохранены", backupTitle(state(BackupPhase.SYNCED)))
        assertEquals("Ожидает продолжения", backupTitle(state(BackupPhase.WAITING)))
        // Пустая очередь без проверки фото — ожидание первой проверки, а не продолжения.
        assertEquals("Ожидает проверки фотографий", backupTitle(state(BackupPhase.WAITING, MediaFileCounters())))
        assertEquals("Требуется внимание", backupTitle(state(BackupPhase.ATTENTION_REQUIRED)))
        assertEquals("Выполняется резервное копирование", backupTitle(state(BackupPhase.RUNNING)))
    }

    @Test
    fun countersShowFiveValuesWithoutPercent() {
        val lines = backupCountersText(counters)

        assertEquals(
            listOf("Всего: 12 · Сохранено: 7 · Ошибки: 2", "Подготовка: 1 · К отправке: 2"),
            lines
        )
        assertTrue(lines.none { it.contains("%") })
    }

    @Test
    fun timeOfOtherDayIncludesDate() {
        assertEquals("сегодня, 09:15", formatBackupTime(at(2026, 10, 29, 9, 15), now, zone))
        assertEquals("28.10.2026 23:59", formatBackupTime(at(2026, 10, 28, 23, 59), now, zone))
    }
}

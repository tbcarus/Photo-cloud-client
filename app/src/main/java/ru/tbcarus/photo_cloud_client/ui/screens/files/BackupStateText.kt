package ru.tbcarus.photo_cloud_client.ui.screens.files

import ru.tbcarus.photo_cloud_client.media.BackupIssue
import ru.tbcarus.photo_cloud_client.media.BackupIssueKind
import ru.tbcarus.photo_cloud_client.media.BackupPhase
import ru.tbcarus.photo_cloud_client.media.BackupState
import ru.tbcarus.photo_cloud_client.media.MediaFileCounters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Тексты карточки состояния backup на Files (A7.2). Без Compose — проверяются JVM-тестами.

fun backupTitle(state: BackupState): String = when (state.phase) {
    BackupPhase.RUNNING -> "Выполняется резервное копирование"
    BackupPhase.WAITING ->
        if (state.counters.preparing + state.counters.toUpload > 0) "Ожидает продолжения" else "Ожидает проверки фотографий"
    BackupPhase.ATTENTION_REQUIRED -> "Требуется внимание"
    BackupPhase.SYNCED -> "Все обнаруженные фото сохранены"
}

fun backupIssueText(issue: BackupIssue, counters: MediaFileCounters, formatTime: (Long) -> String): String {
    val since = issue.since?.let { " (с ${formatTime(it)})" }.orEmpty()
    return when (issue.kind) {
        BackupIssueKind.NO_MEDIA_PERMISSION ->
            "Нет доступа ко всем фотографиям — резервное копирование невозможно. Разрешение предоставляется на экране Settings."
        BackupIssueKind.NOT_SIGNED_IN ->
            "Вход не выполнен: фото готовятся, но не отправляются на сервер. Войдите на экране Login."
        BackupIssueKind.NO_SERVER_ADDRESS ->
            "Адрес сервера не задан. Укажите его на экране Network."
        BackupIssueKind.SCAN_FAILED ->
            "Не удалось проверить фотографии$since. Повтор будет выполнен автоматически."
        BackupIssueKind.SCAN_PENDING ->
            "Фотографии ещё не проверялись."
        BackupIssueKind.SERVER_UNAVAILABLE ->
            "Сервер не отвечает$since. Повтор будет выполнен автоматически."
        BackupIssueKind.UPLOAD_FAILED ->
            "Ошибка отправки на сервер$since. Повтор будет выполнен автоматически."
        BackupIssueKind.FILES_FAILED ->
            "Не удалось сохранить фото: ${counters.failed}. Причины — в списке ниже."
    }
}

/** Пять показателей индекса в две строки; без процентов. */
fun backupCountersText(counters: MediaFileCounters): List<String> = listOf(
    "Всего: ${counters.total} · Сохранено: ${counters.synced} · Ошибки: ${counters.failed}",
    "Подготовка: ${counters.preparing} · К отправке: ${counters.toUpload}"
)

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/** Время по часам устройства: сегодняшнее — только часы и минуты. */
fun formatBackupTime(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (time.toLocalDate() == today) "сегодня, ${TIME.format(time)}" else DATE_TIME.format(time)
}

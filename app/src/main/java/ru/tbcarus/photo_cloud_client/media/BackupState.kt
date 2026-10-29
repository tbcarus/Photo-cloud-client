package ru.tbcarus.photo_cloud_client.media

/** Основное пользовательское состояние резервного копирования (A7.1). */
enum class BackupPhase {
    /** Выполняется прогон Поставщика или Загрузчика — разовый или periodic. */
    RUNNING,

    /** Есть необработанные фото; их обработает продолжение, повтор, событие или periodic. */
    WAITING,

    /** Продолжение невозможно без пользователя либо есть ошибки, при которых нельзя сказать «всё сохранено». */
    ATTENTION_REQUIRED,

    /** Успешная проверка фото состоялась, все обнаруженные фото сохранены, ошибок нет. */
    SYNCED
}

enum class BackupIssueKind {
    /** Нет полного доступа к фото — по актуальному разрешению Android. */
    NO_MEDIA_PERMISSION,

    /** Нет сохранённой пары — по актуальному хранилищу токенов. */
    NOT_SIGNED_IN,

    /** Адрес Server не задан или некорректен — по сохранённым настройкам. */
    NO_SERVER_ADDRESS,

    /** Последний прогон Поставщика завершился ошибкой scan или hash. */
    SCAN_FAILED,

    /** Успешной проверки фото ещё не было: пустой индекс не означает, что фото нет. */
    SCAN_PENDING,

    /** Последний прогон Загрузчика не получил ответ Server на ping. */
    SERVER_UNAVAILABLE,

    /** Последний прогон Загрузчика остановлен временной ошибкой pre-check или upload. */
    UPLOAD_FAILED,

    /** Есть FAILED-строки. */
    FILES_FAILED
}

/** Причина состояния; [since] — время возникновения для сохранённых итогов прогонов, иначе null. */
data class BackupIssue(val kind: BackupIssueKind, val since: Long? = null)

data class BackupState(
    val phase: BackupPhase,
    /** Все актуальные причины по убыванию важности; могут сопровождать любую фазу, в том числе RUNNING. */
    val issues: List<BackupIssue>,
    /** Выполняется прогон — и при ATTENTION_REQUIRED: Поставщик работает и без входа. */
    val isRunning: Boolean,
    val counters: MediaFileCounters,
    val lastScanAt: Long?,
    val lastUploadAt: Long?
)

/**
 * Состояние backup из текущих источников: счётчики Room, RUNNING WorkManager, актуальные разрешение, пара и адрес,
 * сохранённые итоги прогонов. Сохранённая проблема — последний известный итог части, а не подтверждённое
 * текущее состояние, поэтому показывается, только пока может объяснять происходящее: часть сейчас не выполняется,
 * актуальные условия её прогона выполнены и (для Загрузчика) есть что отправлять. Успех прогона Worker сам по себе
 * «всё сохранено» не означает — это определяют только счётчики.
 */
fun backupStateOf(
    counters: MediaFileCounters,
    activity: SyncActivity,
    hasMediaAccess: Boolean,
    isSignedIn: Boolean,
    hasServerAddress: Boolean,
    results: SyncRunResults
): BackupState {
    val blocking = buildList {
        if (!hasMediaAccess) add(BackupIssue(BackupIssueKind.NO_MEDIA_PERMISSION))
        if (!isSignedIn) add(BackupIssue(BackupIssueKind.NOT_SIGNED_IN))
        if (!hasServerAddress) add(BackupIssue(BackupIssueKind.NO_SERVER_ADDRESS))
    }
    // Без доступа Поставщик не выполняет scan: прежняя ошибка ничего не объясняет.
    val scanIssue = results.providerProblem
        ?.takeIf { hasMediaAccess && !activity.providerRunning }
        ?.let { BackupIssue(BackupIssueKind.SCAN_FAILED, it.since) }
    // Без доступа, пары или адреса Загрузчик до Server не доходит — причина в них, а не в прежней ошибке.
    val uploadIssue = results.uploaderProblem
        ?.takeIf { blocking.isEmpty() && !activity.uploaderRunning && counters.toUpload > 0 }
        ?.let {
            val kind = if (it.status == SyncStatus.SERVER_UNAVAILABLE) {
                BackupIssueKind.SERVER_UNAVAILABLE
            } else {
                BackupIssueKind.UPLOAD_FAILED
            }
            BackupIssue(kind, it.since)
        }
    val filesIssue = if (counters.failed > 0) BackupIssue(BackupIssueKind.FILES_FAILED) else null
    // Первая проверка ещё впереди; во время прогона Поставщика или при его ошибке это уже видно из них.
    val scanPending = if (
        results.lastScanAt == null && results.providerProblem == null && hasMediaAccess && !activity.providerRunning
    ) BackupIssue(BackupIssueKind.SCAN_PENDING) else null

    val isRunning = activity.providerRunning || activity.uploaderRunning
    val phase = when {
        blocking.isNotEmpty() -> BackupPhase.ATTENTION_REQUIRED
        isRunning -> BackupPhase.RUNNING
        counters.preparing + counters.toUpload > 0 -> BackupPhase.WAITING
        // Ошибка Поставщика видна и при пустой очереди: новые фото могли быть не обнаружены.
        scanIssue != null || filesIssue != null -> BackupPhase.ATTENTION_REQUIRED
        // Без успешной проверки пустая очередь не означает «всё сохранено».
        results.lastScanAt == null -> BackupPhase.WAITING
        else -> BackupPhase.SYNCED
    }
    return BackupState(
        phase = phase,
        issues = blocking + listOfNotNull(scanIssue, scanPending, uploadIssue, filesIssue),
        isRunning = isRunning,
        counters = counters,
        lastScanAt = results.lastScanAt,
        lastUploadAt = results.lastUploadAt
    )
}

package ru.tbcarus.photo_cloud_client.media

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ручная сверка Camera с Server (Q-01): единственная строка — текущая или последняя проверка.
 * Пока строка не в [CameraCheckState.FINISHED], новые Camera upload приостановлены.
 */
@Entity(tableName = "camera_check")
data class CameraCheck(
    @PrimaryKey val id: Int = SINGLE_ID,
    /** Идентификатор запуска: поздний результат отменённой или перезапущенной проверки не применяется. */
    val runId: String,
    val state: CameraCheckState,
    val stage: CameraCheckStage,
    val error: CameraCheckError? = null,
    /** Назначение проверки: адрес Server и subject пользователя на момент запуска. */
    val serverUrl: String,
    val accountId: String,
    /** CAMERA, по которой получены ответы Server; null — серверная стадия ещё не начиналась. */
    val cameraFolderId: Long? = null,
    val startedAt: Long,
    /** «Позже»: результат свёрнут, пауза сохраняется. */
    val deferred: Boolean = false,
    val finishedAt: Long? = null,
    // Итог фиксируется при завершении серверной стадии; набор файлов после решения удаляется.
    val checked: Int = 0,
    val confirmed: Int = 0,
    val missing: Int = 0,
    val unverifiable: Int = 0,
    /** Сколько отсутствующих файлов возвращено в обычную синхронизацию по подтверждению; null — подтверждения не было. */
    val queuedForUpload: Int? = null
) {
    val isActive: Boolean get() = state == CameraCheckState.RUNNING || state == CameraCheckState.ERROR

    companion object {
        const val SINGLE_ID = 1
    }
}

enum class CameraCheckState {
    /** Выполняется стадия [CameraCheck.stage]. */
    RUNNING,

    /** Стадия остановлена ошибкой [CameraCheck.error]; продолжится с того же места. */
    ERROR,

    /** Есть файлы, не найденные на Server: ждёт «Загрузить» или «Отказаться». */
    AWAITING_DECISION,

    /** Ручной режим завершён, пауза снята; строка хранит итог для показа. */
    FINISHED
}

enum class CameraCheckStage { SCAN, HASH, SERVER, DONE }

enum class CameraCheckError {
    NO_MEDIA_PERMISSION,
    /** Снимок MediaStore неполный — сверка по нему не начинается. */
    SCAN_INCOMPLETE,
    SCAN_FAILED,
    NOT_SIGNED_IN,
    NO_SERVER_ADDRESS,
    SERVER_UNAVAILABLE,
    SERVER_ERROR,
    AUTH,
    BAD_RESPONSE,
    /** Сменились Server, аккаунт или CAMERA: прежние ответы к текущему назначению не применяются. */
    DESTINATION_CHANGED
}

/** Строка набора проверки: версия файла и checksum, по которым спрашивается Server. */
@Entity(tableName = "camera_check_items")
data class CameraCheckItem(
    @PrimaryKey val mediaStoreId: Long,
    val size: Long,
    val lastModified: Long,
    /** null — checksum ещё не получен. */
    val checksum: String?,
    /** null — достоверного результата ещё нет. */
    val result: CameraCheckResult? = null
)

enum class CameraCheckResult {
    EXISTS,
    MISSING,
    /** Файл не прочитан или изменился во время проверки: отсутствующим не считается. */
    UNVERIFIABLE
}

/** Счётчики набора проверки одной выборкой. */
data class CameraCheckCounts(
    val total: Int = 0,
    /** Есть checksum или итог без него — локальная подготовка завершена. */
    val prepared: Int = 0,
    val withChecksum: Int = 0,
    /** С checksum и с результатом: Server ответил или файл исключён при применении ответа. */
    val answered: Int = 0,
    val confirmed: Int = 0,
    val missing: Int = 0,
    val unverifiable: Int = 0
)

/** Состояние проверки для UI. */
data class CameraCheckView(val check: CameraCheck, val counts: CameraCheckCounts)

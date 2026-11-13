package ru.tbcarus.photo_cloud_client.ui.screens.files

import ru.tbcarus.photo_cloud_client.media.CameraCheckError
import ru.tbcarus.photo_cloud_client.media.CameraCheckStage
import ru.tbcarus.photo_cloud_client.media.CameraCheckState
import ru.tbcarus.photo_cloud_client.media.CameraCheckView

// Тексты карточки «Проверка копий» на Files (Q-01). Без Compose — проверяются JVM-тестами.

/** Прогресс стадии; [total] = null — объём неизвестен, показывается неопределённый индикатор. */
data class CheckProgress(val step: Int, val stageName: String, val done: Int, val total: Int?) {
    val percent: Int? get() = total?.let { if (it == 0) 100 else done * 100 / it }
}

const val CHECK_STEPS = 4

/** Прогресс выполняющейся или остановленной ошибкой проверки — по фактически завершённой работе. */
fun checkProgress(view: CameraCheckView): CheckProgress {
    val counts = view.counts
    return when (view.check.stage) {
        CameraCheckStage.SCAN -> CheckProgress(1, "Сканирование Camera", 0, null)
        CameraCheckStage.HASH -> CheckProgress(2, "Вычисление checksum", counts.prepared, counts.total)
        CameraCheckStage.SERVER ->
            if (counts.withChecksum > 0 && counts.answered == counts.withChecksum) {
                CheckProgress(4, "Завершение", 0, null)
            } else {
                CheckProgress(3, "Сверка с Server", counts.answered, counts.withChecksum)
            }
        CameraCheckStage.DONE -> CheckProgress(4, "Завершение", 0, null)
    }
}

fun checkProgressText(progress: CheckProgress): String {
    val head = "Шаг ${progress.step} из $CHECK_STEPS: ${progress.stageName}"
    val total = progress.total ?: return head
    return "$head — ${progress.done} / $total (${progress.percent}%)"
}

fun checkErrorText(error: CameraCheckError?): String = when (error) {
    CameraCheckError.NO_MEDIA_PERMISSION ->
        "Нет полного доступа к фото. Выдайте доступ на экране Settings — проверка продолжится."
    CameraCheckError.SCAN_INCOMPLETE ->
        "Не удалось прочитать список фото полностью. По неполному списку проверка не выполняется."
    CameraCheckError.SCAN_FAILED -> "Не удалось просканировать фото."
    CameraCheckError.NOT_SIGNED_IN -> "Вход не выполнен. Войдите на экране Login — проверка продолжится."
    CameraCheckError.NO_SERVER_ADDRESS -> "Адрес сервера не задан. Укажите его на экране Network."
    CameraCheckError.SERVER_UNAVAILABLE -> "Сервер не отвечает."
    CameraCheckError.SERVER_ERROR -> "Сервер вернул ошибку."
    CameraCheckError.AUTH -> "Сервер отклонил авторизацию."
    CameraCheckError.BAD_RESPONSE -> "Сервер вернул неполный ответ."
    CameraCheckError.DESTINATION_CHANGED ->
        "Сменились сервер, аккаунт или папка Camera. Прежние ответы к ним не относятся — нужна новая проверка."
    null -> "Проверка остановлена."
}

/** Итог проверки: проверено, подтверждено, не найдено, не удалось проверить. */
fun checkSummaryLines(view: CameraCheckView): List<String> {
    val check = view.check
    // До решения итог читается из набора, после — из сохранённых счётчиков.
    val counts = view.counts
    val fromItems = check.state == CameraCheckState.AWAITING_DECISION && counts.total > 0
    val checked = if (fromItems) counts.total else check.checked
    val confirmed = if (fromItems) counts.confirmed else check.confirmed
    val missing = if (fromItems) counts.missing else check.missing
    val unverifiable = if (fromItems) counts.unverifiable else check.unverifiable
    return listOf(
        "Проверено файлов: $checked",
        "Подтверждено на сервере: $confirmed",
        "Не найдено на сервере: $missing",
        "Не удалось проверить: $unverifiable"
    )
}

fun checkMissingCount(view: CameraCheckView): Int =
    if (view.check.state == CameraCheckState.AWAITING_DECISION) view.counts.missing else view.check.missing

/** «1 файл», «2 файла», «5 файлов», «11 файлов», «21 файл». */
fun filesCount(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "файлов"
        n % 10 == 1 -> "файл"
        n % 10 in 2..4 -> "файла"
        else -> "файлов"
    }
    return "$n $word"
}

fun checkUploadButtonText(missing: Int): String = "Загрузить ${filesCount(missing)}"

/** Свёрнутый результат после «Позже». */
fun checkDeferredText(missing: Int): String {
    val verb = if (missing % 10 == 1 && missing % 100 != 11) "ждёт" else "ждут"
    return "Автоматическая загрузка Camera приостановлена: ${filesCount(missing)} $verb решения."
}

/** Итоговая строка завершённой проверки. */
fun checkFinishedText(view: CameraCheckView): String {
    val queued = view.check.queuedForUpload
    return when {
        queued != null -> "Отсутствующие файлы возвращены в обычную синхронизацию: $queued. Автоматическая загрузка возобновлена."
        view.check.checked == 0 -> "Нет фотографий для проверки."
        view.check.unverifiable > 0 -> "Отсутствующих на сервере файлов не найдено. Часть файлов проверить не удалось."
        else -> "Все проверенные файлы есть на сервере."
    }
}

const val CHECK_PAUSE_TEXT = "Автоматическая загрузка Camera приостановлена до завершения проверки и вашего решения."
const val CHECK_CANCEL_HINT =
    "Отмена вернёт обычную автоматическую синхронизацию — она может загрузить недостающие файлы."
const val CHECK_SCOPE_HINT =
    "Сервер подтверждает запись о файле в Camera по checksum; целостность хранимых байтов не проверяется."

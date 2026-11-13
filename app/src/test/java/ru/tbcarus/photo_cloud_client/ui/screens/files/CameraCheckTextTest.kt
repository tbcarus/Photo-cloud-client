package ru.tbcarus.photo_cloud_client.ui.screens.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.tbcarus.photo_cloud_client.media.CameraCheck
import ru.tbcarus.photo_cloud_client.media.CameraCheckCounts
import ru.tbcarus.photo_cloud_client.media.CameraCheckStage
import ru.tbcarus.photo_cloud_client.media.CameraCheckState
import ru.tbcarus.photo_cloud_client.media.CameraCheckView

/** Прогресс и итог карточки «Проверка копий»: проценты только при известном объёме стадии. */
class CameraCheckTextTest {

    private fun view(
        stage: CameraCheckStage,
        counts: CameraCheckCounts = CameraCheckCounts(),
        state: CameraCheckState = CameraCheckState.RUNNING
    ) = CameraCheckView(
        CameraCheck(runId = "r", state = state, stage = stage, serverUrl = "u", accountId = "a", startedAt = 1),
        counts
    )

    @Test
    fun scanHasNoInventedPercent() {
        val progress = checkProgress(view(CameraCheckStage.SCAN))
        assertNull(progress.total)
        assertNull(progress.percent)
        assertEquals("Шаг 1 из 4: Сканирование Camera", checkProgressText(progress))
    }

    @Test
    fun hashAndServerStagesCountFinishedWork() {
        val hash = checkProgress(view(CameraCheckStage.HASH, CameraCheckCounts(total = 8, prepared = 2)))
        assertEquals("Шаг 2 из 4: Вычисление checksum — 2 / 8 (25%)", checkProgressText(hash))

        // Сверяются только строки с checksum; нечитаемые учтены отдельно.
        val server = checkProgress(view(CameraCheckStage.SERVER, CameraCheckCounts(total = 8, withChecksum = 7, answered = 0)))
        assertEquals("Шаг 3 из 4: Сверка с Server — 0 / 7 (0%)", checkProgressText(server))

        val finishing = checkProgress(view(CameraCheckStage.SERVER, CameraCheckCounts(total = 8, withChecksum = 7, answered = 7)))
        assertEquals("Шаг 4 из 4: Завершение", checkProgressText(finishing))
    }

    @Test
    fun fileCountsAgreeWithNumber() {
        assertEquals(
            listOf("1 файл", "2 файла", "4 файла", "5 файлов", "11 файлов", "12 файлов", "21 файл", "22 файла", "100 файлов", "111 файлов"),
            listOf(1, 2, 4, 5, 11, 12, 21, 22, 100, 111).map(::filesCount)
        )
        assertEquals("Загрузить 1 файл", checkUploadButtonText(1))
        assertEquals("Загрузить 3 файла", checkUploadButtonText(3))
        assertEquals("Загрузить 5 файлов", checkUploadButtonText(5))
        assertEquals("Автоматическая загрузка Camera приостановлена: 1 файл ждёт решения.", checkDeferredText(1))
        assertEquals("Автоматическая загрузка Camera приостановлена: 11 файлов ждут решения.", checkDeferredText(11))
        assertEquals("Автоматическая загрузка Camera приостановлена: 2 файла ждут решения.", checkDeferredText(2))
    }

    @Test
    fun summaryReadsItemsBeforeDecisionAndSavedCountersAfter() {
        val awaiting = view(
            CameraCheckStage.DONE,
            CameraCheckCounts(total = 8, confirmed = 3, missing = 4, unverifiable = 1),
            CameraCheckState.AWAITING_DECISION
        )
        assertEquals(
            listOf("Проверено файлов: 8", "Подтверждено на сервере: 3", "Не найдено на сервере: 4", "Не удалось проверить: 1"),
            checkSummaryLines(awaiting)
        )
        assertEquals(4, checkMissingCount(awaiting))

        val finished = CameraCheckView(
            awaiting.check.copy(state = CameraCheckState.FINISHED, checked = 8, confirmed = 3, missing = 4, unverifiable = 1, queuedForUpload = 4),
            CameraCheckCounts()
        )
        assertEquals(checkSummaryLines(awaiting), checkSummaryLines(finished))
        assertEquals("Все проверенные файлы есть на сервере.", checkFinishedText(finished.copy(check = finished.check.copy(queuedForUpload = null, missing = 0, unverifiable = 0))))
        assertEquals("Нет фотографий для проверки.", checkFinishedText(finished.copy(check = finished.check.copy(queuedForUpload = null, checked = 0, confirmed = 0, missing = 0, unverifiable = 0))))
        assertEquals(
            "Отсутствующие файлы возвращены в обычную синхронизацию: 4. Автоматическая загрузка возобновлена.",
            checkFinishedText(finished)
        )
    }
}

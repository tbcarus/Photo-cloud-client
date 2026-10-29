package ru.tbcarus.photo_cloud_client.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tbcarus.photo_cloud_client.media.BackupIssueKind.FILES_FAILED
import ru.tbcarus.photo_cloud_client.media.BackupIssueKind.NOT_SIGNED_IN
import ru.tbcarus.photo_cloud_client.media.BackupIssueKind.NO_MEDIA_PERMISSION
import ru.tbcarus.photo_cloud_client.media.BackupIssueKind.NO_SERVER_ADDRESS
import ru.tbcarus.photo_cloud_client.media.BackupIssueKind.SCAN_FAILED
import ru.tbcarus.photo_cloud_client.media.BackupIssueKind.SCAN_PENDING
import ru.tbcarus.photo_cloud_client.media.BackupIssueKind.SERVER_UNAVAILABLE
import ru.tbcarus.photo_cloud_client.media.BackupIssueKind.UPLOAD_FAILED

/** Пользовательское состояние backup (A7.1): фазы, приоритеты и актуальность сохранённых проблем. */
class BackupStateTest {

    private val synced = MediaFileCounters(total = 10, synced = 10)
    private val toUpload = MediaFileCounters(total = 10, synced = 7, toUpload = 3)
    private val preparing = MediaFileCounters(total = 10, synced = 8, preparing = 2)
    // Успешная проверка фото уже была — кроме тестов первой проверки.
    private val scanned = SyncRunResults(lastScanAt = 1)
    private val serverDown = scanned.copy(uploaderProblem = SyncProblem(SyncStatus.SERVER_UNAVAILABLE, 500))
    private val scanError = scanned.copy(providerProblem = SyncProblem(SyncStatus.ERROR, 700))

    private fun state(
        counters: MediaFileCounters = synced,
        activity: SyncActivity = SyncActivity(),
        hasMediaAccess: Boolean = true,
        isSignedIn: Boolean = true,
        hasServerAddress: Boolean = true,
        results: SyncRunResults = scanned
    ) = backupStateOf(counters, activity, hasMediaAccess, isSignedIn, hasServerAddress, results)

    private fun BackupState.kinds() = issues.map { it.kind }

    @Test
    fun allDiscoveredPhotosSavedWithoutProblems() {
        val state = state(results = SyncRunResults(lastScanAt = 1, lastUploadAt = 2))

        assertEquals(BackupPhase.SYNCED, state.phase)
        assertTrue(state.issues.isEmpty())
        assertEquals(1L, state.lastScanAt)
        assertEquals(2L, state.lastUploadAt)
    }

    @Test
    fun emptyIndexAfterSuccessfulScanIsSynced() {
        assertEquals(BackupPhase.SYNCED, state(counters = MediaFileCounters()).phase)
    }

    @Test
    fun emptyIndexBeforeFirstScanIsNotSynced() {
        val state = state(counters = MediaFileCounters(), results = SyncRunResults())

        assertEquals(BackupPhase.WAITING, state.phase)
        assertEquals(listOf(SCAN_PENDING), state.kinds())
    }

    @Test
    fun savedPhotosWithoutRecordedScanAreNotReportedAsSynced() {
        // Например, строки из прошлых сборок без сохранённого итога Поставщика.
        assertEquals(BackupPhase.WAITING, state(results = SyncRunResults()).phase)
    }

    @Test
    fun pendingFirstScanIsHiddenWhenExplainedOtherwise() {
        val first = SyncRunResults()
        val running = state(counters = MediaFileCounters(), activity = SyncActivity(providerRunning = true), results = first)
        assertEquals(BackupPhase.RUNNING, running.phase)
        assertTrue(running.issues.isEmpty())

        val failed = state(
            counters = MediaFileCounters(),
            results = SyncRunResults(providerProblem = SyncProblem(SyncStatus.ERROR, 1))
        )
        assertEquals(listOf(SCAN_FAILED), failed.kinds())

        val noAccess = state(counters = MediaFileCounters(), hasMediaAccess = false, results = first)
        assertEquals(listOf(NO_MEDIA_PERMISSION), noAccess.kinds())
    }

    @Test
    fun failedFilesNeverGiveAllSaved() {
        val state = state(counters = MediaFileCounters(total = 10, synced = 8, failed = 2))

        assertEquals(BackupPhase.ATTENTION_REQUIRED, state.phase)
        assertEquals(listOf(FILES_FAILED), state.kinds())
    }

    @Test
    fun queueLeftAfterSuccessfulWorkerIsWaiting() {
        // Прогон завершился успешно (проблемы нет), но очередь осталась — например, бюджет A8.
        assertEquals(BackupPhase.WAITING, state(counters = toUpload).phase)
        assertEquals(BackupPhase.WAITING, state(counters = preparing).phase)
        assertTrue(state(counters = toUpload).issues.isEmpty())
    }

    @Test
    fun anyRunningPartGivesRunning() {
        listOf(SyncActivity(providerRunning = true), SyncActivity(uploaderRunning = true)).forEach { activity ->
            val state = state(counters = toUpload, activity = activity)
            assertEquals(BackupPhase.RUNNING, state.phase)
            assertTrue(state.isRunning)
        }
        // Выполняющийся прогон без очереди (scan нашёл ноль новых) — тоже RUNNING.
        assertEquals(BackupPhase.RUNNING, state(activity = SyncActivity(providerRunning = true)).phase)
    }

    @Test
    fun runningWithFailedFilesKeepsWarning() {
        val state = state(
            counters = MediaFileCounters(total = 5, toUpload = 3, failed = 2),
            activity = SyncActivity(uploaderRunning = true)
        )

        assertEquals(BackupPhase.RUNNING, state.phase)
        assertEquals(listOf(FILES_FAILED), state.kinds())
    }

    @Test
    fun blockingConditionsOutrankRunningAndAreReportedTogether() {
        val state = state(
            counters = toUpload,
            activity = SyncActivity(providerRunning = true),
            hasMediaAccess = false,
            isSignedIn = false,
            hasServerAddress = false
        )

        assertEquals(BackupPhase.ATTENTION_REQUIRED, state.phase)
        assertTrue(state.isRunning)
        assertEquals(listOf(NO_MEDIA_PERMISSION, NOT_SIGNED_IN, NO_SERVER_ADDRESS), state.kinds())
    }

    @Test
    fun missingSignInStopsBackupEvenWithEmptyQueue() {
        val state = state(isSignedIn = false, results = serverDown)

        assertEquals(BackupPhase.ATTENTION_REQUIRED, state.phase)
        // Прежняя ошибка Загрузчика не выдаётся за текущую: без пары он до Server не доходит.
        assertEquals(listOf(NOT_SIGNED_IN), state.kinds())
    }

    @Test
    fun unavailableServerDiffersFromMissingAddress() {
        val unavailable = state(counters = toUpload, results = serverDown)
        assertEquals(BackupPhase.WAITING, unavailable.phase)
        assertEquals(listOf(BackupIssue(SERVER_UNAVAILABLE, since = 500)), unavailable.issues)

        val noAddress = state(counters = toUpload, hasServerAddress = false, results = serverDown)
        assertEquals(BackupPhase.ATTENTION_REQUIRED, noAddress.phase)
        assertEquals(listOf(NO_SERVER_ADDRESS), noAddress.kinds())
    }

    @Test
    fun temporaryUploadErrorsAreOneWaitingReason() {
        listOf(SyncStatus.ERROR, SyncStatus.RETRY_SCHEDULED).forEach { status ->
            val state = state(counters = toUpload, results = scanned.copy(uploaderProblem = SyncProblem(status, 1)))
            assertEquals(BackupPhase.WAITING, state.phase)
            assertEquals(listOf(UPLOAD_FAILED), state.kinds())
        }
    }

    @Test
    fun storedUploaderProblemIsHiddenWhenItCannotExplainState() {
        // Нечего отправлять — прежняя недоступность Server не мешает.
        assertEquals(BackupPhase.SYNCED, state(results = serverDown).phase)
        assertTrue(state(counters = preparing, results = serverDown).issues.isEmpty())
        // Загрузчик снова выполняется — прежний итог пересматривается.
        val running = state(counters = toUpload, activity = SyncActivity(uploaderRunning = true), results = serverDown)
        assertEquals(BackupPhase.RUNNING, running.phase)
        assertTrue(running.issues.isEmpty())
    }

    @Test
    fun scanErrorIsVisibleWithEmptyQueue() {
        val state = state(results = scanError)

        assertEquals(BackupPhase.ATTENTION_REQUIRED, state.phase)
        assertEquals(listOf(BackupIssue(SCAN_FAILED, since = 700)), state.issues)
    }

    @Test
    fun scanErrorAccompaniesUploaderActivityAndWaiting() {
        val running = state(counters = toUpload, activity = SyncActivity(uploaderRunning = true), results = scanError)
        assertEquals(BackupPhase.RUNNING, running.phase)
        assertEquals(listOf(SCAN_FAILED), running.kinds())

        val waiting = state(counters = toUpload, results = scanError)
        assertEquals(BackupPhase.WAITING, waiting.phase)
        assertEquals(listOf(SCAN_FAILED), waiting.kinds())
    }

    @Test
    fun scanErrorIsHiddenWhileProviderRunsAgainOrHasNoAccess() {
        val retrying = state(activity = SyncActivity(providerRunning = true), results = scanError)
        assertEquals(BackupPhase.RUNNING, retrying.phase)
        assertTrue(retrying.issues.isEmpty())

        val noAccess = state(hasMediaAccess = false, results = scanError)
        assertEquals(listOf(NO_MEDIA_PERMISSION), noAccess.kinds())
    }

    @Test
    fun issuesAreOrderedByImportance() {
        val state = state(
            counters = MediaFileCounters(total = 6, toUpload = 3, failed = 3),
            results = scanError.copy(uploaderProblem = SyncProblem(SyncStatus.SERVER_UNAVAILABLE, 1))
        )

        assertEquals(BackupPhase.WAITING, state.phase)
        assertEquals(listOf(SCAN_FAILED, SERVER_UNAVAILABLE, FILES_FAILED), state.kinds())
        assertFalse(state.isRunning)
    }
}

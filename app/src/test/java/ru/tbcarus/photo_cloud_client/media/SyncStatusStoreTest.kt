package ru.tbcarus.photo_cloud_client.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** Сохраняемые итоги прогонов (A7.1): раздельность частей, время возникновения проблемы и сохранность. */
class SyncStatusStoreTest {

    private val now = AtomicLong(1_000)
    private val file = newSyncStatusFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = testSyncStatusStore(file, scope) { now.get() }

    private suspend fun results() = store.observeRunResults().first()

    @Test
    fun emptyStoreHasNoResults() = runBlocking {
        assertEquals(SyncRunResults(), results())
    }

    @Test
    fun successOfOnePartKeepsProblemOfTheOther() = runBlocking {
        store.recordProviderRun(scanned = true, problem = SyncStatus.ERROR)
        now.set(2_000)
        store.recordUploaderRun(problem = SyncStatus.SERVER_UNAVAILABLE, filesUploaded = false)

        now.set(3_000)
        store.recordUploaderRun(problem = null, filesUploaded = true)
        assertEquals(SyncProblem(SyncStatus.ERROR, 1_000), results().providerProblem)
        assertNull(results().uploaderProblem)

        store.recordUploaderRun(problem = SyncStatus.ERROR, filesUploaded = false)
        now.set(4_000)
        store.recordProviderRun(scanned = true, problem = null)
        assertNull(results().providerProblem)
        assertEquals(SyncProblem(SyncStatus.ERROR, 3_000), results().uploaderProblem)
        assertEquals(4_000L, results().lastScanAt)
        assertEquals(3_000L, results().lastUploadAt)
    }

    @Test
    fun repeatedProblemKeepsTimeOfOccurrence() = runBlocking {
        store.recordUploaderRun(problem = SyncStatus.SERVER_UNAVAILABLE, filesUploaded = false)
        now.set(5_000)
        store.recordUploaderRun(problem = SyncStatus.SERVER_UNAVAILABLE, filesUploaded = false)
        assertEquals(SyncProblem(SyncStatus.SERVER_UNAVAILABLE, 1_000), results().uploaderProblem)

        // Другая проблема — новое время возникновения.
        now.set(6_000)
        store.recordUploaderRun(problem = SyncStatus.RETRY_SCHEDULED, filesUploaded = false)
        assertEquals(SyncProblem(SyncStatus.RETRY_SCHEDULED, 6_000), results().uploaderProblem)
    }

    @Test
    fun uploadTimeIsWrittenOnlyWhenFilesWereTransferred() = runBlocking {
        store.recordUploaderRun(problem = null, filesUploaded = false)
        assertNull(results().lastUploadAt)

        store.recordUploaderRun(problem = SyncStatus.RETRY_SCHEDULED, filesUploaded = true)
        assertEquals(1_000L, results().lastUploadAt)

        // Прогон без отправки не стирает и не сдвигает время последней отправки.
        now.set(9_000)
        store.recordUploaderRun(problem = null, filesUploaded = false)
        assertEquals(1_000L, results().lastUploadAt)
    }

    @Test
    fun scanTimeIsWrittenOnlyAfterSuccessfulScan() = runBlocking {
        store.recordProviderRun(scanned = false, problem = SyncStatus.ERROR)
        assertNull(results().lastScanAt)
        assertEquals(SyncProblem(SyncStatus.ERROR, 1_000), results().providerProblem)
    }

    @Test
    fun resultsSurviveRecreationOfStore() = runBlocking {
        store.recordProviderRun(scanned = true, problem = null)
        now.set(2_000)
        store.recordUploaderRun(problem = SyncStatus.SERVER_UNAVAILABLE, filesUploaded = true)
        val saved = results()
        // Новый процесс: прежний DataStore закрыт, новый читает тот же файл.
        scope.coroutineContext.job.cancelAndJoin()

        val reopened = testSyncStatusStore(file) { now.get() }

        assertEquals(saved, reopened.observeRunResults().first())
        assertEquals(
            SyncRunResults(
                lastScanAt = 1_000,
                uploaderProblem = SyncProblem(SyncStatus.SERVER_UNAVAILABLE, 2_000),
                lastUploadAt = 2_000
            ),
            saved
        )
    }
}

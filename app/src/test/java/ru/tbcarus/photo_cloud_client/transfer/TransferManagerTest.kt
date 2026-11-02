package ru.tbcarus.photo_cloud_client.transfer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.library.LibrarySession
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TransferManagerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val remote = FakeTransferRemote()
    private val sessions = MutableStateFlow(LibrarySession("http://server/", "alice"))
    private val toRoot = UploadDestination(1, isRoot = true)
    private lateinit var documents: FakeDocuments
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        documents = FakeDocuments(ApplicationProvider.getApplicationContext())
        tempDir = temporary.newFolder("transfer")
    }

    // Свой планировщик вместо runTest: runTest перехватил бы и чужие исключения, оставшиеся в процессе от других классов.
    private val scheduler = TestCoroutineScheduler()

    private fun advanceUntilIdle() = scheduler.advanceUntilIdle()

    private fun manager(): TransferManager =
        TransferManager(remote, documents, tempDir, sessions, StandardTestDispatcher(scheduler)).also { advanceUntilIdle() }

    private val TransferManager.finished: TransferState.Finished get() = state.value as TransferState.Finished

    private fun TransferState.Finished.reasons(): Map<String, String> = issues.associate { it.name to it.reason }

    // 4.1 — скачивание

    @Test
    fun downloadIsVerifiedBeforeSavingAndTempIsRemoved() {
        val bytes = "Содержимое отчёта".toByteArray()
        val file = remote.file(1, "Отчёт.pdf", bytes)
        val manager = manager()

        assertTrue(manager.download(file))
        advanceUntilIdle()

        val awaiting = manager.state.value as TransferState.AwaitingDestination
        assertEquals("Отчёт.pdf", awaiting.fileName)
        assertEquals(1, tempDir.listFiles()!!.size)

        val target = documents.output()
        manager.saveDownload(awaiting.id, target)
        advanceUntilIdle()

        assertEquals("Отчёт.pdf", manager.finished.savedName)
        assertArrayEquals(bytes, documents.outputs.getValue(target).toByteArray())
        assertTrue(tempDir.listFiles()!!.isEmpty())
        assertTrue(documents.deleted.isEmpty())
    }

    @Test
    fun declinedDestinationRemovesTemp() {
        val file = remote.file(1, "a.txt", "abc".toByteArray())
        val manager = manager()
        manager.download(file)
        advanceUntilIdle()

        manager.saveDownload((manager.state.value as TransferState.AwaitingDestination).id, null)

        assertTrue(manager.finished.cancelled)
        assertTrue(tempDir.listFiles()!!.isEmpty())
        assertTrue(documents.outputs.isEmpty())
    }

    @Test
    fun networkErrorDoesNotOfferFile() {
        val file = remote.file(1, "a.txt", "abc".toByteArray())
        remote.fail("download:${file.id}", RemoteResult.NetworkError(IOException("reset")))
        val manager = manager()

        manager.download(file)
        advanceUntilIdle()

        assertEquals(TransferManager.NO_CONNECTION, manager.finished.issues.single().reason)
        assertNull(manager.finished.savedName)
        assertTrue(tempDir.listFiles()!!.isEmpty())
    }

    @Test
    fun corruptedContentIsNotOffered() {
        val file = remote.file(1, "a.txt", "original".toByteArray())
        remote.servedBytes = "damaged!".toByteArray()
        val manager = manager()

        manager.download(file)
        advanceUntilIdle()

        assertEquals(TransferManager.CORRUPTED, manager.finished.issues.single().reason)
        assertTrue(tempDir.listFiles()!!.isEmpty())
    }

    @Test
    fun activeDownloadCanBeCancelled() {
        val file = remote.file(1, "a.txt", "abc".toByteArray())
        remote.hold("download:${file.id}")
        val manager = manager()
        manager.download(file)
        advanceUntilIdle()

        manager.cancel()
        advanceUntilIdle()

        assertTrue(manager.finished.cancelled)
        assertTrue(tempDir.listFiles()!!.isEmpty())
    }

    @Test
    fun failedSaveRemovesUnfinishedDocument() {
        val file = remote.file(1, "a.txt", "abc".toByteArray())
        val manager = manager()
        manager.download(file)
        advanceUntilIdle()
        documents.failOutput = true
        val target = documents.output()

        manager.saveDownload((manager.state.value as TransferState.AwaitingDestination).id, target)
        advanceUntilIdle()

        assertTrue(manager.finished.issues.single().reason.startsWith("Не удалось сохранить файл"))
        assertEquals(listOf(target), documents.deleted)
        assertTrue(tempDir.listFiles()!!.isEmpty())
    }

    @Test
    fun leftoverTempFilesAreRemovedOnStart() {
        File(tempDir, "download-7.tmp").writeText("old")

        manager()

        assertTrue(tempDir.listFiles()!!.isEmpty())
    }

    // 4.2 — загрузка файлов

    @Test
    fun uploadsSeveralFilesWithOriginalNamesInOrder() {
        val first = documents.file("Чертёж.pdf", "pdf".toByteArray())
        val second = documents.file("модель.STEP", "step-data".toByteArray())
        val manager = manager()

        assertTrue(manager.uploadFiles(toRoot, listOf(first.uri, second.uri)))
        advanceUntilIdle()

        val finished = manager.finished
        assertEquals(2, finished.uploaded)
        assertEquals(emptyList<TransferIssue>(), finished.issues)
        assertEquals(1L, finished.destinationFolderId)
        assertEquals(listOf("upload:1:Чертёж.pdf", "upload:1:модель.STEP"), remote.uploads)
        assertEquals(listOf("Чертёж.pdf", "модель.STEP"), remote.filesIn(1).map { it.originalFilename })
    }

    @Test
    fun sameFileAgainIsCountedAsExistingWithoutTransfer() {
        remote.file(1, "a.txt", "same".toByteArray())
        val local = documents.file("A.TXT", "same".toByteArray())
        val manager = manager()

        manager.uploadFiles(toRoot, listOf(local.uri))
        advanceUntilIdle()

        assertEquals(1, manager.finished.existing)
        assertEquals(0, manager.finished.uploaded)
        assertTrue(remote.uploads.isEmpty())
    }

    @Test
    fun nameWithDifferentContentIsSkippedAsConflict() {
        remote.file(1, "a.txt", "server".toByteArray())
        val local = documents.file("a.txt", "local!".toByteArray())
        val manager = manager()

        manager.uploadFiles(toRoot, listOf(local.uri))
        advanceUntilIdle()

        assertEquals(mapOf("a.txt" to TransferManager.CONTENT_CONFLICT), manager.finished.reasons())
        assertTrue(remote.uploads.isEmpty())
        assertArrayEquals("server".toByteArray(), remote.content.values.single())
    }

    @Test
    fun invalidEmptyAndOversizedFilesAreSkippedBeforeTransfer() {
        val invalid = documents.file("a:b.txt", "x".toByteArray())
        val empty = documents.file(".nomedia", ByteArray(0))
        val large = documents.file("video.mp4", "x".toByteArray(), size = TransferManager.MAX_UPLOAD_BYTES + 1)
        val reserved = documents.file("camera", "x".toByteArray())
        val valid = documents.file("ok.txt", "x".toByteArray())
        val manager = manager()

        manager.uploadFiles(toRoot, listOf(invalid.uri, empty.uri, large.uri, reserved.uri, valid.uri))
        advanceUntilIdle()

        val reasons = manager.finished.reasons()
        assertEquals(libraryNameProblem("a:b.txt"), reasons["a:b.txt"])
        assertEquals(TransferManager.EMPTY_FILE, reasons[".nomedia"])
        assertEquals(TransferManager.TOO_LARGE, reasons["video.mp4"])
        assertEquals(TransferManager.RESERVED_NAME, reasons["camera"])
        assertEquals(listOf("upload:1:ok.txt"), remote.uploads)
        assertEquals(1, manager.finished.uploaded)
    }

    @Test
    fun serverLimitDuringTransferIsReportedForThatFileOnly() {
        val big = documents.file("big.bin", "x".toByteArray(), size = null)
        val next = documents.file("next.txt", "y".toByteArray())
        remote.fail("upload:1:big.bin", RemoteResult.NetworkError(UploadTooLargeException()))
        val manager = manager()

        manager.uploadFiles(toRoot, listOf(big.uri, next.uri))
        advanceUntilIdle()

        assertEquals(mapOf("big.bin" to TransferManager.TOO_LARGE), manager.finished.reasons())
        assertEquals(1, manager.finished.uploaded)
        assertNull(manager.finished.stopReason)
    }

    @Test
    fun failureOfOneFileDoesNotStopOthers() {
        val conflict = documents.file("raced.txt", "x".toByteArray())
        val broken = documents.file("broken.txt", "y".toByteArray())
        val good = documents.file("good.txt", "z".toByteArray())
        remote.fail("upload:1:raced.txt", RemoteResult.HttpError(409, "File with this name already exists in folder"))
        remote.fail("upload:1:broken.txt", RemoteResult.HttpError(500, "Internal"))
        val manager = manager()

        manager.uploadFiles(toRoot, listOf(conflict.uri, broken.uri, good.uri))
        advanceUntilIdle()

        val reasons = manager.finished.reasons()
        assertEquals(TransferManager.SERVER_CONFLICT, reasons["raced.txt"])
        assertEquals("Ошибка сервера: Internal", reasons["broken.txt"])
        assertEquals(1, manager.finished.uploaded)
        assertEquals(listOf("good.txt"), remote.filesIn(1).map { it.originalFilename })
    }

    @Test
    fun unreadableSourceIsSkipped() {
        val denied = documents.file("denied.txt", "x".toByteArray())
        val good = documents.file("good.txt", "y".toByteArray())
        documents.unreadable += denied.uri
        val manager = manager()

        manager.uploadFiles(toRoot, listOf(denied.uri, good.uri))
        advanceUntilIdle()

        assertEquals(mapOf("denied.txt" to UNREADABLE), manager.finished.reasons())
        assertEquals(1, manager.finished.uploaded)
    }

    @Test
    fun networkErrorIsRetriedOnceWithReopenedSource() {
        val local = documents.file("a.txt", "abc".toByteArray())
        remote.fail("upload:1:a.txt", RemoteResult.NetworkError(IOException("reset")))
        val manager = manager()

        manager.uploadFiles(toRoot, listOf(local.uri))
        advanceUntilIdle()

        assertEquals(1, manager.finished.uploaded)
        assertEquals(listOf("upload:1:a.txt", "upload:1:a.txt"), remote.uploads)
    }

    @Test
    fun lostResponseIsResolvedByServerIdempotency() {
        val local = documents.file("a.txt", "abc".toByteArray())
        // Server сохранил файл, но ответ потерян: повтор получает тот же FileItem.
        remote.lostResponses += "upload:1:a.txt"
        val manager = manager()

        manager.uploadFiles(toRoot, listOf(local.uri))
        advanceUntilIdle()

        assertEquals(1, manager.finished.uploaded)
        assertTrue(manager.finished.issues.isEmpty())
        assertEquals(listOf("a.txt"), remote.filesIn(1).map { it.originalFilename })
        assertEquals(2, remote.uploads.size)
    }

    @Test
    fun repeatedNetworkErrorStopsTransfer() {
        val first = documents.file("a.txt", "x".toByteArray())
        val second = documents.file("b.txt", "y".toByteArray())
        remote.fail(
            "upload:1:a.txt",
            RemoteResult.NetworkError(IOException("reset")),
            RemoteResult.NetworkError(IOException("reset"))
        )
        val manager = manager()

        manager.uploadFiles(toRoot, listOf(first.uri, second.uri))
        advanceUntilIdle()

        assertEquals(TransferManager.NO_CONNECTION, manager.finished.stopReason)
        assertEquals(listOf("upload:1:a.txt", "upload:1:a.txt"), remote.uploads)
        assertEquals(0, manager.finished.uploaded)
    }

    @Test
    fun unauthorizedStopsTransfer() {
        val first = documents.file("a.txt", "x".toByteArray())
        val second = documents.file("b.txt", "y".toByteArray())
        remote.fail("upload:1:a.txt", RemoteResult.HttpError(401, "Unauthorized"))
        val manager = manager()

        manager.uploadFiles(toRoot, listOf(first.uri, second.uri))
        advanceUntilIdle()

        assertEquals(TransferManager.AUTH_REQUIRED, manager.finished.stopReason)
        assertEquals(listOf("upload:1:a.txt"), remote.uploads)
    }

    @Test
    fun removedDestinationIsNotReplacedByRoot() {
        val folder = remote.folder(5, 1, "Docs")
        val first = documents.file("a.txt", "x".toByteArray())
        val second = documents.file("b.txt", "y".toByteArray())
        val manager = manager()
        remote.folders.remove(folder.id)

        manager.uploadFiles(UploadDestination(5, isRoot = false), listOf(first.uri, second.uri))
        advanceUntilIdle()

        assertEquals(TransferManager.DESTINATION_GONE, manager.finished.stopReason)
        assertTrue(remote.uploads.isEmpty())
        assertTrue(remote.filesIn(1).isEmpty())
    }

    @Test
    fun folderRemovedDuringTransferSkipsItsRemainingFiles() {
        val first = documents.file("a.txt", "x".toByteArray())
        val second = documents.file("b.txt", "y".toByteArray())
        remote.folder(5, 1, "Docs")
        remote.fail("upload:5:a.txt", RemoteResult.HttpError(404, "Folder not found"))
        val manager = manager()

        manager.uploadFiles(UploadDestination(5, isRoot = false), listOf(first.uri, second.uri))
        advanceUntilIdle()

        assertEquals(listOf("upload:5:a.txt"), remote.uploads)
        assertEquals(TransferManager.DESTINATION_GONE, manager.finished.reasons()["b.txt"])
    }

    @Test
    fun cancelKeepsCompletedFilesAndReportsThem() {
        val first = documents.file("a.txt", "x".toByteArray())
        val second = documents.file("b.txt", "y".toByteArray())
        val third = documents.file("c.txt", "z".toByteArray())
        remote.hold("upload:1:b.txt")
        val manager = manager()
        manager.uploadFiles(toRoot, listOf(first.uri, second.uri, third.uri))
        advanceUntilIdle()

        val running = manager.state.value as TransferState.Running
        assertEquals(2, running.current)
        assertEquals("b.txt", running.currentName)
        manager.cancel()
        advanceUntilIdle()

        assertTrue(manager.finished.cancelled)
        assertEquals(1, manager.finished.uploaded)
        assertEquals(listOf("a.txt"), remote.filesIn(1).map { it.originalFilename })
        assertFalse(remote.uploads.contains("upload:1:c.txt"))
    }

    @Test
    fun sessionChangeCancelsAndClearsTransfer() {
        val first = documents.file("a.txt", "x".toByteArray())
        val second = documents.file("b.txt", "y".toByteArray())
        val gate = remote.hold("upload:1:a.txt")
        val manager = manager()
        manager.uploadFiles(toRoot, listOf(first.uri, second.uri))
        advanceUntilIdle()

        sessions.value = LibrarySession("http://server/", "bob")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(TransferState.Idle, manager.state.value)
        assertEquals(listOf("upload:1:a.txt"), remote.uploads)
    }

    @Test
    fun serverChangeCancelsTransfer() {
        val local = documents.file("a.txt", "x".toByteArray())
        remote.hold("upload:1:a.txt")
        val manager = manager()
        manager.uploadFiles(toRoot, listOf(local.uri))
        advanceUntilIdle()

        sessions.value = LibrarySession("http://other/", "alice")
        advanceUntilIdle()

        assertEquals(TransferState.Idle, manager.state.value)
    }

    @Test
    fun unexpectedFailureEndsTransferWithoutCrash() {
        documents = object : FakeDocuments(ApplicationProvider.getApplicationContext()) {
            override fun walk(treeUri: android.net.Uri): LocalDir = throw IllegalStateException("provider died")
        }
        val manager = manager()

        manager.uploadFolder(toRoot, android.net.Uri.parse("content://test/tree"))
        advanceUntilIdle()

        assertEquals("Непредвиденная ошибка: provider died", manager.finished.stopReason)
        assertFalse(manager.state.value.isActive)
    }

    @Test
    fun onlyOneTransferAtATimeAndNoneWithoutSession() {
        val local = documents.file("a.txt", "x".toByteArray())
        remote.hold("upload:1:a.txt")
        val manager = manager()

        assertTrue(manager.uploadFiles(toRoot, listOf(local.uri)))
        advanceUntilIdle()
        assertFalse(manager.uploadFiles(toRoot, listOf(local.uri)))
        assertFalse(manager.download(remote.file(1, "b.txt", "b".toByteArray())))

        manager.cancel()
        advanceUntilIdle()
        sessions.value = LibrarySession("http://server/", null)
        advanceUntilIdle()
        assertFalse(manager.uploadFiles(toRoot, listOf(local.uri)))
    }

    @Test
    fun progressCountsBytesOfKnownSizesOnly() {
        val known = documents.file("a.txt", "12345".toByteArray())
        val unknown = documents.file("b.txt", "678".toByteArray(), size = null)
        remote.hold("upload:1:a.txt")
        val manager = manager()

        manager.uploadFiles(toRoot, listOf(known.uri))
        advanceUntilIdle()
        assertEquals(5L, (manager.state.value as TransferState.Running).bytesTotal)
        manager.cancel()
        advanceUntilIdle()

        remote.hold("upload:1:b.txt")
        manager.uploadFiles(toRoot, listOf(unknown.uri))
        advanceUntilIdle()
        assertNull((manager.state.value as TransferState.Running).bytesTotal)
    }

    // 4.3 — загрузка папки

    @Test
    fun folderTreeIsRecreatedWithEmptyFolders() {
        documents.tree = LocalDir(
            "Документы",
            files = listOf(documents.file("Заметки.txt", "notes".toByteArray())),
            dirs = listOf(
                LocalDir(
                    "Проекты",
                    files = listOf(
                        documents.file("Чертеж.pdf", "pdf".toByteArray()),
                        documents.file("Модель.step", "step".toByteArray())
                    ),
                    dirs = listOf(LocalDir("Архив", dirs = listOf(LocalDir("2024"))))
                ),
                LocalDir("Пустая")
            )
        )
        val manager = manager()

        assertTrue(manager.uploadFolder(toRoot, android.net.Uri.parse("content://test/tree")))
        advanceUntilIdle()

        val docs = remote.folderNamed(1, "Документы")!!
        val projects = remote.folderNamed(docs.id, "Проекты")!!
        val archive = remote.folderNamed(projects.id, "Архив")!!
        assertNotNull(remote.folderNamed(archive.id, "2024"))
        assertNotNull(remote.folderNamed(docs.id, "Пустая"))
        assertEquals(listOf("Заметки.txt"), remote.filesIn(docs.id).map { it.originalFilename })
        assertEquals(listOf("Чертеж.pdf", "Модель.step"), remote.filesIn(projects.id).map { it.originalFilename })
        assertEquals(3, manager.finished.uploaded)
        assertTrue(manager.finished.issues.isEmpty())
    }

    @Test
    fun existingFolderIsMergedWithConflictRules() {
        val docs = remote.folder(10, 1, "документы")
        remote.file(docs.id, "same.txt", "same".toByteArray())
        remote.file(docs.id, "changed.txt", "server".toByteArray())
        documents.tree = LocalDir(
            "Документы",
            files = listOf(
                documents.file("same.txt", "same".toByteArray()),
                documents.file("changed.txt", "local".toByteArray()),
                documents.file("new.txt", "new".toByteArray())
            )
        )
        val manager = manager()

        manager.uploadFolder(toRoot, android.net.Uri.parse("content://test/tree"))
        advanceUntilIdle()

        assertFalse(remote.calls.any { it.startsWith("create:") })
        assertEquals(1, manager.finished.existing)
        assertEquals(1, manager.finished.uploaded)
        assertEquals(mapOf("Документы/changed.txt" to TransferManager.CONTENT_CONFLICT), manager.finished.reasons())
    }

    @Test
    fun namesTakenByOtherKindAreSkipped() {
        val docs = remote.folder(10, 1, "Docs")
        remote.folder(11, docs.id, "report")
        remote.file(docs.id, "photos", "file".toByteArray())
        documents.tree = LocalDir(
            "Docs",
            files = listOf(documents.file("report", "x".toByteArray())),
            dirs = listOf(LocalDir("photos", files = listOf(documents.file("p.jpg", "p".toByteArray()))))
        )
        val manager = manager()

        manager.uploadFolder(toRoot, android.net.Uri.parse("content://test/tree"))
        advanceUntilIdle()

        val reasons = manager.finished.reasons()
        assertEquals(TransferManager.NAME_TAKEN_BY_FOLDER, reasons["Docs/report"])
        assertEquals("${TransferManager.NAME_TAKEN_BY_FILE} (файлов: 1)", reasons["Docs/photos/"])
        assertTrue(remote.uploads.isEmpty())
    }

    @Test
    fun hiddenEntriesAreNotExcluded() {
        documents.tree = LocalDir(
            "Data",
            files = listOf(documents.file(".config", "cfg".toByteArray())),
            dirs = listOf(LocalDir(".hidden", files = listOf(documents.file(".nomedia", ByteArray(0)))))
        )
        val manager = manager()

        manager.uploadFolder(toRoot, android.net.Uri.parse("content://test/tree"))
        advanceUntilIdle()

        val data = remote.folderNamed(1, "Data")!!
        assertNotNull(remote.folderNamed(data.id, ".hidden"))
        assertEquals(listOf(".config"), remote.filesIn(data.id).map { it.originalFilename })
        assertEquals(mapOf("Data/.hidden/.nomedia" to TransferManager.EMPTY_FILE), manager.finished.reasons())
    }

    @Test
    fun unreadablePartsAreSkippedAndRestContinues() {
        val denied = documents.file("denied.txt", "x".toByteArray())
        documents.unreadable += denied.uri
        documents.tree = LocalDir(
            "Data",
            files = listOf(denied, documents.file("ok.txt", "y".toByteArray())),
            dirs = listOf(LocalDir("Locked", problem = UNREADABLE_FOLDER), LocalDir("bad:name"))
        )
        val manager = manager()

        manager.uploadFolder(toRoot, android.net.Uri.parse("content://test/tree"))
        advanceUntilIdle()

        val reasons = manager.finished.reasons()
        assertEquals(UNREADABLE, reasons["Data/denied.txt"])
        assertEquals(UNREADABLE_FOLDER, reasons["Data/Locked/"])
        assertEquals(libraryNameProblem("bad:name"), reasons["Data/bad:name/"])
        assertEquals(1, manager.finished.uploaded)
    }

    @Test
    fun partialFailureKeepsCreatedFolders() {
        documents.tree = LocalDir(
            "Data",
            dirs = listOf(
                LocalDir("A", files = listOf(documents.file("a.txt", "a".toByteArray()))),
                LocalDir("B", files = listOf(documents.file("b.txt", "b".toByteArray())))
            )
        )
        remote.fail(
            "upload:*:b.txt",
            RemoteResult.NetworkError(IOException("reset")),
            RemoteResult.NetworkError(IOException("reset"))
        )
        val manager = manager()

        manager.uploadFolder(toRoot, android.net.Uri.parse("content://test/tree"))
        advanceUntilIdle()

        val data = remote.folderNamed(1, "Data")!!
        val a = remote.folderNamed(data.id, "A")!!
        assertNotNull(remote.folderNamed(data.id, "B"))
        assertEquals(listOf("a.txt"), remote.filesIn(a.id).map { it.originalFilename })
        assertEquals(TransferManager.NO_CONNECTION, manager.finished.stopReason)
        assertEquals(1, manager.finished.uploaded)
    }

    @Test
    fun cameraFolderInRootIsReportedAsReserved() {
        documents.tree = LocalDir("Camera", files = listOf(documents.file("IMG_1.jpg", "x".toByteArray())))
        val manager = manager()

        manager.uploadFolder(toRoot, android.net.Uri.parse("content://test/tree"))
        advanceUntilIdle()

        assertEquals("${TransferManager.RESERVED_NAME} (файлов: 1)", manager.finished.reasons()["Camera/"])
        assertFalse(remote.calls.any { it.startsWith("create:") || it.startsWith("upload:") })
    }

    @Test
    fun cameraNameIsAllowedBelowRoot() {
        remote.folder(5, 1, "Trips")
        documents.tree = LocalDir("Camera", files = listOf(documents.file("IMG_1.jpg", "x".toByteArray())))
        val manager = manager()

        manager.uploadFolder(UploadDestination(5, isRoot = false), android.net.Uri.parse("content://test/tree"))
        advanceUntilIdle()

        assertNotNull(remote.folderNamed(5, "Camera"))
        assertEquals(1, manager.finished.uploaded)
    }

    @Test
    fun folderCreatedByLostRequestIsReused() {
        documents.tree = LocalDir("Data", files = listOf(documents.file("a.txt", "a".toByteArray())))
        // Первый запрос создал папку, но ответ потерян; повтор получает 409 и находит её.
        remote.lostResponses += "create:1:Data"
        val manager = manager()

        manager.uploadFolder(toRoot, android.net.Uri.parse("content://test/tree"))
        advanceUntilIdle()

        assertEquals(2, remote.calls.count { it == "create:1:Data" })
        val data = remote.folderNamed(1, "Data")!!
        assertEquals(1, remote.folders.values.count { it.name == "Data" })
        assertEquals(listOf("a.txt"), remote.filesIn(data.id).map { it.originalFilename })
        assertTrue(manager.finished.issues.isEmpty())
    }
}

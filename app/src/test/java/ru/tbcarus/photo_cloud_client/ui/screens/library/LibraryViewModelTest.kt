package ru.tbcarus.photo_cloud_client.ui.screens.library

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FilePageDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import androidx.room.Room
import ru.tbcarus.photo_cloud_client.backup.BackupAccount
import ru.tbcarus.photo_cloud_client.backup.BackupSource
import ru.tbcarus.photo_cloud_client.backup.BackupSourceRemote
import ru.tbcarus.photo_cloud_client.backup.BackupSourceRepository
import ru.tbcarus.photo_cloud_client.backup.DisableOutcome
import ru.tbcarus.photo_cloud_client.backup.FakeAccess
import ru.tbcarus.photo_cloud_client.backup.FakeAccounts
import ru.tbcarus.photo_cloud_client.backup.FakeScheduler
import ru.tbcarus.photo_cloud_client.backup.RegistrationState
import ru.tbcarus.photo_cloud_client.backup.SourceAccess
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.library.LibraryRepository
import ru.tbcarus.photo_cloud_client.library.LibraryResult
import ru.tbcarus.photo_cloud_client.library.librarySessionFlow
import ru.tbcarus.photo_cloud_client.transfer.FakeDocuments
import ru.tbcarus.photo_cloud_client.transfer.FakeTransferRemote
import ru.tbcarus.photo_cloud_client.transfer.TransferManager
import ru.tbcarus.photo_cloud_client.transfer.TransferState
import ru.tbcarus.photo_cloud_client.transfer.UploadDestination
import java.nio.file.Files
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryViewModelTest {

    private companion object {
        const val SERVER_A = "http://10.0.0.1:8080/"
        const val SERVER_B = "http://10.0.0.2:8080/"
    }

    private val root = FolderDto(1, null, "root", "ROOT")
    private val camera = FolderDto(2, 1, "Camera", "CAMERA")
    private val alpha = FolderDto(3, 1, "Alpha", "USER")
    private val beta = FolderDto(4, 1, "Beta", "USER")
    private val nested = FolderDto(5, 3, "Nested", "USER")

    private val repository = FakeLibraryRepository(root)
    private val tokens = InMemoryTokenStorage(tokensOf("alice"))
    private val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply { baseUrl = SERVER_A }

    private val state get() = viewModel.uiState.value
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // Незакрытая база оставляет фоновые задачи Room, которые мешают следующим тестам.
        if (dbDelegate.isInitialized()) db.close()
        Dispatchers.resetMain()
    }

    private val transferRemote = FakeTransferRemote()
    private val transferDocuments = FakeDocuments(ApplicationProvider.getApplicationContext())
    private val transfers by lazy {
        TransferManager(
            transferRemote,
            transferDocuments,
            Files.createTempDirectory("transfer").toFile(),
            librarySessionFlow(baseUrlProvider, tokens),
            UnconfinedTestDispatcher()
        )
    }

    private val disableRemote = FakeDisableRemote()
    private val dbDelegate = lazy {
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    private val db by dbDelegate
    private val sources by lazy {
        val context = ApplicationProvider.getApplicationContext<Application>()
        BackupSourceRepository(
            db.backupSourceDao(), disableRemote, FakeAccess(context),
            FakeAccounts(BackupAccount(SERVER_A, "alice")), FakeScheduler(context)
        )
    }

    private fun createViewModel() {
        viewModel = LibraryViewModel(repository, baseUrlProvider, tokens, transfers, sources)
    }

    private fun opened(): LibraryViewModel {
        createViewModel()
        viewModel.ensureLoaded()
        return viewModel
    }

    @Test
    fun firstOpenLoadsRootContent() {
        repository.setChildren(root, alpha)
        repository.setFiles(root, 0, page(listOf(file(10))))

        opened()

        assertEquals(LibraryStatus.Content, state.status)
        assertEquals(listOf(root), state.path)
        assertEquals(listOf(alpha), state.folders)
        assertEquals(listOf(10L), fileIds())
        assertEquals(listOf("root", "children:1", "files:1:0"), repository.calls)
    }

    @Test
    fun cameraIsFirstInFolderList() {
        repository.setChildren(root, alpha, camera, beta)

        opened()

        assertEquals(listOf(camera, alpha, beta), state.folders)
    }

    @Test
    fun foldersOpenAndBackReturnsToParent() {
        repository.setChildren(root, alpha)
        repository.setChildren(alpha, nested)
        repository.setFiles(nested, 0, page(listOf(file(11))))
        opened()

        viewModel.openFolder(alpha)
        viewModel.openFolder(nested)
        assertEquals(listOf(root, alpha, nested), state.path)
        assertEquals(listOf(11L), fileIds())
        assertTrue(state.canGoUp)

        viewModel.navigateUp()
        assertEquals(listOf(root, alpha), state.path)
        assertEquals(listOf(nested), state.folders)
        assertTrue(state.files.isEmpty())

        viewModel.navigateUp()
        assertEquals(listOf(root), state.path)
        assertEquals(listOf(alpha), state.folders)
        assertFalse(state.canGoUp)
    }

    @Test
    fun backInRootLeavesSystemBackToApplication() {
        opened()
        val calls = repository.calls.size

        viewModel.navigateUp()

        assertFalse(state.canGoUp)
        assertEquals(listOf(root), state.path)
        assertEquals(calls, repository.calls.size)
    }

    @Test
    fun returningToTabKeepsFolderListAndPosition() {
        repository.setChildren(root, alpha)
        repository.setFiles(alpha, 0, page(listOf(file(10)), hasNext = true))
        repository.setFiles(alpha, 1, page(listOf(file(11))))
        opened()
        viewModel.openFolder(alpha)
        viewModel.loadNextPage()
        viewModel.saveScroll(12, 34)
        val calls = repository.calls.size
        val loaded = state

        viewModel.ensureLoaded()

        assertEquals(loaded, state)
        assertEquals(calls, repository.calls.size)
        assertEquals(12, viewModel.scrollIndex)
        assertEquals(34, viewModel.scrollOffset)
    }

    @Test
    fun refreshStartsCurrentFolderFromFirstPage() {
        repository.setChildren(root, alpha)
        repository.setFiles(alpha, 0, page(listOf(file(10)), hasNext = true))
        repository.setFiles(alpha, 1, page(listOf(file(11))))
        opened()
        viewModel.openFolder(alpha)
        viewModel.loadNextPage()
        viewModel.saveScroll(5, 0)
        repository.setFiles(alpha, 0, page(listOf(file(12))))
        repository.calls.clear()

        viewModel.refresh()

        assertEquals(listOf("children:3", "files:3:0"), repository.calls)
        assertEquals(listOf(12L), fileIds())
        assertFalse(state.hasNext)
        assertEquals(listOf(root, alpha), state.path)
        assertEquals(0, viewModel.scrollIndex)
    }

    @Test
    fun emptyFolderIsContentWithoutItems() {
        opened()

        assertEquals(LibraryStatus.Content, state.status)
        assertTrue(state.isEmpty)
    }

    @Test
    fun missingServerIsReportedWithoutRequests() {
        baseUrlProvider.baseUrl = ""

        opened()

        assertEquals(LibraryStatus.NoServer, state.status)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun missingSessionIsReportedWithoutRequests() {
        tokens.clear()

        opened()

        assertEquals(LibraryStatus.NoSession, state.status)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun serverAddressRestoredOnOpenScreenLoadsAfterStatusChange() {
        baseUrlProvider.baseUrl = ""
        opened()

        baseUrlProvider.baseUrl = SERVER_A

        // Экран реагирует на смену статуса тем же вызовом.
        assertEquals(LibraryStatus.Idle, state.status)
        viewModel.ensureLoaded()
        assertEquals(LibraryStatus.Content, state.status)
    }

    @Test
    fun firstLoadErrorIsShownAndRetried() {
        repository.fail("root", LibraryResult.NetworkError("timeout"))

        opened()
        assertTrue(state.status is LibraryStatus.Error)
        assertTrue(state.path.isEmpty())

        repository.succeed("root")
        viewModel.refresh()
        assertEquals(LibraryStatus.Content, state.status)
    }

    @Test
    fun filesFailureDoesNotShowPartialFolder() {
        repository.setChildren(root, alpha)
        repository.fail("files:1:0", LibraryResult.ServerError("boom"))

        opened()

        assertTrue(state.status is LibraryStatus.Error)
        assertTrue(state.folders.isEmpty())
        assertEquals(listOf(root), state.path)
    }

    @Test
    fun foldersFailureDoesNotShowPartialFolder() {
        repository.setFiles(root, 0, page(listOf(file(10))))
        repository.fail("children:1", LibraryResult.NetworkError("timeout"))

        opened()

        assertTrue(state.status is LibraryStatus.Error)
        assertTrue(state.files.isEmpty())
    }

    @Test
    fun rejectedSessionAsksToLogIn() {
        repository.fail("root", LibraryResult.Unauthorized)

        opened()

        assertEquals(LibraryStatus.NoSession, state.status)
    }

    @Test
    fun newLoginOfSameUserAfterRejectionLoadsAgain() {
        repository.fail("root", LibraryResult.Unauthorized)
        opened()
        repository.succeed("root")

        tokens.saveTokens(tokensOf("alice", version = 2))

        assertEquals(LibraryStatus.Idle, state.status)
        viewModel.ensureLoaded()
        assertEquals(LibraryStatus.Content, state.status)
    }

    @Test
    fun deletedFolderReturnsToRootWithNotice() {
        repository.setChildren(root, alpha)
        opened()
        repository.fail("children:3", LibraryResult.NotFound)

        viewModel.openFolder(alpha)

        assertEquals(LibraryStatus.Content, state.status)
        assertEquals(listOf(root), state.path)
        assertEquals(listOf(alpha), state.folders)
        assertNotNull(state.notice)

        viewModel.refresh()
        assertNull(state.notice)
    }

    @Test
    fun folderDeletedDuringPaginationReturnsToRoot() {
        repository.setChildren(root, alpha)
        repository.setFiles(alpha, 0, page(listOf(file(10)), hasNext = true))
        repository.fail("files:3:1", LibraryResult.NotFound)
        opened()
        viewModel.openFolder(alpha)

        viewModel.loadNextPage()

        assertEquals(listOf(root), state.path)
        assertNotNull(state.notice)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun nextPageIsAppendedAndStopsWithoutHasNext() {
        repository.setFiles(root, 0, page(listOf(file(10)), hasNext = true))
        repository.setFiles(root, 1, page(listOf(file(11)), hasNext = true))
        repository.setFiles(root, 2, page(listOf(file(12))))
        opened()

        viewModel.onListEndReached()
        viewModel.onListEndReached()
        viewModel.onListEndReached()

        assertEquals(listOf(10L, 11L, 12L), fileIds())
        assertFalse(state.hasNext)
        assertEquals(listOf("files:1:0", "files:1:1", "files:1:2"), repository.calls.filter { it.startsWith("files") })
    }

    @Test
    fun onlyOneNextPageRequestRunsAtATime() {
        repository.setFiles(root, 0, page(listOf(file(10)), hasNext = true))
        repository.setFiles(root, 1, page(listOf(file(11))))
        val gate = repository.hold("files:1:1")
        opened()

        viewModel.onListEndReached()
        viewModel.onListEndReached()
        viewModel.loadNextPage()

        assertTrue(state.isLoadingMore)
        assertEquals(1, repository.calls.count { it == "files:1:1" })

        gate.complete(Unit)
        assertFalse(state.isLoadingMore)
        assertEquals(listOf(10L, 11L), fileIds())
    }

    @Test
    fun nextPageFailureKeepsListAndRetriesSamePage() {
        repository.setFiles(root, 0, page(listOf(file(10)), hasNext = true))
        repository.setFiles(root, 1, page(listOf(file(11))))
        repository.fail("files:1:1", LibraryResult.NetworkError("timeout"))
        opened()

        viewModel.onListEndReached()

        assertEquals(LibraryStatus.Content, state.status)
        assertEquals(listOf(10L), fileIds())
        assertNotNull(state.loadMoreError)
        assertFalse(state.isLoadingMore)

        // Прокрутка не повторяет ошибочный запрос сама.
        viewModel.onListEndReached()
        assertEquals(1, repository.calls.count { it == "files:1:1" })

        repository.succeed("files:1:1")
        viewModel.loadNextPage()

        assertEquals(2, repository.calls.count { it == "files:1:1" })
        assertEquals(0, repository.calls.count { it == "files:1:2" })
        assertEquals(listOf(10L, 11L), fileIds())
        assertNull(state.loadMoreError)
    }

    @Test
    fun repeatedFilesAreRemovedFromPages() {
        repository.setFiles(root, 0, page(listOf(file(10), file(11)), hasNext = true))
        repository.setFiles(root, 1, page(listOf(file(11), file(12), file(12))))
        opened()

        viewModel.onListEndReached()

        assertEquals(listOf(10L, 11L, 12L), fileIds())
    }

    @Test
    fun staleFolderResponseDoesNotReplaceNewFolder() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(20))))
        repository.setFiles(beta, 0, page(listOf(file(30))))
        val gate = repository.hold("files:3:0")
        opened()

        viewModel.openFolder(alpha)
        assertEquals(LibraryStatus.Loading, state.status)
        viewModel.navigateUp()
        viewModel.openFolder(beta)
        gate.complete(Unit)

        assertEquals(listOf(root, beta), state.path)
        assertEquals(listOf(30L), fileIds())
        assertEquals(LibraryStatus.Content, state.status)
    }

    @Test
    fun staleNextPageIsNotAddedToNewFolder() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(20)), hasNext = true))
        repository.setFiles(alpha, 1, page(listOf(file(21))))
        repository.setFiles(beta, 0, page(listOf(file(30))))
        val gate = repository.hold("files:3:1")
        opened()
        viewModel.openFolder(alpha)

        viewModel.onListEndReached()
        viewModel.navigateUp()
        viewModel.openFolder(beta)
        gate.complete(Unit)

        assertEquals(listOf(30L), fileIds())
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun refreshedTokensOfSameUserKeepLoadedFolder() {
        repository.setChildren(root, alpha)
        opened()
        viewModel.openFolder(alpha)
        val loaded = state

        tokens.saveTokens(tokensOf("alice", version = 2))

        assertEquals(loaded, state)
    }

    @Test
    fun otherUserDropsLoadedFolderAtOnce() {
        repository.setChildren(root, alpha)
        repository.setFiles(alpha, 0, page(listOf(file(20)), hasNext = true))
        opened()
        viewModel.openFolder(alpha)
        viewModel.saveScroll(7, 0)

        tokens.saveTokens(tokensOf("bob"))

        assertEquals(LibraryStatus.Idle, state.status)
        assertTrue(state.path.isEmpty())
        assertTrue(state.files.isEmpty())
        assertFalse(state.hasNext)
        assertEquals(0, viewModel.scrollIndex)

        repository.calls.clear()
        viewModel.ensureLoaded()
        assertEquals("root", repository.calls.first())
        assertEquals(listOf(root), state.path)
    }

    @Test
    fun logoutDropsLoadedFolder() {
        repository.setChildren(root, alpha)
        opened()
        viewModel.openFolder(alpha)

        tokens.clear()

        assertEquals(LibraryStatus.NoSession, state.status)
        assertTrue(state.path.isEmpty())
        assertTrue(state.folders.isEmpty())
    }

    @Test
    fun otherServerDropsLoadedFolder() {
        repository.setChildren(root, alpha)
        opened()
        viewModel.openFolder(alpha)

        baseUrlProvider.baseUrl = SERVER_B

        assertEquals(LibraryStatus.Idle, state.status)
        assertTrue(state.path.isEmpty())
    }

    @Test
    fun responseForPreviousAccountIsIgnored() {
        repository.setChildren(root, alpha)
        repository.setFiles(alpha, 0, page(listOf(file(20))))
        val gate = repository.hold("files:3:0")
        opened()
        viewModel.openFolder(alpha)

        tokens.saveTokens(tokensOf("bob"))
        gate.complete(Unit)

        assertEquals(LibraryStatus.Idle, state.status)
        assertTrue(state.files.isEmpty())
    }

    private fun fileIds() = state.files.map { it.id }

    @Test
    fun finishedUploadRefreshesOpenDestinationWithoutLeavingIt() {
        repository.setChildren(root, alpha)
        transferRemote.folder(alpha.id, root.id, alpha.name)
        opened().openFolder(alpha)
        repository.calls.clear()
        val local = transferDocuments.file("new.txt", "x".toByteArray())

        viewModel.uploadFiles(viewModel.uploadDestination()!!, listOf(local.uri))

        assertTrue(viewModel.transferState.value is TransferState.Finished)
        assertEquals(listOf("children:3", "files:3:0"), repository.calls)
        assertEquals(listOf(root, alpha), state.path)
    }

    @Test
    fun finishedUploadDoesNotReloadFolderUserLeft() {
        repository.setChildren(root, alpha)
        transferRemote.folder(alpha.id, root.id, alpha.name)
        opened().openFolder(alpha)
        val local = transferDocuments.file("new.txt", "x".toByteArray())
        val gate = transferRemote.hold("upload:3:new.txt")
        viewModel.uploadFiles(viewModel.uploadDestination()!!, listOf(local.uri))
        viewModel.navigateUp()
        repository.calls.clear()

        gate.complete(Unit)

        assertTrue(viewModel.transferState.value is TransferState.Finished)
        assertTrue(repository.calls.isEmpty())
        assertEquals(listOf(root), state.path)
    }

    @Test
    fun uploadIsOfferedInRootAndUserFoldersButNotInCamera() {
        repository.setChildren(root, camera, alpha)
        opened()
        assertEquals(UploadDestination(root.id, isRoot = true), viewModel.uploadDestination())

        viewModel.openFolder(alpha)
        assertEquals(UploadDestination(alpha.id, isRoot = false), viewModel.uploadDestination())

        viewModel.navigateUp()
        viewModel.openFolder(camera)
        assertFalse(state.canUpload)
        assertNull(viewModel.uploadDestination())
    }

    @Test
    fun disableOnSourceRootWithoutLocalRecordKeepsFolderOpenAndAllowsUpload() {
        val (autoUploads, source, nestedSource) = openSourceRoot()
        repository.setFiles(source, 0, page(listOf(file(10), file(11))))
        assertTrue(state.isSourceRoot)
        assertFalse(state.canUpload)
        repository.calls.clear()

        viewModel.disableSource()
        awaitState { !it.isSourceRoot && it.status == LibraryStatus.Content }

        assertEquals(listOf(source.id), disableRemote.calls)
        // Та же папка, без отметки источника: путь не сбрасывается в ROOT, содержимое перечитано.
        assertEquals(listOf(root, autoUploads, source.copy(backupSource = null)), state.path)
        assertEquals(LibraryStatus.Content, state.status)
        assertFalse(state.isSourceRoot)
        assertTrue(state.canUpload)
        assertEquals(UploadDestination(source.id, isRoot = false), viewModel.uploadDestination())
        assertEquals(listOf(nestedSource), state.folders)
        assertEquals(listOf(10L, 11L), fileIds())
        assertEquals(listOf("children:${source.id}", "files:${source.id}:0"), repository.calls.sorted())
        assertNull(state.notice)

        // Внутри отключённого дерева ручная загрузка тоже доступна.
        viewModel.openFolder(nestedSource)
        assertTrue(state.canUpload)
    }

    @Test
    fun failedDisableKeepsSourceAndShowsError() {
        val (autoUploads, source, _) = openSourceRoot()
        disableRemote.outcome = DisableOutcome.Unknown("Нет связи с сервером")

        viewModel.disableSource()
        awaitState { it.notice != null }

        assertEquals(listOf(root, autoUploads, source), state.path)
        assertTrue(state.isSourceRoot)
        assertFalse(state.canUpload)
        assertFalse(state.disabling)
        assertEquals("Нет связи с сервером" + BackupSourceRepository.DISABLE_UNKNOWN_SUFFIX, state.notice)

        // Повтор — тот же ID.
        disableRemote.outcome = DisableOutcome.Disabled
        viewModel.disableSource()
        awaitState { !it.isSourceRoot && it.status == LibraryStatus.Content }
        assertEquals(listOf(source.id, source.id), disableRemote.calls)
    }

    @Test
    fun disableIsIgnoredOutsideSourceRoot() {
        val (_, _, nestedSource) = openSourceRoot()
        viewModel.openFolder(nestedSource)

        viewModel.disableSource()
        viewModel.navigateUp()
        viewModel.navigateUp()
        viewModel.disableSource()

        assertTrue(disableRemote.calls.isEmpty())
    }

    @Test
    fun disableUpdatesLocalRecordOfThisDevice() = kotlinx.coroutines.runBlocking {
        val (_, source, _) = openSourceRoot()
        val id = db.backupSourceDao().insert(
            BackupSource(
                sourceUuid = source.backupSource!!, treeUri = "content://tree/docs", localName = "Documents",
                serverName = "Documents", serverRootFolderId = source.id, serverUrl = SERVER_A, accountId = "alice",
                registrationState = RegistrationState.ACTIVE, registrationError = null,
                accessState = SourceAccess.AVAILABLE, createdAt = 1
            )
        )

        viewModel.disableSource()
        awaitState { !it.isSourceRoot && it.status == LibraryStatus.Content }

        assertEquals(RegistrationState.REMOTE_DISABLED, db.backupSourceDao().get(id)!!.registrationState)
    }

    @Test
    fun createdFolderAppearsAfterServerConfirmation() {
        repository.setChildren(root, alpha)
        repository.setChildren(alpha, nested)
        opened().openFolder(alpha)

        viewModel.startCreateFolder()
        assertEquals(NameTarget.NewFolder, state.edit?.target)
        assertEquals("", state.edit?.name)
        viewModel.onEditNameChange("Архив")
        // До ответа Server папка в списке не появляется.
        val created = FolderDto(30, alpha.id, "Архив", "USER")
        repository.setChildren(alpha, created, nested)
        val gate = repository.hold("create:${alpha.id}:Архив")
        repository.calls.clear()
        viewModel.submitEdit()
        assertTrue(state.edit!!.saving)
        assertEquals(listOf(nested), state.folders)

        gate.complete(Unit)

        assertNull(state.edit)
        assertEquals(listOf("create:${alpha.id}:Архив", "children:${alpha.id}", "files:${alpha.id}:0"), repository.calls)
        assertEquals(listOf(root, alpha), state.path)
        assertEquals(listOf(created, nested), state.folders)
    }

    @Test
    fun nameConflictKeepsDialogAndTypedName() {
        repository.setChildren(root, alpha)
        opened()
        repository.fail("create:${root.id}:Alpha", LibraryResult.NameTaken)

        viewModel.startCreateFolder()
        viewModel.onEditNameChange("Alpha")
        repository.calls.clear()
        viewModel.submitEdit()

        val edit = state.edit!!
        assertEquals("Alpha", edit.name)
        assertFalse(edit.saving)
        assertEquals("Имя уже занято файлом или папкой", edit.error)
        // Список не перечитывается, автоматического переименования нет.
        assertEquals(listOf("create:${root.id}:Alpha"), repository.calls)
        assertEquals(listOf(alpha), state.folders)

        // Исправленное имя отправляется без повторного открытия диалога.
        viewModel.onEditNameChange("Alpha 2")
        assertNull(state.edit!!.error)
        viewModel.submitEdit()
        assertNull(state.edit)
        assertEquals("create:${root.id}:Alpha 2", repository.calls[1])
    }

    @Test
    fun renameFileReloadsFirstPageInServerOrder() {
        repository.setChildren(root, alpha)
        repository.setFiles(alpha, 0, page(listOf(file(10), file(11)), hasNext = true))
        repository.setFiles(alpha, 1, page(listOf(file(12))))
        opened().openFolder(alpha)
        viewModel.loadNextPage()
        assertEquals(listOf(10L, 11L, 12L), fileIds())

        val target = state.files.first { it.id == 12L }
        viewModel.startRename(target)
        assertEquals("file12.txt", state.edit?.name)
        viewModel.onEditNameChange("a.TXT")
        // Server сортирует по имени: переименованный файл переходит в начало первой страницы.
        repository.setFiles(alpha, 0, page(listOf(file(12).copy(originalFilename = "a.TXT"), file(10)), hasNext = true))
        repository.setFiles(alpha, 1, page(listOf(file(11))))
        repository.calls.clear()

        viewModel.submitEdit()

        assertNull(state.edit)
        // Без загрузки всех страниц: только первая, дальше — обычная пагинация.
        assertEquals(listOf("renameFile:12:a.TXT", "children:${alpha.id}", "files:${alpha.id}:0"), repository.calls)
        assertEquals(listOf(12L, 10L), fileIds())
        assertEquals("a.TXT", state.files.first().originalFilename)
        assertTrue(state.hasNext)
        assertEquals(listOf(root, alpha), state.path)

        viewModel.loadNextPage()
        assertEquals(listOf(12L, 10L, 11L), fileIds())
    }

    @Test
    fun renameFolderKeepsItsIdAndNavigation() {
        repository.setChildren(root, alpha)
        repository.setChildren(alpha, nested)
        opened().openFolder(alpha)

        viewModel.startRename(nested)
        assertEquals("Nested", state.edit?.name)
        viewModel.onEditNameChange("Вложенная")
        val renamed = nested.copy(name = "Вложенная")
        repository.setChildren(alpha, renamed)
        viewModel.submitEdit()

        assertNull(state.edit)
        assertTrue(repository.calls.contains("renameFolder:${nested.id}:Вложенная"))
        assertEquals(listOf(renamed), state.folders)
        assertEquals(listOf(root, alpha), state.path)

        viewModel.openFolder(renamed)
        assertEquals(listOf(root, alpha, renamed), state.path)
    }

    @Test
    fun unchangedNameClosesDialogWithoutRequest() {
        repository.setChildren(root, alpha)
        opened()
        repository.calls.clear()

        viewModel.startRename(alpha)
        viewModel.submitEdit()

        assertNull(state.edit)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun invalidNameIsRejectedBeforeRequest() {
        repository.setChildren(root, alpha)
        opened()
        repository.calls.clear()

        viewModel.startCreateFolder()
        viewModel.submitEdit()
        assertEquals("Пустое имя", state.edit?.error)

        viewModel.onEditNameChange("a/b")
        viewModel.submitEdit()
        assertTrue(state.edit!!.error!!.startsWith("Имя содержит"))

        viewModel.onEditNameChange("camera")
        viewModel.submitEdit()
        assertEquals("Имя Camera в корне зарезервировано", state.edit?.error)
        assertEquals("camera", state.edit?.name)

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun networkErrorDoesNotChangeListAndRetryUsesSameName() {
        repository.setChildren(root, alpha)
        opened()
        repository.fail("renameFolder:${alpha.id}:Альфа", LibraryResult.NetworkError("timeout"))
        val before = state.folders
        val version = state.contentVersion

        viewModel.startRename(alpha)
        viewModel.onEditNameChange("Альфа")
        repository.calls.clear()
        viewModel.submitEdit()

        assertEquals("Нет связи с сервером: timeout", state.edit?.error)
        assertEquals("Альфа", state.edit?.name)
        assertEquals(before, state.folders)
        assertEquals(version, state.contentVersion)
        assertEquals(listOf("renameFolder:${alpha.id}:Альфа"), repository.calls)

        repository.succeed("renameFolder:${alpha.id}:Альфа")
        repository.setChildren(root, alpha.copy(name = "Альфа"))
        viewModel.submitEdit()

        assertNull(state.edit)
        assertEquals(listOf(alpha.copy(name = "Альфа")), state.folders)
    }

    @Test
    fun repeatedConfirmationWhileSavingSendsOneRequest() {
        opened()
        viewModel.startCreateFolder()
        viewModel.onEditNameChange("Новая")
        val gate = repository.hold("create:${root.id}:Новая")
        repository.calls.clear()

        viewModel.submitEdit()
        viewModel.submitEdit()
        // Во время запроса имя не меняется и диалог не закрывается.
        viewModel.onEditNameChange("Другая")
        viewModel.dismissEdit()
        assertEquals("Новая", state.edit?.name)
        repository.setChildren(root, FolderDto(30, root.id, "Новая", "USER"))
        gate.complete(Unit)

        assertEquals(1, repository.calls.count { it.startsWith("create:") })
        assertNull(state.edit)
        assertEquals(listOf(30L), state.folders.map { it.id })
    }

    @Test
    fun managedAndRejectedRefusalsAreExplained() {
        repository.setChildren(root, alpha)
        opened()
        repository.fail("renameFolder:${alpha.id}:X", LibraryResult.Managed)
        viewModel.startRename(alpha)
        viewModel.onEditNameChange("X")
        viewModel.submitEdit()
        assertEquals("Папку изменяет только автоматическое резервирование", state.edit?.error)

        repository.fail("renameFolder:${alpha.id}:Y", LibraryResult.Rejected("Cannot rename system folder"))
        viewModel.onEditNameChange("Y")
        viewModel.submitEdit()
        assertEquals("Сервер отклонил операцию: Cannot rename system folder", state.edit?.error)

        repository.fail("renameFolder:${alpha.id}:Z", LibraryResult.ServerError("boom"))
        viewModel.onEditNameChange("Z")
        viewModel.submitEdit()
        assertEquals("Ошибка сервера: boom", state.edit?.error)
        assertEquals(listOf(alpha), state.folders)
    }

    @Test
    fun missingObjectClosesDialogAndReloadsFolderWithNotice() {
        repository.setChildren(root, alpha)
        repository.setFiles(root, 0, page(listOf(file(10))))
        opened()
        repository.fail("renameFile:10:new.txt", LibraryResult.NotFound)
        viewModel.startRename(state.files.single())
        viewModel.onEditNameChange("new.txt")
        repository.setFiles(root, 0, page(emptyList()))
        repository.calls.clear()

        viewModel.submitEdit()

        assertNull(state.edit)
        assertEquals("Файл «file10.txt» не найден: возможно, он удалён или перемещён", state.notice)
        assertEquals(listOf("renameFile:10:new.txt", "children:1", "files:1:0"), repository.calls)
        assertTrue(state.files.isEmpty())
        assertEquals(listOf(root), state.path)
    }

    @Test
    fun rejectedSessionDuringChangeAsksToLogIn() {
        repository.setChildren(root, alpha)
        opened()
        repository.fail("renameFolder:${alpha.id}:X", LibraryResult.Unauthorized)
        viewModel.startRename(alpha)
        viewModel.onEditNameChange("X")

        viewModel.submitEdit()

        assertEquals(LibraryStatus.NoSession, state.status)
        assertNull(state.edit)
    }

    @Test
    fun changesAreNotOfferedInsideActiveSourceOrForSystemFolders() {
        val (autoUploads, source, nestedSource) = openSourceRoot()
        repository.setFiles(nestedSource, 0, page(listOf(file(40))))
        viewModel.navigateUp()
        assertEquals(listOf(root, autoUploads), state.path)
        viewModel.startRename(source)
        viewModel.startCreateFolder()
        assertNull(state.edit)

        viewModel.openFolder(source)
        viewModel.startRename(nestedSource)
        viewModel.startCreateFolder()
        assertNull(state.edit)

        viewModel.openFolder(nestedSource)
        viewModel.startRename(state.files.single())
        viewModel.startCreateFolder()
        viewModel.submitEdit()
        assertNull(state.edit)

        viewModel.navigateUp()
        viewModel.navigateUp()
        viewModel.navigateUp()
        assertEquals(listOf(root), state.path)
        viewModel.startRename(camera)
        assertNull(state.edit)
        viewModel.startRename(autoUploads)
        assertNull(state.edit)

        assertTrue(repository.calls.none { it.startsWith("create") || it.startsWith("rename") })
    }

    @Test
    fun disabledSourceAllowsOrdinaryChanges() {
        val autoUploads = FolderDto(20, 1, "AutoUploads", "AUTO_UPLOADS")
        val disabled = FolderDto(21, 20, "Documents", "USER")
        repository.setChildren(root, autoUploads)
        repository.setChildren(autoUploads, disabled)
        repository.setFiles(disabled, 0, page(listOf(file(40))))
        opened().openFolder(autoUploads)

        viewModel.startRename(disabled)
        viewModel.onEditNameChange("Документы")
        viewModel.submitEdit()
        assertTrue(repository.calls.contains("renameFolder:${disabled.id}:Документы"))

        viewModel.openFolder(disabled)
        viewModel.startCreateFolder()
        viewModel.onEditNameChange("Новая")
        viewModel.submitEdit()
        viewModel.startRename(state.files.single())
        viewModel.onEditNameChange("file40.md")
        viewModel.submitEdit()

        assertTrue(repository.calls.contains("create:${disabled.id}:Новая"))
        assertTrue(repository.calls.contains("renameFile:40:file40.md"))
        assertNull(state.edit)
    }

    @Test
    fun folderRefreshDuringOpenDialogKeepsTypedName() {
        repository.setChildren(root, alpha)
        opened()
        viewModel.startCreateFolder()
        viewModel.onEditNameChange("Черновик")

        viewModel.refresh()

        assertEquals("Черновик", state.edit?.name)
        assertEquals(LibraryStatus.Content, state.status)
    }

    @Test
    fun sessionChangeDuringChangeDropsLateResult() {
        repository.setChildren(root, alpha)
        opened()
        viewModel.startCreateFolder()
        viewModel.onEditNameChange("Новая")
        val gate = repository.hold("create:${root.id}:Новая")
        viewModel.submitEdit()

        tokens.saveTokens(tokensOf("bob"))
        repository.calls.clear()
        gate.complete(Unit)

        assertNull(state.edit)
        assertEquals(LibraryStatus.Idle, state.status)
        assertTrue(repository.calls.isEmpty())
    }

    // --- Перемещение и копирование (этап 6.2) ---

    private fun moveTo(target: FolderDto) {
        viewModel.navigateUp()
        viewModel.openFolder(target)
    }

    @Test
    fun movedFileLeavesSourceAfterServerConfirmation() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10), file(11))))
        opened().openFolder(alpha)

        viewModel.startMove(state.files.first())
        // Выбор начинается в папке файла: туда переместить нельзя.
        assertTrue(state.picking)
        assertEquals(listOf(root, alpha), state.path)
        assertEquals("Файл уже находится здесь", state.pickProblem)
        viewModel.requestRelocationConfirm()
        assertFalse(state.relocation!!.confirming)

        moveTo(beta)
        assertNull(state.pickProblem)
        viewModel.requestRelocationConfirm()
        assertTrue(state.relocation!!.confirming)
        repository.setFiles(alpha, 0, page(listOf(file(11))))
        repository.calls.clear()
        viewModel.confirmRelocation()

        assertNull(state.relocation)
        assertEquals(listOf("moveFile:10:${beta.id}", "children:${alpha.id}", "files:${alpha.id}:0"), repository.calls)
        assertEquals(listOf(root, alpha), state.path)
        assertEquals(listOf(11L), fileIds())
        assertEquals("Файл «file10.txt» перемещён в «Beta»", state.info)
    }

    @Test
    fun copiedFileKeepsSourceListWithoutReload() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10), file(11)), hasNext = true))
        repository.setFiles(alpha, 1, page(listOf(file(12))))
        opened().openFolder(alpha)
        viewModel.loadNextPage()
        viewModel.saveScroll(2, 15)
        val before = state

        viewModel.startCopy(state.files.first())
        assertEquals("file10.txt", state.relocation!!.copyName)
        moveTo(beta)
        viewModel.requestRelocationConfirm()
        repository.calls.clear()
        viewModel.confirmRelocation()

        // Исходный каталог не менялся: возвращается без запросов, с прежними страницами и прокруткой.
        assertEquals(listOf("copyFile:10:${beta.id}:file10.txt"), repository.calls)
        assertNull(state.relocation)
        assertEquals(before.path, state.path)
        assertEquals(listOf(10L, 11L, 12L), fileIds())
        assertEquals(before.hasNext, state.hasNext)
        assertEquals(2, viewModel.scrollIndex)
        assertEquals(15, viewModel.scrollOffset)
        assertEquals("Копия «file10.txt» создана в «Beta»", state.info)
    }

    @Test
    fun copyIntoSameFolderNeedsNewName() {
        repository.setChildren(root, alpha)
        repository.setFiles(alpha, 0, page(listOf(file(10))))
        opened().openFolder(alpha)

        viewModel.startCopy(state.files.single())
        assertNull(state.pickProblem)
        viewModel.requestRelocationConfirm()
        repository.calls.clear()
        viewModel.confirmRelocation()
        assertEquals("Копия в той же папке должна называться иначе", state.relocation!!.error)
        assertTrue(repository.calls.isEmpty())

        viewModel.onCopyNameChange("file10 (копия).txt")
        repository.setFiles(alpha, 0, page(listOf(file(10), FileItemDto(id = 99, originalFilename = "file10 (копия).txt"))))
        viewModel.confirmRelocation()

        assertEquals(
            listOf("copyFile:10:${alpha.id}:file10 (копия).txt", "children:${alpha.id}", "files:${alpha.id}:0"),
            repository.calls
        )
        // Копия получила новый ID Server, исходный файл на месте.
        assertEquals(listOf(10L, 99L), fileIds())
    }

    @Test
    fun movedFolderKeepsIdAndSourceIsReloaded() {
        repository.setChildren(root, alpha, beta)
        repository.setChildren(alpha, nested)
        opened()

        viewModel.startMove(alpha)
        assertEquals("Папка уже находится здесь", state.pickProblem)
        viewModel.openFolder(beta)
        viewModel.requestRelocationConfirm()
        repository.setChildren(root, beta)
        repository.setChildren(beta, alpha.copy(parentId = beta.id))
        repository.calls.clear()
        viewModel.confirmRelocation()

        assertEquals(listOf("moveFolder:${alpha.id}:${beta.id}", "children:${root.id}", "files:${root.id}:0"), repository.calls)
        assertEquals(listOf(root), state.path)
        assertEquals(listOf(beta), state.folders)
        viewModel.openFolder(beta)
        assertEquals(alpha.id, state.folders.single().id)
    }

    @Test
    fun folderCannotBeMovedIntoItsSubtree() {
        repository.setChildren(root, alpha, beta)
        repository.setChildren(alpha, nested)
        opened()
        viewModel.startMove(alpha)

        // Экран не открывает саму папку; даже если путь в неё ведёт, подтверждения нет.
        assertNotNull(blockedFolderProblem(state.relocation!!, state.path, alpha))
        viewModel.openFolder(alpha)
        viewModel.openFolder(nested)
        assertEquals("Нельзя переместить папку в саму себя или во вложенную папку", state.pickProblem)
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()

        assertTrue(repository.calls.none { it.startsWith("moveFolder") })
    }

    @Test
    fun nameConflictKeepsDestinationAndDialog() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10))))
        opened().openFolder(alpha)
        viewModel.startCopy(state.files.single())
        moveTo(beta)
        repository.fail("copyFile:10:${beta.id}:file10.txt", LibraryResult.NameTaken)
        viewModel.requestRelocationConfirm()
        val version = state.contentVersion

        viewModel.confirmRelocation()

        val relocation = state.relocation!!
        assertEquals("Имя уже занято файлом или папкой", relocation.error)
        assertTrue(relocation.confirming)
        assertFalse(relocation.saving)
        assertEquals(listOf(root, beta), state.path)
        assertEquals(version, state.contentVersion)

        // Пользователь сам меняет имя: суффиксы не добавляются автоматически.
        viewModel.onCopyNameChange("file10-2.txt")
        viewModel.confirmRelocation()
        assertTrue(repository.calls.contains("copyFile:10:${beta.id}:file10-2.txt"))
        assertNull(state.relocation)
    }

    @Test
    fun activeSourceIsNotDestinationAndManagedRefusalIsShown() {
        val (autoUploads, source, nestedSource) = openSourceRoot()
        repository.setChildren(root, camera, autoUploads, alpha)
        viewModel.navigateUp()
        viewModel.navigateUp()
        viewModel.openFolder(alpha)
        repository.setFiles(alpha, 0, page(listOf(file(10))))
        viewModel.refresh()
        viewModel.startMove(state.files.single())
        viewModel.navigateUp()
        viewModel.openFolder(autoUploads)
        viewModel.openFolder(source)
        assertEquals("Папку изменяет только автоматическое резервирование", state.pickProblem)
        viewModel.openFolder(nestedSource)
        assertEquals("Папку изменяет только автоматическое резервирование", state.pickProblem)
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()
        assertTrue(repository.calls.none { it.startsWith("moveFile") })

        // Защиту, о которой Android не знал, сообщает Server.
        viewModel.navigateUp()
        viewModel.navigateUp()
        viewModel.navigateUp()
        viewModel.openFolder(beta)
        repository.fail("moveFile:10:${beta.id}", LibraryResult.Managed)
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()
        assertEquals("Папку изменяет только автоматическое резервирование", state.relocation!!.error)
    }

    @Test
    fun disabledSourceMovesFromAutoUploadsToLibrary() {
        val autoUploads = FolderDto(20, 1, "AutoUploads", "AUTO_UPLOADS")
        val disabled = FolderDto(21, 20, "OldPhone", "USER")
        repository.setChildren(root, autoUploads, alpha)
        repository.setChildren(autoUploads, disabled)
        opened().openFolder(autoUploads)

        viewModel.startMove(disabled)
        assertEquals("В AutoUploads размещаются только источники резервирования", state.pickProblem)
        viewModel.navigateUp()
        viewModel.openFolder(alpha)
        viewModel.requestRelocationConfirm()
        repository.setChildren(autoUploads)
        viewModel.confirmRelocation()

        assertTrue(repository.calls.contains("moveFolder:${disabled.id}:${alpha.id}"))
        assertEquals(listOf(root, autoUploads), state.path)
        assertTrue(state.folders.isEmpty())
    }

    @Test
    fun cameraFileIsOnlyCopiedIntoLibrary() {
        repository.setChildren(root, camera, alpha)
        repository.setFiles(camera, 0, page(listOf(file(30))))
        opened().openFolder(camera)
        val photo = state.files.single()

        viewModel.startMove(photo)
        assertNull(state.relocation)

        viewModel.startCopy(photo)
        assertNotNull(state.pickProblem)
        viewModel.navigateUp()
        viewModel.openFolder(alpha)
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()

        assertTrue(repository.calls.contains("copyFile:30:${alpha.id}:file30.txt"))
        assertEquals(listOf(root, camera), state.path)
    }

    @Test
    fun refusalsKeepDestinationAndLibraryUnchanged() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10))))
        opened().openFolder(alpha)
        viewModel.startMove(state.files.single())
        moveTo(beta)
        viewModel.requestRelocationConfirm()
        val key = "moveFile:10:${beta.id}"

        repository.fail(key, LibraryResult.Rejected("Cannot move file between Camera and library folders"))
        viewModel.confirmRelocation()
        assertEquals("Сервер отклонил операцию: Cannot move file between Camera and library folders", state.relocation!!.error)

        repository.fail(key, LibraryResult.ServerError("Physical storage operation failed"))
        viewModel.confirmRelocation()
        assertEquals("Ошибка сервера: Physical storage operation failed", state.relocation!!.error)
        assertFalse(state.relocation!!.unknownResult)
        assertEquals(listOf(root, beta), state.path)
        assertEquals(2, repository.calls.count { it == key })

        // Отмена после отправленных запросов перечитывает исходную папку.
        repository.calls.clear()
        viewModel.cancelRelocation()
        assertNull(state.relocation)
        assertEquals(listOf(root, alpha), state.path)
        assertEquals(listOf("children:${alpha.id}", "files:${alpha.id}:0"), repository.calls)
    }

    @Test
    fun missingObjectReturnsToSourceWithNotice() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10))))
        opened().openFolder(alpha)
        viewModel.startMove(state.files.single())
        moveTo(beta)
        repository.fail("moveFile:10:${beta.id}", LibraryResult.NotFound)
        repository.setFiles(alpha, 0, page(emptyList()))
        viewModel.requestRelocationConfirm()

        viewModel.confirmRelocation()

        assertNull(state.relocation)
        assertEquals(listOf(root, alpha), state.path)
        assertTrue(state.files.isEmpty())
        assertEquals("Объект или папка назначения не найдены: возможно, они удалены или перемещены", state.notice)
    }

    @Test
    fun cancelRestoresSourceWithoutRequests() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10), file(11)), hasNext = true))
        repository.setFiles(alpha, 1, page(listOf(file(12))))
        opened().openFolder(alpha)
        viewModel.loadNextPage()
        viewModel.saveScroll(1, 7)
        val before = state

        viewModel.startMove(state.files.first())
        moveTo(beta)
        repository.calls.clear()
        viewModel.cancelRelocation()

        assertTrue(repository.calls.isEmpty())
        assertNull(state.relocation)
        assertEquals(before.path, state.path)
        assertEquals(before.files, state.files)
        assertEquals(1, viewModel.scrollIndex)
        assertEquals(7, viewModel.scrollOffset)
        // Догрузка продолжается с той же страницы.
        assertFalse(state.hasNext)
    }

    @Test
    fun sessionChangeDuringRelocationDropsLateResult() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10))))
        opened().openFolder(alpha)
        viewModel.startMove(state.files.single())
        moveTo(beta)
        viewModel.requestRelocationConfirm()
        val gate = repository.hold("moveFile:10:${beta.id}")
        viewModel.confirmRelocation()

        baseUrlProvider.baseUrl = SERVER_B
        repository.calls.clear()
        gate.complete(Unit)

        assertNull(state.relocation)
        assertEquals(LibraryStatus.Idle, state.status)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun destinationFilesArePagedDuringSelection() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10))))
        repository.setFiles(beta, 0, page(listOf(file(20), file(21)), hasNext = true))
        repository.setFiles(beta, 1, page(listOf(file(22))))
        opened().openFolder(alpha)
        viewModel.startCopy(state.files.single())

        moveTo(beta)
        assertEquals(listOf(20L, 21L), fileIds())
        viewModel.onListEndReached()

        assertEquals(listOf(20L, 21L, 22L), fileIds())
        assertTrue(state.picking)
        assertNull(state.pickProblem)
    }

    @Test
    fun unknownResultIsNotRepeatedAutomatically() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10))))
        opened().openFolder(alpha)
        viewModel.startCopy(state.files.single())
        moveTo(beta)
        val key = "copyFile:10:${beta.id}:file10.txt"
        repository.fail(key, LibraryResult.NetworkError("timeout"))
        viewModel.requestRelocationConfirm()

        viewModel.confirmRelocation()

        val relocation = state.relocation!!
        assertTrue(relocation.unknownResult)
        assertFalse(relocation.confirming)
        assertTrue(relocation.error!!.contains("результат неизвестен"))
        // Повтор недоступен, пока папка не обновлена.
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()
        assertEquals(1, repository.calls.count { it == key })

        // Обновление показывает фактическое состояние назначения: копия уже есть.
        repository.setFiles(beta, 0, page(listOf(FileItemDto(id = 50, originalFilename = "file10.txt"))))
        viewModel.refresh()
        assertFalse(state.relocation!!.unknownResult)
        assertEquals(listOf(50L), fileIds())
        assertEquals(1, repository.calls.count { it == key })

        // Повтор — только явным подтверждением пользователя; Server отвечает конфликтом, дубликата нет.
        repository.fail(key, LibraryResult.NameTaken)
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()
        assertEquals(2, repository.calls.count { it == key })
        assertEquals("Имя уже занято файлом или папкой", state.relocation!!.error)
    }

    @Test
    fun unreadableSuccessKeepsSelectionAndBlocksRepeat() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10), file(11))))
        opened().openFolder(alpha)
        viewModel.startMove(state.files.first())
        moveTo(beta)
        val key = "moveFile:10:${beta.id}"
        // Server переместил файл, но ответ не разобран.
        repository.fail(key, LibraryResult.UnknownResult("Expected BEGIN_OBJECT"))
        repository.setFiles(alpha, 0, page(listOf(file(11))))
        repository.setFiles(beta, 0, page(listOf(file(10))))
        viewModel.requestRelocationConfirm()
        val version = state.contentVersion
        repository.calls.clear()

        viewModel.confirmRelocation()

        // Выбор назначения сохранён, Library не перечитана и не объявила успех.
        val relocation = state.relocation!!
        assertEquals(listOf(key), repository.calls)
        assertTrue(relocation.unknownResult)
        assertFalse(relocation.confirming)
        assertFalse(relocation.saving)
        assertTrue(relocation.error!!.startsWith("Ответ сервера не разобран (Expected BEGIN_OBJECT), результат неизвестен"))
        assertEquals(listOf(root, beta), state.path)
        assertEquals(version, state.contentVersion)
        assertNull(state.info)

        // Без обновления подтверждение недоступно, автоматического повтора нет.
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()
        assertEquals(listOf(key), repository.calls)

        // Обновление показывает, что файл уже в назначении; отмена перечитывает исходную папку.
        viewModel.refresh()
        assertFalse(state.relocation!!.unknownResult)
        assertEquals(listOf(10L), fileIds())
        viewModel.cancelRelocation()
        assertNull(state.relocation)
        assertEquals(listOf(root, alpha), state.path)
        assertEquals(listOf(11L), fileIds())
        assertEquals(1, repository.calls.count { it == key })
    }

    @Test
    fun unreadableCopyAndFolderMoveAreUnknownToo() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10))))
        opened().openFolder(alpha)
        viewModel.startCopy(state.files.single())
        moveTo(beta)
        repository.fail("copyFile:10:${beta.id}:file10.txt", LibraryResult.UnknownResult("malformed"))
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()
        assertTrue(state.relocation!!.unknownResult)
        viewModel.refresh()
        viewModel.cancelRelocation()

        viewModel.navigateUp()
        viewModel.startMove(alpha)
        viewModel.openFolder(beta)
        repository.fail("moveFolder:${alpha.id}:${beta.id}", LibraryResult.UnknownResult("malformed"))
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()
        assertTrue(state.relocation!!.unknownResult)
        assertEquals(1, repository.calls.count { it.startsWith("copyFile") })
        assertEquals(1, repository.calls.count { it.startsWith("moveFolder") })
    }

    @Test
    fun repeatedConfirmationWhileRelocatingSendsOneRequest() {
        repository.setChildren(root, alpha, beta)
        repository.setFiles(alpha, 0, page(listOf(file(10))))
        opened().openFolder(alpha)
        viewModel.startMove(state.files.single())
        moveTo(beta)
        viewModel.requestRelocationConfirm()
        val gate = repository.hold("moveFile:10:${beta.id}")

        viewModel.confirmRelocation()
        viewModel.confirmRelocation()
        viewModel.cancelRelocation()
        viewModel.dismissRelocationConfirm()
        assertTrue(state.relocation!!.saving)
        gate.complete(Unit)

        assertEquals(1, repository.calls.count { it.startsWith("moveFile") })
        assertNull(state.relocation)
    }

    // --- Удаление (этап 6.3) ---

    @Test
    fun deletedFileDisappearsAfterServerConfirmation() {
        repository.setChildren(root, alpha)
        repository.setFiles(alpha, 0, page(listOf(file(10), file(11))))
        opened().openFolder(alpha)

        viewModel.startDelete(state.files.first())
        assertEquals(LibraryItem.File(file(10)), state.deletion!!.target)
        // До подтверждения запросов нет, строка на месте.
        repository.setFiles(alpha, 0, page(listOf(file(11))))
        repository.calls.clear()
        viewModel.confirmDelete()

        assertNull(state.deletion)
        assertEquals(listOf("deleteFile:10", "children:${alpha.id}", "files:${alpha.id}:0"), repository.calls)
        assertEquals(listOf(root, alpha), state.path)
        assertEquals(listOf(11L), fileIds())
        assertEquals("Файл «file10.txt» удалён", state.info)
    }

    @Test
    fun deletedFolderWithContentDisappears() {
        repository.setChildren(root, alpha, beta)
        repository.setChildren(alpha, nested)
        repository.setFiles(alpha, 0, page(listOf(file(10))))
        opened()

        viewModel.startDelete(alpha)
        repository.setChildren(root, beta)
        repository.calls.clear()
        viewModel.confirmDelete()

        // Поддерево удаляет Server одним запросом; Android вложенные объекты не перебирает.
        assertEquals(listOf("deleteFolder:${alpha.id}", "children:${root.id}", "files:${root.id}:0"), repository.calls)
        assertEquals(listOf(beta), state.folders)
        assertEquals("Папка «Alpha» удалена", state.info)

        // Пустая папка — тот же запрос.
        viewModel.startDelete(beta)
        repository.setChildren(root)
        viewModel.confirmDelete()
        assertTrue(repository.calls.contains("deleteFolder:${beta.id}"))
        assertTrue(state.folders.isEmpty())
    }

    @Test
    fun cancelledDeletionChangesNothing() {
        repository.setChildren(root, alpha)
        repository.setFiles(root, 0, page(listOf(file(10))))
        opened()
        val before = state
        repository.calls.clear()

        viewModel.startDelete(state.files.single())
        viewModel.dismissDelete()
        viewModel.startDelete(alpha)
        viewModel.dismissDelete()

        assertTrue(repository.calls.isEmpty())
        assertEquals(before, state)
    }

    @Test
    fun repeatedConfirmationWhileDeletingSendsOneRequest() {
        repository.setFiles(root, 0, page(listOf(file(10))))
        opened()
        viewModel.startDelete(state.files.single())
        val gate = repository.hold("deleteFile:10")

        viewModel.confirmDelete()
        viewModel.confirmDelete()
        viewModel.dismissDelete()
        assertTrue(state.deletion!!.deleting)
        repository.setFiles(root, 0, page(emptyList()))
        gate.complete(Unit)

        assertEquals(1, repository.calls.count { it == "deleteFile:10" })
        assertNull(state.deletion)
        assertTrue(state.files.isEmpty())
    }

    @Test
    fun systemFoldersAndActiveTreeAreNotDeleted() {
        val (autoUploads, source, nestedSource) = openSourceRoot()
        repository.setFiles(source, 0, page(listOf(file(40))))
        viewModel.refresh()
        viewModel.startDelete(nestedSource)
        viewModel.startDelete(state.files.single())
        viewModel.navigateUp()
        viewModel.startDelete(source)
        viewModel.navigateUp()
        viewModel.startDelete(camera)
        viewModel.startDelete(autoUploads)
        viewModel.startDelete(root)

        assertNull(state.deletion)
        assertTrue(repository.calls.none { it.startsWith("delete") })
    }

    @Test
    fun disabledSourceAndCameraFileAreDeletedAsServerAllows() {
        val autoUploads = FolderDto(20, 1, "AutoUploads", "AUTO_UPLOADS")
        val disabled = FolderDto(21, 20, "OldPhone", "USER")
        repository.setChildren(root, camera, autoUploads)
        repository.setChildren(autoUploads, disabled)
        repository.setFiles(camera, 0, page(listOf(file(30))))
        opened().openFolder(autoUploads)

        viewModel.startDelete(disabled)
        viewModel.confirmDelete()
        assertTrue(repository.calls.contains("deleteFolder:${disabled.id}"))

        viewModel.navigateUp()
        viewModel.openFolder(camera)
        viewModel.startDelete(state.files.single())
        assertNotNull(state.deletion)
        viewModel.confirmDelete()
        assertTrue(repository.calls.contains("deleteFile:30"))
        assertNull(state.deletion)
    }

    @Test
    fun refusalsKeepDialogAndList() {
        repository.setChildren(root, alpha)
        repository.setFiles(root, 0, page(listOf(file(10))))
        opened()
        viewModel.startDelete(state.files.single())
        val version = state.contentVersion

        repository.fail("deleteFile:10", LibraryResult.Rejected("Object is busy or not accessible, try again later"))
        viewModel.confirmDelete()
        assertEquals("Сервер отклонил операцию: Object is busy or not accessible, try again later", state.deletion!!.error)

        repository.fail("deleteFile:10", LibraryResult.Managed)
        viewModel.confirmDelete()
        assertEquals("Папку изменяет только автоматическое резервирование", state.deletion!!.error)

        // 5xx не повторяется сам, повтор — новым подтверждением пользователя.
        repository.fail("deleteFile:10", LibraryResult.ServerError("Physical storage operation failed"))
        viewModel.confirmDelete()
        assertEquals("Ошибка сервера: Physical storage operation failed", state.deletion!!.error)
        assertFalse(state.deletion!!.deleting)
        assertFalse(state.deletion!!.unknownResult)

        assertEquals(3, repository.calls.count { it == "deleteFile:10" })
        assertEquals(version, state.contentVersion)
        assertEquals(listOf(10L), fileIds())
        assertEquals(listOf(alpha), state.folders)
    }

    @Test
    fun missingObjectClosesDialogAndReloads() {
        repository.setFiles(root, 0, page(listOf(file(10))))
        opened()
        viewModel.startDelete(state.files.single())
        repository.fail("deleteFile:10", LibraryResult.NotFound)
        repository.setFiles(root, 0, page(emptyList()))
        repository.calls.clear()

        viewModel.confirmDelete()

        assertNull(state.deletion)
        assertEquals("«file10.txt» не найден: возможно, он уже удалён", state.notice)
        assertEquals(listOf("deleteFile:10", "children:1", "files:1:0"), repository.calls)
        assertTrue(state.files.isEmpty())
    }

    @Test
    fun lostResponseAfterDeletionIsUnknownAndNotRepeated() {
        repository.setChildren(root, alpha)
        repository.setChildren(alpha, nested)
        opened().openFolder(alpha)
        viewModel.startDelete(nested)
        // Server удалил папку, но ответ потерян.
        repository.fail("deleteFolder:${nested.id}", LibraryResult.NetworkError("timeout"))
        repository.setChildren(alpha)
        val version = state.contentVersion
        repository.calls.clear()

        viewModel.confirmDelete()

        val deletion = state.deletion!!
        assertTrue(deletion.unknownResult)
        assertFalse(deletion.deleting)
        assertTrue(deletion.error!!.startsWith("Связь прервалась (timeout), результат неизвестен"))
        assertEquals(version, state.contentVersion)
        assertEquals(listOf(nested), state.folders)

        // Повтор недоступен: только обновление.
        viewModel.confirmDelete()
        assertEquals(listOf("deleteFolder:${nested.id}"), repository.calls)

        viewModel.refreshAfterDelete()
        assertNull(state.deletion)
        assertTrue(state.folders.isEmpty())
        // Исчезнувшая строка ошибкой не считается.
        assertNull(state.notice)
        assertEquals(1, repository.calls.count { it.startsWith("deleteFolder") })
        assertEquals(listOf(root, alpha), state.path)
    }

    @Test
    fun deletingLastItemOfPageKeepsPaginationConsistent() {
        repository.setFiles(root, 0, page(listOf(file(10), file(11)), hasNext = true))
        repository.setFiles(root, 1, page(listOf(file(12))))
        opened()
        viewModel.loadNextPage()
        assertEquals(listOf(10L, 11L, 12L), fileIds())

        viewModel.startDelete(state.files.last())
        repository.setFiles(root, 0, page(listOf(file(10), file(11)), hasNext = false))
        repository.setFiles(root, 1, page(emptyList()))
        repository.calls.clear()
        viewModel.confirmDelete()

        // Перечитывается только первая страница; удалённый файл не возвращается.
        assertEquals(listOf("deleteFile:12", "children:1", "files:1:0"), repository.calls)
        assertEquals(listOf(10L, 11L), fileIds())
        assertFalse(state.hasNext)
    }

    @Test
    fun sessionChangeDuringDeletionDropsLateResult() {
        repository.setFiles(root, 0, page(listOf(file(10))))
        opened()
        viewModel.startDelete(state.files.single())
        val gate = repository.hold("deleteFile:10")
        viewModel.confirmDelete()

        tokens.saveTokens(tokensOf("bob"))
        repository.calls.clear()
        gate.complete(Unit)

        assertNull(state.deletion)
        assertEquals(LibraryStatus.Idle, state.status)
        assertTrue(repository.calls.isEmpty())
        assertNull(state.info)
    }

    @Test
    fun sessionChangeBeforeConfirmationSendsNothing() {
        repository.setFiles(root, 0, page(listOf(file(10))))
        opened()
        viewModel.startDelete(state.files.single())

        baseUrlProvider.baseUrl = SERVER_B
        viewModel.confirmDelete()

        assertNull(state.deletion)
        assertTrue(repository.calls.none { it.startsWith("delete") })
    }

    // --- Множественное выделение и групповые операции (этап 6.4) ---

    private val gamma = FolderDto(6, 1, "Gamma", "USER")

    private fun outcomes(): List<BatchOutcome> = state.batch!!.entries.map { it.outcome }

    /** alpha с файлами 10, 11, 12; в корне ещё beta и gamma. */
    private fun openAlphaWithFiles() {
        repository.setChildren(root, alpha, beta, gamma)
        repository.setChildren(alpha, nested)
        repository.setFiles(alpha, 0, page(listOf(file(10), file(11), file(12))))
        opened().openFolder(alpha)
    }

    private fun selectFiles(vararg ids: Long) = ids.forEach { id -> viewModel.toggleSelection(state.files.first { it.id == id }) }

    @Test
    fun longPressEntersSelectionAndTapsToggle() {
        openAlphaWithFiles()

        viewModel.toggleSelection(state.files[0])
        assertTrue(state.selecting)
        assertEquals(setOf(10L), state.selection!!.files.keys)
        viewModel.toggleSelection(state.files[1])
        viewModel.toggleSelection(nested)
        assertEquals(3, state.selection!!.size)
        viewModel.toggleSelection(state.files[0])
        assertEquals(setOf(11L), state.selection!!.files.keys)
        // Одиночные меню в режиме выделения недоступны.
        assertFalse(state.canRename(state.files[0]))

        viewModel.clearSelection()
        assertTrue(state.selecting)
        assertTrue(state.selection!!.isEmpty)
        viewModel.exitSelection()
        assertNull(state.selection)
        assertTrue(repository.calls.none { it.startsWith("move") || it.startsWith("copy") || it.startsWith("delete") })
    }

    @Test
    fun selectionSurvivesPagingAndSelectsOnlyLoaded() {
        repository.setChildren(root, alpha)
        repository.setFiles(root, 0, page(listOf(file(10), file(11)), hasNext = true))
        repository.setFiles(root, 1, page(listOf(file(11), file(12)), hasNext = true))
        opened()
        viewModel.toggleSelection(state.files[0])

        viewModel.loadNextPage()
        assertEquals(setOf(10L), state.selection!!.files.keys)

        repository.calls.clear()
        viewModel.selectLoaded()
        // Только загруженное, без догрузки; повтор ID на соседних страницах не удваивает счёт.
        assertTrue(repository.calls.isEmpty())
        assertEquals(setOf(10L, 11L, 12L), state.selection!!.files.keys)
        assertEquals(setOf(alpha.id), state.selection!!.folders.keys)
        assertEquals(4, state.selection!!.size)
    }

    @Test
    fun navigationAndSessionResetSelection() {
        openAlphaWithFiles()
        viewModel.toggleSelection(state.files[0])
        // Обновление той же папки выделение сохраняет.
        viewModel.refresh()
        assertEquals(setOf(10L), state.selection!!.files.keys)

        viewModel.navigateUp()
        assertNull(state.selection)

        viewModel.toggleSelection(alpha)
        tokens.saveTokens(tokensOf("bob"))
        assertNull(state.selection)

        viewModel.ensureLoaded()
        viewModel.toggleSelection(alpha)
        baseUrlProvider.baseUrl = SERVER_B
        assertNull(state.selection)
    }

    @Test
    fun systemFoldersAndActiveTreeAreNotSelected() {
        val (autoUploads, source, nestedSource) = openSourceRoot()
        repository.setFiles(source, 0, page(listOf(file(40))))
        viewModel.refresh()
        viewModel.toggleSelection(nestedSource)
        viewModel.toggleSelection(state.files.single())
        assertNull(state.selection)
        viewModel.navigateUp()
        viewModel.toggleSelection(source)
        viewModel.navigateUp()
        viewModel.toggleSelection(camera)
        viewModel.toggleSelection(autoUploads)
        assertNull(state.selection)
    }

    @Test
    fun groupMoveOfFilesRunsOneRequestPerObject() {
        openAlphaWithFiles()
        selectFiles(10, 11)
        viewModel.startGroupMove()
        assertTrue(state.picking)
        assertNull(state.selection)
        assertEquals("«file10.txt»: Файл уже находится здесь", state.pickProblem)

        moveTo(beta)
        viewModel.requestRelocationConfirm()
        repository.setFiles(alpha, 0, page(listOf(file(12))))
        repository.calls.clear()
        viewModel.confirmRelocation()

        assertEquals(
            listOf("moveFile:10:${beta.id}", "moveFile:11:${beta.id}", "children:${alpha.id}", "files:${alpha.id}:0"),
            repository.calls
        )
        val batch = state.batch!!
        assertFalse(batch.running)
        assertEquals(listOf(BatchOutcome.SUCCESS, BatchOutcome.SUCCESS), outcomes())
        assertEquals(BatchKind.MOVE, batch.kind)
        assertEquals(listOf(root, alpha), state.path)
        assertEquals(listOf(12L), fileIds())
        assertNull(state.relocation)
        assertNull(state.selection)

        viewModel.dismissBatch()
        assertNull(state.batch)
    }

    @Test
    fun groupMoveOfFilesAndFolderTogether() {
        openAlphaWithFiles()
        viewModel.toggleSelection(nested)
        selectFiles(12)
        viewModel.startGroupMove()
        moveTo(beta)
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()

        assertTrue(repository.calls.contains("moveFolder:${nested.id}:${beta.id}"))
        assertTrue(repository.calls.contains("moveFile:12:${beta.id}"))
        assertEquals(listOf(BatchOutcome.SUCCESS, BatchOutcome.SUCCESS), outcomes())
    }

    @Test
    fun invalidDestinationForOneObjectBlocksWholeGroup() {
        openAlphaWithFiles()
        viewModel.navigateUp()
        viewModel.toggleSelection(alpha)
        viewModel.toggleSelection(beta)
        viewModel.startGroupMove()

        // Внутрь alpha нельзя переместить саму alpha, хотя beta туда подходит.
        assertNotNull(blockedFolderProblem(state.relocation!!, state.path, alpha))
        viewModel.openFolder(gamma)
        assertNull(state.pickProblem)
        viewModel.navigateUp()
        viewModel.openFolder(alpha)
        assertEquals("«Alpha»: Нельзя переместить папку в саму себя или во вложенную папку", state.pickProblem)
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()

        assertNull(state.batch)
        assertTrue(repository.calls.none { it.startsWith("moveFolder") })
    }

    @Test
    fun groupCopyOfFilesKeepsSourceWithoutReload() {
        openAlphaWithFiles()
        selectFiles(10, 12)
        viewModel.startGroupCopy()
        viewModel.requestRelocationConfirm()
        assertFalse(state.relocation!!.confirming)
        moveTo(beta)
        viewModel.requestRelocationConfirm()
        repository.calls.clear()
        viewModel.confirmRelocation()

        // Копии с исходными именами; исходная папка не меняется и не перечитывается.
        assertEquals(listOf("copyFile:10:${beta.id}:file10.txt", "copyFile:12:${beta.id}:file12.txt"), repository.calls)
        assertEquals(listOf(10L, 11L, 12L), fileIds())
        assertEquals(listOf(root, alpha), state.path)
        assertEquals(BatchKind.COPY, state.batch!!.kind)
    }

    @Test
    fun groupWithFolderCannotBeCopied() {
        openAlphaWithFiles()
        viewModel.toggleSelection(nested)
        selectFiles(10)

        viewModel.startGroupCopy()

        assertNull(state.relocation)
        assertEquals("Копировать можно только файлы: копирования папок на Server нет", state.groupCopyProblem)
    }

    @Test
    fun groupDeleteNeedsConfirmationAndHandlesMixedSet() {
        openAlphaWithFiles()
        viewModel.toggleSelection(nested)
        selectFiles(10, 11)
        repository.calls.clear()

        viewModel.requestGroupDelete()
        assertTrue(state.selection!!.confirmingDelete)
        viewModel.dismissGroupDelete()
        assertTrue(repository.calls.isEmpty())
        assertEquals(3, state.selection!!.size)

        viewModel.requestGroupDelete()
        repository.setChildren(alpha)
        repository.setFiles(alpha, 0, page(listOf(file(12))))
        viewModel.confirmGroupDelete()

        assertEquals(
            listOf("deleteFolder:${nested.id}", "deleteFile:10", "deleteFile:11", "children:${alpha.id}", "files:${alpha.id}:0"),
            repository.calls
        )
        assertEquals(List(3) { BatchOutcome.SUCCESS }, outcomes())
        assertTrue(state.folders.isEmpty())
        assertEquals(listOf(12L), fileIds())
    }

    @Test
    fun definiteRefusalOfOneObjectDoesNotStopOthers() {
        openAlphaWithFiles()
        selectFiles(10, 11, 12)
        viewModel.startGroupMove()
        moveTo(beta)
        repository.fail("moveFile:11:${beta.id}", LibraryResult.NameTaken)
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()

        val batch = state.batch!!
        assertEquals(listOf(BatchOutcome.SUCCESS, BatchOutcome.FAILED, BatchOutcome.SUCCESS), outcomes())
        assertEquals("Имя уже занято файлом или папкой", batch.entries[1].reason)
        assertEquals(2, batch.count(BatchOutcome.SUCCESS))
        assertEquals(1, batch.count(BatchOutcome.FAILED))
        assertNull(batch.haltReason)
        assertEquals(1, repository.calls.count { it == "moveFile:11:${beta.id}" })
    }

    @Test
    fun unknownResultStopsBatchWithoutRepeat() {
        openAlphaWithFiles()
        selectFiles(10, 11, 12)
        viewModel.requestGroupDelete()
        // Server удалил 11, но ответ потерян.
        repository.fail("deleteFile:11", LibraryResult.NetworkError("timeout"))
        repository.setFiles(alpha, 0, page(listOf(file(12))))
        viewModel.confirmGroupDelete()

        val batch = state.batch!!
        assertEquals(listOf(BatchOutcome.SUCCESS, BatchOutcome.UNKNOWN, BatchOutcome.NOT_PROCESSED), outcomes())
        assertEquals("Результат последней операции неизвестен: пакет остановлен", batch.haltReason)
        assertFalse(batch.running)
        assertEquals(1, repository.calls.count { it == "deleteFile:11" })
        assertTrue(repository.calls.none { it == "deleteFile:12" })
        // Исходная папка перечитана: видно фактическое состояние.
        assertEquals(listOf(12L), fileIds())
    }

    @Test
    fun serverErrorStopsBatchAsUnknownWithoutRepeat() {
        openAlphaWithFiles()
        selectFiles(10, 11, 12)
        viewModel.requestGroupDelete()
        repository.fail("deleteFile:11", LibraryResult.ServerError("Physical storage operation failed"))
        repository.calls.clear()

        viewModel.confirmGroupDelete()

        val batch = state.batch!!
        assertEquals(listOf(BatchOutcome.SUCCESS, BatchOutcome.UNKNOWN, BatchOutcome.NOT_PROCESSED), outcomes())
        assertEquals("Ошибка сервера (Physical storage operation failed)", batch.entries[1].reason)
        assertEquals("Результат последней операции неизвестен: пакет остановлен", batch.haltReason)
        assertFalse(batch.running)
        assertEquals(2, batch.processed)
        assertEquals(1, batch.count(BatchOutcome.SUCCESS))
        assertEquals(0, batch.count(BatchOutcome.FAILED))
        assertEquals(1, batch.count(BatchOutcome.UNKNOWN))
        assertEquals(1, batch.count(BatchOutcome.NOT_PROCESSED))
        // Каждый объект — не больше одного запроса, третий не отправлялся.
        assertEquals(1, repository.calls.count { it == "deleteFile:10" })
        assertEquals(1, repository.calls.count { it == "deleteFile:11" })
        assertTrue(repository.calls.none { it == "deleteFile:12" })
    }

    @Test
    fun badRequestOfOneObjectStillContinues() {
        openAlphaWithFiles()
        selectFiles(10, 11, 12)
        viewModel.startGroupMove()
        moveTo(beta)
        repository.fail("moveFile:11:${beta.id}", LibraryResult.Rejected("Cannot move file between Camera and library folders"))
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()

        assertEquals(listOf(BatchOutcome.SUCCESS, BatchOutcome.FAILED, BatchOutcome.SUCCESS), outcomes())
        assertNull(state.batch!!.haltReason)
    }

    @Test
    fun lostMoveResponseLeadsToTargetCheck() {
        openAlphaWithFiles()
        selectFiles(10, 11)
        viewModel.startGroupMove()
        moveTo(beta)
        repository.fail("moveFile:10:${beta.id}", LibraryResult.UnknownResult("malformed"))
        repository.setFiles(beta, 0, page(listOf(file(10))))
        viewModel.requestRelocationConfirm()
        viewModel.confirmRelocation()

        assertEquals(listOf(BatchOutcome.UNKNOWN, BatchOutcome.NOT_PROCESSED), outcomes())
        repository.calls.clear()
        viewModel.openBatchTarget()

        assertNull(state.batch)
        assertEquals(listOf(root, beta), state.path)
        assertEquals(listOf(10L), fileIds())
        assertTrue(repository.calls.none { it.startsWith("moveFile") })
    }

    @Test
    fun stopKeepsResultOfRequestInFlight() {
        openAlphaWithFiles()
        selectFiles(10, 11, 12)
        viewModel.requestGroupDelete()
        val gate = repository.hold("deleteFile:11")
        viewModel.confirmGroupDelete()
        assertTrue(state.batch!!.running)
        assertEquals(1, state.batch!!.processed)

        viewModel.stopBatch()
        viewModel.dismissBatch()
        assertNotNull(state.batch)
        gate.complete(Unit)

        assertEquals(listOf(BatchOutcome.SUCCESS, BatchOutcome.SUCCESS, BatchOutcome.NOT_PROCESSED), outcomes())
        assertTrue(state.batch!!.stopRequested)
        assertFalse(state.batch!!.running)
        assertTrue(repository.calls.none { it == "deleteFile:12" })
    }

    @Test
    fun refusedAccessStopsBatchAndThenAsksToLogIn() {
        openAlphaWithFiles()
        selectFiles(10, 11)
        viewModel.requestGroupDelete()
        repository.fail("deleteFile:10", LibraryResult.Unauthorized)
        repository.calls.clear()
        viewModel.confirmGroupDelete()

        assertEquals(listOf(BatchOutcome.FAILED, BatchOutcome.NOT_PROCESSED), outcomes())
        assertTrue(state.batch!!.sessionLost)
        assertEquals(listOf("deleteFile:10"), repository.calls)

        viewModel.dismissBatch()
        assertEquals(LibraryStatus.NoSession, state.status)
    }

    @Test
    fun sessionChangeDuringBatchStopsIt() {
        openAlphaWithFiles()
        selectFiles(10, 11, 12)
        viewModel.requestGroupDelete()
        val gate = repository.hold("deleteFile:10")
        viewModel.confirmGroupDelete()

        tokens.saveTokens(tokensOf("bob"))
        repository.calls.clear()
        gate.complete(Unit)

        assertNull(state.batch)
        assertEquals(LibraryStatus.Idle, state.status)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun cancelledDestinationReturnsSelection() {
        openAlphaWithFiles()
        selectFiles(10, 11)
        viewModel.startGroupMove()
        moveTo(beta)
        repository.calls.clear()

        viewModel.cancelRelocation()

        assertTrue(repository.calls.isEmpty())
        assertEquals(listOf(root, alpha), state.path)
        assertEquals(setOf(10L, 11L), state.selection!!.files.keys)
        assertNull(state.batch)
    }

    // Room отвечает на своём потоке: итог отключения приходит асинхронно.
    private fun awaitState(condition: (LibraryUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition(state)) {
            check(System.currentTimeMillis() < deadline) { "Состояние не достигнуто: $state" }
            Thread.sleep(10)
        }
    }

    private fun openSourceRoot(): Triple<FolderDto, FolderDto, FolderDto> {
        val autoUploads = FolderDto(20, 1, "AutoUploads", "AUTO_UPLOADS")
        val source = FolderDto(21, 20, "Documents", "USER", backupSource = "550e8400-e29b-41d4-a716-446655440000")
        val nestedSource = FolderDto(22, 21, "Проекты", "USER")
        repository.setChildren(root, camera, autoUploads)
        repository.setChildren(autoUploads, source)
        repository.setChildren(source, nestedSource)
        opened()
        viewModel.openFolder(autoUploads)
        viewModel.openFolder(source)
        return Triple(autoUploads, source, nestedSource)
    }

    // --- Полноэкранный просмотр: состояние в ViewModel, Library под ним не меняется.

    private fun photo(id: Long, mime: String = "image/jpeg") =
        FileItemDto(id = id, originalFilename = "photo$id.jpg", mimeType = mime, checksum = "%064x".format(id))

    @Test
    fun viewerSequenceContainsOnlyViewableFilesInLibraryOrder() {
        repository.setFiles(root, 0, page(listOf(photo(10), file(11), photo(12, "image/heic"), photo(13, "image/png"),
            photo(14, "image/webp"), FileItemDto(id = 15, mimeType = "image/jpeg"))))
        opened()

        assertEquals(listOf(10L, 13L, 14L), state.viewerImages.map { it.id })
    }

    @Test
    fun openingAndClosingViewerKeepsLibraryState() {
        repository.setChildren(root, alpha)
        repository.setFiles(root, 0, page(listOf(photo(10), file(11), photo(12))))
        opened()
        viewModel.saveScroll(2, 15)
        val before = state
        val calls = repository.calls.toList()

        viewModel.openViewer(file(11))
        assertNull(viewModel.viewerFileId.value)
        viewModel.openViewer(photo(12))
        assertEquals(12L, viewModel.viewerFileId.value)
        viewModel.closeViewer()

        assertNull(viewModel.viewerFileId.value)
        assertEquals(before, state)
        assertEquals(calls, repository.calls)
        assertEquals(2, viewModel.scrollIndex)
    }

    @Test
    fun selectionModeTogglesInsteadOfOpeningViewer() {
        repository.setFiles(root, 0, page(listOf(photo(10), photo(12))))
        opened()
        viewModel.toggleSelection(photo(10))

        viewModel.openViewer(photo(12))

        assertNull(viewModel.viewerFileId.value)
        assertEquals(setOf(10L), state.selection!!.files.keys)
    }

    @Test
    fun viewerNearEndOfLoadedImagesLoadsNextPageOnce() {
        repository.setFiles(root, 0, page((1L..10L).map { photo(it) }, hasNext = true))
        repository.setFiles(root, 1, page((11L..12L).map { photo(it) }))
        opened()
        viewModel.openViewer(photo(1))

        viewModel.onViewerPage(5)
        assertFalse(repository.calls.contains("files:1:1"))
        viewModel.onViewerPage(8)

        assertEquals(1, repository.calls.count { it == "files:1:1" })
        assertEquals((1L..12L).toList(), state.viewerImages.map { it.id })
        assertEquals(8L, viewModel.viewerFileId.value)
        viewModel.onViewerPage(11)
        assertEquals(1, repository.calls.count { it == "files:1:1" })
    }

    @Test
    fun accountOrServerChangeClosesViewer() {
        repository.setFiles(root, 0, page(listOf(photo(10))))
        opened()
        viewModel.openViewer(photo(10))

        tokens.saveTokens(tokensOf("bob"))
        assertNull(viewModel.viewerFileId.value)

        viewModel.ensureLoaded()
        viewModel.openViewer(photo(10))
        baseUrlProvider.baseUrl = SERVER_B
        assertNull(viewModel.viewerFileId.value)
        // Поздний переход страницы прежнего просмотра его не открывает.
        viewModel.onViewerPage(10)
        assertNull(viewModel.viewerFileId.value)
    }

    /** DELETE без HTTP: исход задаёт тест. */
    private class FakeDisableRemote : BackupSourceRemote(mock(ApiServiceFactory::class.java)) {
        var outcome: DisableOutcome = DisableOutcome.Disabled
        val calls = mutableListOf<Long>()

        override suspend fun disable(folderId: Long): DisableOutcome {
            calls += folderId
            return outcome
        }
    }

    private fun file(id: Long) = FileItemDto(id = id, originalFilename = "file$id.txt")

    private fun page(items: List<FileItemDto>, hasNext: Boolean = false) = FilePageDto(items = items, hasNext = hasNext)

    private fun tokensOf(subject: String, version: Int = 1): Tokens {
        val payload = """{"sub":"$subject","v":$version}""".toByteArray()
        return Tokens("header.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload)}.signature", "refresh$version")
    }

    /** Ответы задаёт тест; удержанный запрос не реагирует на отмену, как уже выполняющийся HTTP-вызов. */
    private class FakeLibraryRepository(
        private val root: FolderDto
    ) : LibraryRepository(mock(ApiServiceFactory::class.java)) {

        val calls = mutableListOf<String>()
        private val children = HashMap<Long, List<FolderDto>>()
        private val files = HashMap<String, FilePageDto>()
        private val failures = HashMap<String, LibraryResult.Failure>()
        private val gates = HashMap<String, CompletableDeferred<Unit>>()

        fun setChildren(folder: FolderDto, vararg items: FolderDto) {
            children[folder.id] = items.toList()
        }

        fun setFiles(folder: FolderDto, page: Int, value: FilePageDto) {
            files["files:${folder.id}:$page"] = value
        }

        fun fail(key: String, failure: LibraryResult.Failure) {
            failures[key] = failure
        }

        fun succeed(key: String) {
            failures.remove(key)
        }

        fun hold(key: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[key] = it }

        override suspend fun getRoot(): LibraryResult<FolderDto> = answer("root", root)

        override suspend fun getChildren(folderId: Long): LibraryResult<List<FolderDto>> =
            answer("children:$folderId", children[folderId].orEmpty())

        override suspend fun getFiles(folder: FolderDto, page: Int): LibraryResult<FilePageDto> {
            val key = "files:${folder.id}:$page"
            return answer(key, files[key] ?: FilePageDto(items = emptyList()))
        }

        // Изменения отвечают описанием объекта; новое содержимое папки задаёт тест через setChildren/setFiles.
        override suspend fun createFolder(parentId: Long, name: String): LibraryResult<FolderDto> =
            answer("create:$parentId:$name", FolderDto(1000, parentId, name, "USER"))

        override suspend fun renameFolder(folderId: Long, name: String): LibraryResult<FolderDto> =
            answer("renameFolder:$folderId:$name", FolderDto(folderId, null, name, "USER"))

        override suspend fun renameFile(fileId: Long, name: String): LibraryResult<FileItemDto> =
            answer("renameFile:$fileId:$name", FileItemDto(id = fileId, originalFilename = name))

        override suspend fun moveFile(fileId: Long, targetFolderId: Long): LibraryResult<FileItemDto> =
            answer("moveFile:$fileId:$targetFolderId", FileItemDto(id = fileId, folderId = targetFolderId))

        override suspend fun copyFile(fileId: Long, targetFolderId: Long, name: String): LibraryResult<FileItemDto> =
            answer("copyFile:$fileId:$targetFolderId:$name", FileItemDto(id = 2000, folderId = targetFolderId, originalFilename = name))

        override suspend fun moveFolder(folderId: Long, targetParentId: Long): LibraryResult<FolderDto> =
            answer("moveFolder:$folderId:$targetParentId", FolderDto(folderId, targetParentId, "moved", "USER"))

        override suspend fun deleteFile(fileId: Long): LibraryResult<Unit> = answer("deleteFile:$fileId", Unit)

        override suspend fun deleteFolder(folderId: Long): LibraryResult<Unit> = answer("deleteFolder:$folderId", Unit)

        private suspend fun <T> answer(key: String, value: T): LibraryResult<T> {
            calls += key
            gates.remove(key)?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return failures[key] ?: LibraryResult.Success(value)
        }
    }
}

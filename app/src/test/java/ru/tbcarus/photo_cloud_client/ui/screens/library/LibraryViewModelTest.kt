package ru.tbcarus.photo_cloud_client.ui.screens.library

import android.app.Application
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
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.library.LibraryRepository
import ru.tbcarus.photo_cloud_client.library.LibraryResult
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
        Dispatchers.resetMain()
    }

    private fun createViewModel() {
        viewModel = LibraryViewModel(repository, baseUrlProvider, tokens)
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

        private suspend fun <T> answer(key: String, value: T): LibraryResult<T> {
            calls += key
            gates.remove(key)?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return failures[key] ?: LibraryResult.Success(value)
        }
    }
}

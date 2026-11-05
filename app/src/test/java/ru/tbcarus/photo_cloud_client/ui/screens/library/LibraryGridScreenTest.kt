package ru.tbcarus.photo_cloud_client.ui.screens.library

import android.app.Application
import org.junit.rules.RuleChain
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FilePageDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.backup.BackupAccount
import ru.tbcarus.photo_cloud_client.backup.BackupSourceRemote
import ru.tbcarus.photo_cloud_client.backup.BackupSourceRepository
import ru.tbcarus.photo_cloud_client.backup.DisableOutcome
import ru.tbcarus.photo_cloud_client.backup.FakeAccess
import ru.tbcarus.photo_cloud_client.backup.FakeAccounts
import ru.tbcarus.photo_cloud_client.backup.FakeScheduler
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.library.LibraryRepository
import ru.tbcarus.photo_cloud_client.library.LibraryResult
import ru.tbcarus.photo_cloud_client.library.librarySessionFlow
import ru.tbcarus.photo_cloud_client.library.thumbnail.LibraryThumbnails
import ru.tbcarus.photo_cloud_client.transfer.FakeDocuments
import ru.tbcarus.photo_cloud_client.transfer.FakeTransferRemote
import ru.tbcarus.photo_cloud_client.transfer.TransferManager
import java.nio.file.Files
import java.util.Base64

/**
 * Плитка Library: тот же ViewModel и состояние, что у списка. Логика операций проверена в LibraryViewModelTest,
 * здесь — представление, его переключение и связь с общим состоянием.
 * Ресурсы библиотек в unit-тесты не подключены (includeAndroidResources меняет поведение других тестов), поэтому
 * выпадающее меню и диалоги Material3 здесь не открываются: их содержимое общее со списком и проверяется на устройстве.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class LibraryGridScreenTest {

    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeTestSetup()).around(compose)

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val root = FolderDto(1, null, "root", "ROOT")
    private val camera = FolderDto(2, 1, "Camera", "CAMERA")
    private val autoUploads = FolderDto(3, 1, "AutoUploads", "AUTO_UPLOADS")
    private val source = FolderDto(4, 3, "Phone", "USER", backupSource = "uuid-1")
    private val alpha = FolderDto(5, 1, "Alpha", "USER")
    private val many = FolderDto(6, 1, "Many", "USER")
    private val empty = FolderDto(7, 1, "Empty", "USER")

    private val jpeg = image(10, "photo.jpg", "image/jpeg")
    private val png = image(11, "picture.png", "image/png")
    private val webp = image(12, "image.webp", "image/webp")
    private val pdf = FileItemDto(id = 13, originalFilename = "doc.pdf", mimeType = "application/pdf", checksum = "c13", fileType = "DOCUMENT")
    private val heic = image(14, "fake.jpg", "image/heic")
    private val clip = FileItemDto(id = 15, originalFilename = "clip.mp4", mimeType = "video/mp4", checksum = "c15", fileType = "VIDEO")

    private val server = MockWebServer()
    private val repository = FakeRepository(root)
    private val tokens = InMemoryTokenStorage(tokensOf("alice"))
    private lateinit var baseUrlProvider: BaseUrlProvider
    private lateinit var viewModel: LibraryViewModel
    private lateinit var db: AppDatabase
    private lateinit var thumbnails: LibraryThumbnails

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val jpegBytes = javaClass.getResourceAsStream("/thumbnail/sample.jpg")!!.use { it.readBytes() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse()
                .setHeader("Content-Type", "image/jpeg")
                .setHeader("Cache-Control", "max-age=86400, private")
                .setBody(Buffer().write(jpegBytes))
        }
        server.start()
        baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply { baseUrl = server.url("/").toString() }

        repository.children[root.id] = listOf(camera, autoUploads, alpha, many, empty)
        repository.children[autoUploads.id] = listOf(source)
        repository.files["${root.id}:0"] = FilePageDto(items = listOf(jpeg, png, webp, pdf, heic, clip))
        repository.files["${source.id}:0"] = FilePageDto(items = listOf(image(20, "synced.jpg", "image/jpeg")))
        repository.files["${many.id}:0"] = FilePageDto(items = (100L until 150L).map { image(it, "n$it.jpg", "image/jpeg") }, hasNext = true)
        repository.files["${many.id}:1"] = FilePageDto(items = (150L until 160L).map { image(it, "n$it.jpg", "image/jpeg") })

        val transfers = TransferManager(
            FakeTransferRemote(), FakeDocuments(context), Files.createTempDirectory("transfer").toFile(),
            librarySessionFlow(baseUrlProvider, tokens), UnconfinedTestDispatcher()
        )
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val sources = BackupSourceRepository(
            db.backupSourceDao(), FakeDisableRemote(), FakeAccess(context),
            FakeAccounts(BackupAccount(baseUrlProvider.baseUrl, "alice")), FakeScheduler(context)
        )
        viewModel = LibraryViewModel(repository, baseUrlProvider, tokens, transfers, sources)
        thumbnails = LibraryThumbnails(
            context, OkHttpClient(), librarySessionFlow(baseUrlProvider, tokens), UnconfinedTestDispatcher(),
            Files.createTempDirectory("thumbnails").toFile()
        )
        compose.setContent { LibraryScreen(viewModel, onOpenNetwork = {}, onOpenLogin = {}, thumbnails = thumbnails) }
        compose.waitForIdle()
    }

    // Незакрытая база оставляет фоновые задачи Room, которые падают уже в следующих тестах.
    @After
    fun tearDown() {
        db.close()
        thumbnails.imageLoader.shutdown()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun toGrid() {
        compose.onNodeWithContentDescription("Показать плиткой").performClick()
        compose.waitForIdle()
    }

    private fun tile(name: String) = compose.onNodeWithTag(TILE_TAG_PREFIX + name)

    private fun open(name: String) {
        tile(name).performClick()
        compose.waitForIdle()
    }

    private fun gridTags(): List<String> =
        compose.onNodeWithTag(GRID_TAG).onChildren().fetchSemanticsNodes()
            .mapNotNull { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }

    private fun menuOf(name: String) =
        compose.onNode(hasContentDescription("Действия") and hasAnyAncestor(hasTestTag(TILE_TAG_PREFIX + name)))

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun checkedBoxes() = compose.onAllNodes(isToggleable() and isOn())

    @Test
    fun startsAsListAndSwitchesBothWays() {
        compose.onNodeWithTag(LIST_TAG).assertExists()
        compose.onNodeWithTag(GRID_TAG).assertDoesNotExist()

        toGrid()
        compose.onNodeWithTag(GRID_TAG).assertExists()
        compose.onNodeWithTag(LIST_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription("Показать списком").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(LIST_TAG).assertExists()
        compose.onNodeWithContentDescription("Показать плиткой").assertExists()
    }

    @Test
    fun switchingViewDoesNotReloadCatalog() {
        val before = repository.calls.toList()

        toGrid()
        compose.onNodeWithContentDescription("Показать списком").performClick()
        toGrid()

        assertEquals(before, repository.calls)
    }

    @Test
    fun gridKeepsListOrderFoldersThenFiles() {
        toGrid()

        assertEquals(
            listOf("Camera", "AutoUploads", "Alpha", "Many", "Empty", "photo.jpg", "picture.png", "image.webp", "doc.pdf", "fake.jpg", "clip.mp4")
                .map { TILE_TAG_PREFIX + it },
            gridTags()
        )
    }

    @Test
    fun foldersKeepTheirIconsAndImagesGetThumbnails() {
        toGrid()

        compose.onNodeWithTag(FOLDER_ICON_TAG_PREFIX + "CAMERA", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FOLDER_ICON_TAG_PREFIX + "AUTO_UPLOADS", useUnmergedTree = true).assertExists()
        // Robolectric без native graphics не декодирует картинку надёжно: проверяются запросы, а не пиксели.
        val requested = (1..server.requestCount).map { server.takeRequest().path }.toSet()
        assertEquals(listOf(jpeg, png, webp).map { "/api/v1/files/${it.id}/thumbnail" }.toSet(), requested)
        // PDF и HEIC под именем .jpg — значок типа, миниатюра не запрашивается.
        listOf(pdf, heic, clip).forEach {
            compose.onNodeWithTag(FILE_ICON_TAG_PREFIX + it.id, useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(THUMBNAIL_TAG_PREFIX + it.id, useUnmergedTree = true).assertDoesNotExist()
        }
        open("AutoUploads")
        compose.onNodeWithTag(FOLDER_ICON_TAG_PREFIX + "ACTIVE_SOURCE", useUnmergedTree = true).assertExists()
    }

    @Test
    fun folderOpensFromTileAndViewSurvivesNavigation() {
        toGrid()
        open("Alpha")

        assertTrue(repository.calls.contains("children:${alpha.id}"))
        compose.onNodeWithText("Корень / Alpha", substring = true).assertExists()
        compose.onNodeWithText("Папка пуста").assertExists()
        assertTrue(viewModel.gridView.value)

        compose.onNodeWithContentDescription("К родительской папке").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(GRID_TAG).assertExists()
        tile("photo.jpg").assertExists()
    }

    @Test
    fun tappingImageOpensViewerFromGridAndList() {
        toGrid()
        val before = repository.calls.toList()

        tile("photo.jpg").performClick()
        compose.waitForIdle()
        assertEquals(jpeg.id, viewModel.viewerFileId.value)
        viewModel.closeViewer()
        // Документ и HEIC не открываются; запросов каталога нет.
        tile("doc.pdf").performClick()
        tile("fake.jpg").performClick()
        compose.waitForIdle()
        assertEquals(null, viewModel.viewerFileId.value)

        compose.onNodeWithContentDescription("Показать списком").performClick()
        compose.onNodeWithText("picture.png").performClick()
        compose.waitForIdle()
        assertEquals(png.id, viewModel.viewerFileId.value)
        assertEquals(before, repository.calls)
        compose.onNodeWithText("Выбрано", substring = true).assertDoesNotExist()
    }

    @Test
    fun tappingVideoOpensPlayerFromGridAndList() {
        toGrid()
        compose.onNodeWithTag(GRID_TAG).performScrollToIndex(10)

        tile("clip.mp4").performClick()
        compose.waitForIdle()
        assertEquals(clip.id, viewModel.videoFileId.value)
        assertEquals(null, viewModel.viewerFileId.value)
        viewModel.closeVideo()

        compose.onNodeWithContentDescription("Показать списком").performClick()
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(10)
        compose.onNodeWithText("clip.mp4").performClick()
        compose.waitForIdle()
        assertEquals(clip.id, viewModel.videoFileId.value)
        assertEquals(null, viewModel.viewerFileId.value)
    }

    @Test
    fun selectionModeTapTogglesInsteadOfOpeningViewer() {
        toGrid()
        tile("photo.jpg").performTouchInput { longClick() }
        tile("picture.png").performClick()
        compose.waitForIdle()

        assertEquals(null, viewModel.viewerFileId.value)
        compose.onNodeWithText("Выбрано: 2").assertExists()
    }

    @Test
    fun longPressSelectsAndTapsToggleSelectionThatSurvivesViewSwitch() {
        toGrid()

        tile("photo.jpg").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Выбрано: 1").assertExists()
        tile("picture.png").performClick()
        tile("image.webp").performClick()
        tile("image.webp").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Выбрано: 2").assertExists()
        checkedBoxes().assertCountEquals(2)

        compose.onNodeWithContentDescription("Показать списком").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LIST_TAG).assertExists()
        compose.onNodeWithText("Выбрано: 2").assertExists()
        checkedBoxes().assertCountEquals(2)
        assertEquals(setOf(jpeg.id, png.id), viewModel.uiState.value.selection!!.files.keys)
    }

    @Test
    fun menuButtonOnlyOnObjectsWithActions() {
        toGrid()

        // Действия плитки строятся теми же функциями, что у строки списка; кнопки нет у объектов без действий.
        listOf("photo.jpg", "doc.pdf", "Alpha").forEach { menuOf(it).assertExists() }
        listOf("Camera", "AutoUploads").forEach { menuOf(it).assertDoesNotExist() }
    }

    @Test
    fun activeSourceTreeKeepsRestrictions() {
        toGrid()
        open("AutoUploads")
        // Корень активного источника не меняется пользователем: меню нет.
        menuOf("Phone").assertDoesNotExist()
        open("Phone")

        // В активном дереве объекты не выделяются; у файла остаётся только «Скачать», поэтому меню есть.
        tile("synced.jpg").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Выбрано", substring = true).assertDoesNotExist()
        menuOf("synced.jpg").assertExists()
        assertTrue(viewModel.uiState.value.inActiveSource)
        compose.onNodeWithContentDescription("Добавить").assertDoesNotExist()
    }

    @Test
    fun selectionFromGridFeedsGroupCommands() {
        toGrid()
        tile("photo.jpg").performTouchInput { longClick() }
        tile("doc.pdf").performClick()
        compose.waitForIdle()

        // Панель групповых команд та же, что в списке, и работает с выделением, сделанным в плитке.
        listOf("Переместить", "Копировать", "Удалить").forEach { compose.onNodeWithText(it).assertExists() }
        assertEquals(setOf(jpeg.id, pdf.id), viewModel.uiState.value.selection!!.files.keys)
        compose.onNodeWithText("Переместить").performClick()
        compose.waitForIdle()
        val group = viewModel.uiState.value.relocation!!.item as Relocated.Group
        assertEquals(setOf(jpeg.id, pdf.id), group.items.map { (it as LibraryItem.File).file.id }.toSet())
    }

    @Test
    fun gridLoadsNextPageNearEndOnce() {
        toGrid()
        open("Many")
        assertEquals(listOf("files:${many.id}:0"), repository.calls.filter { it.startsWith("files:${many.id}") })

        compose.onNodeWithTag(GRID_TAG).performScrollToIndex(49)
        compose.waitForIdle()
        compose.onNodeWithTag(GRID_TAG).performScrollToIndex(59)
        compose.waitForIdle()

        tile("n159.jpg").assertExists()
        assertEquals(listOf("files:${many.id}:0", "files:${many.id}:1"), repository.calls.filter { it.startsWith("files:${many.id}") })
    }

    @Test
    fun nextPageErrorIsShownInGridWithoutLosingTiles() {
        repository.failures["files:${many.id}:1"] = LibraryResult.NetworkError("Нет связи с сервером")
        toGrid()
        open("Many")

        compose.onNodeWithTag(GRID_TAG).performScrollToIndex(49)
        compose.waitForIdle()
        compose.onNodeWithTag(GRID_TAG).performScrollToIndex(50)
        compose.waitForIdle()

        compose.onNodeWithText("Повторить").assertExists()
        tile("n149.jpg").assertExists()
    }

    @Test
    fun emptyFolderAndCatalogErrorLookTheSameInGrid() {
        toGrid()
        open("Empty")
        compose.onNodeWithText("Папка пуста").assertExists()

        repository.failures["children:${root.id}"] = LibraryResult.NetworkError("Нет связи с сервером")
        compose.onNodeWithContentDescription("К родительской папке").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Повторить").assertExists()
        compose.onNodeWithTag(GRID_TAG).assertDoesNotExist()
    }

    @Test
    fun destinationPickerUsesListAndCancelReturnsGrid() {
        toGrid()
        tile("photo.jpg").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Переместить").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(LIST_TAG).assertExists()
        compose.onNodeWithTag(GRID_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription("Показать списком").assertIsNotEnabled()
        compose.onNodeWithText("Отмена").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(GRID_TAG).assertExists()
        // Отмена возвращает и выделение.
        compose.onNodeWithText("Выбрано: 1").assertExists()
    }

    @Test
    fun backLeavesSelectionThenFolderInGrid() {
        toGrid()
        open("Alpha")
        compose.onNodeWithContentDescription("К родительской папке").assertExists()

        pressBack()

        compose.onNodeWithTag(GRID_TAG).assertExists()
        tile("Alpha").assertExists()
    }

    @Test
    fun accountAndServerChangeKeepGridAndReloadNewContext() {
        toGrid()
        tile("photo.jpg").performTouchInput { longClick() }
        compose.waitForIdle()

        tokens.saveTokens(tokensOf("bob"))
        compose.waitForIdle()
        compose.onNodeWithTag(GRID_TAG).assertExists()
        compose.onNodeWithText("Выбрано", substring = true).assertDoesNotExist()

        baseUrlProvider.baseUrl = "http://10.0.0.9:8080/"
        compose.waitForIdle()
        compose.onNodeWithTag(GRID_TAG).assertExists()
        assertEquals(3, repository.calls.count { it == "root" })
    }

    private fun image(id: Long, name: String, mime: String) =
        FileItemDto(id = id, originalFilename = name, mimeType = mime, checksum = "c$id", fileType = "IMAGE")

    private fun tokensOf(subject: String): Tokens {
        val payload = """{"sub":"$subject"}""".toByteArray()
        return Tokens("header.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload)}.signature", "refresh-$subject")
    }

    private class FakeDisableRemote : BackupSourceRemote(mock(ApiServiceFactory::class.java)) {
        override suspend fun disable(folderId: Long): DisableOutcome = DisableOutcome.Disabled
    }

    /** Каталог в памяти: ответы чтения и удаления, журнал вызовов. */
    private class FakeRepository(private val root: FolderDto) : LibraryRepository(mock(ApiServiceFactory::class.java)) {
        val calls = mutableListOf<String>()
        val children = HashMap<Long, List<FolderDto>>()
        val files = HashMap<String, FilePageDto>()
        val failures = HashMap<String, LibraryResult.Failure>()

        override suspend fun getRoot(): LibraryResult<FolderDto> = answer("root", root)

        override suspend fun getChildren(folderId: Long): LibraryResult<List<FolderDto>> =
            answer("children:$folderId", children[folderId].orEmpty())

        override suspend fun getFiles(folder: FolderDto, page: Int): LibraryResult<FilePageDto> =
            answer("files:${folder.id}:$page", files["${folder.id}:$page"] ?: FilePageDto(items = emptyList()))

        override suspend fun deleteFile(fileId: Long): LibraryResult<Unit> = answer("deleteFile:$fileId", Unit)

        override suspend fun deleteFolder(folderId: Long): LibraryResult<Unit> = answer("deleteFolder:$folderId", Unit)

        private fun <T> answer(key: String, value: T): LibraryResult<T> {
            calls += key
            return failures[key] ?: LibraryResult.Success(value)
        }
    }
}

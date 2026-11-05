package ru.tbcarus.photo_cloud_client.ui.screens.library

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.library.LibrarySession
import ru.tbcarus.photo_cloud_client.library.original.ViewerError
import ru.tbcarus.photo_cloud_client.library.original.ViewerImage
import ru.tbcarus.photo_cloud_client.library.original.ViewerImageSource
import ru.tbcarus.photo_cloud_client.library.thumbnail.LibraryThumbnails
import java.nio.file.Files

/** Экран просмотра с подменённым источником изображений: загрузка, ошибки, листание, жесты и закрытие. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class ImageViewerTest {

    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeTestSetup()).around(compose)

    private val images = (1L..3L).map {
        FileItemDto(id = it, originalFilename = "photo$it.jpg", mimeType = "image/jpeg", checksum = "%064x".format(it), size = 10)
    }
    private val loads = mutableListOf<Long>()
    private val released = mutableListOf<Long>()
    private val pages = mutableListOf<Long>()
    private var closed = 0
    private val results = HashMap<Long, ArrayDeque<ViewerImage>>()
    private var gate: CompletableDeferred<Unit>? = null
    private var open by mutableStateOf(true)
    private var currentId by mutableStateOf(1L)
    private lateinit var thumbnails: LibraryThumbnails

    private val source = ViewerImageSource { file ->
        val id = file.id!!
        loads += id
        gate?.await()
        results[id]?.removeFirstOrNull() ?: shown(id)
    }

    private fun shown(id: Long) =
        ViewerImage.Shown(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)) { released += id }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        thumbnails = LibraryThumbnails(
            // Без сессии миниатюры не запрашиваются: сетевые вызовы Coil пережили бы тест и мешали следующим.
            context, OkHttpClient(), MutableStateFlow(LibrarySession("", null)),
            UnconfinedTestDispatcher(), Files.createTempDirectory("thumbnails").toFile()
        )
    }

    @After
    fun tearDown() {
        thumbnails.imageLoader.shutdown()
    }

    // Содержимое задаётся в тесте после настройки источника: первая загрузка стартует уже при показе.
    private fun show() {
        compose.setContent {
            if (open) {
                ImageViewer(images, currentId, thumbnails, source, onPage = { pages += it }, onClose = { closed++ })
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun loadingShowsPlaceholderThenOriginal() {
        gate = CompletableDeferred()
        show()
        compose.onNodeWithTag(VIEWER_LOADING_TAG).assertExists()
        compose.onNodeWithText("1 / 3").assertExists()

        gate!!.complete(Unit)
        compose.waitForIdle()

        compose.onNodeWithTag(VIEWER_IMAGE_TAG).assertExists()
        compose.onNodeWithTag(VIEWER_LOADING_TAG).assertDoesNotExist()
        // Оригинал грузится только для открытой страницы, соседние не скачиваются заранее.
        assertEquals(listOf(1L), loads)
    }

    @Test
    fun errorOffersExplicitRetryWithoutAutomaticRepeats() {
        results[1] = ArrayDeque(listOf(ViewerImage.Error(ViewerError.NO_CONNECTION)))
        show()

        compose.onNodeWithText("Нет связи с сервером").assertExists()
        compose.waitForIdle()
        assertEquals(listOf(1L), loads)
        compose.onNodeWithText("Повторить").performClick()
        compose.waitForIdle()

        assertEquals(listOf(1L, 1L), loads)
        compose.onNodeWithTag(VIEWER_IMAGE_TAG).assertExists()
    }

    @Test
    fun swipeMovesToNextImageAndReportsPage() {
        show()

        compose.onNodeWithTag(VIEWER_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText("2 / 3").assertExists()
        compose.onNodeWithText("photo2.jpg").assertExists()
        assertEquals(2L, pages.last())
        assertTrue(loads.contains(2L))
    }

    @Test
    fun zoomedImagePansInsteadOfSwiping() {
        show()

        compose.onNodeWithTag(VIEWER_IMAGE_TAG).performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.onNodeWithTag(VIEWER_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText("1 / 3").assertExists()
        assertTrue(pages.none { it != 1L })
    }

    @Test
    fun closeButtonAndBackClose() {
        show()

        compose.onNodeWithContentDescription("Закрыть просмотр").performClick()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(2, closed)
    }

    @Test
    fun closingViewerReleasesCachedFile() {
        show()
        compose.onNodeWithTag(VIEWER_IMAGE_TAG).assertExists()

        open = false
        compose.waitForIdle()

        assertEquals(listOf(1L), released)
    }

    @Test
    fun missingCurrentFileClosesViewer() {
        currentId = 99
        show()

        assertEquals(1, closed)
    }
}

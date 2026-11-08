package ru.tbcarus.photo_cloud_client.ui.screens.notifications

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.api.models.NotificationDto
import ru.tbcarus.photo_cloud_client.api.models.NotificationPageDto
import ru.tbcarus.photo_cloud_client.api.models.NotificationTargetType
import ru.tbcarus.photo_cloud_client.api.models.NotificationType
import ru.tbcarus.photo_cloud_client.api.models.UnreadNotificationCountDto
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.library.LibraryRepository
import ru.tbcarus.photo_cloud_client.library.LibraryResult
import ru.tbcarus.photo_cloud_client.library.LibrarySession
import ru.tbcarus.photo_cloud_client.ui.screens.library.SharedTarget
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationsViewModelTest {

    private companion object {
        const val SERVER_A = "http://10.0.0.1:8080/"
        const val SERVER_B = "http://10.0.0.2:8080/"
        const val READ_AT = "2026-11-08T10:00:00Z"
    }

    private val scheduler = TestCoroutineScheduler()
    private val repository = FakeNotificationRepository()
    private val tokens = InMemoryTokenStorage(tokensOf("alice"))
    private val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply { baseUrl = SERVER_A }
    private lateinit var viewModel: NotificationsViewModel
    private val state get() = viewModel.uiState.value

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        viewModel = NotificationsViewModel(repository, baseUrlProvider, tokens)
    }

    @After
    fun tearDown() {
        viewModel.onBackground()
        Dispatchers.resetMain()
    }

    private fun shareGranted(id: Long, targetType: NotificationTargetType, targetId: Long, readAt: String? = null) =
        NotificationDto(id, NotificationType.SHARE_GRANTED, "Предоставлен доступ", "Вам предоставлен доступ к «$id»",
            targetType, targetId, "2026-11-08T09:00:00Z", readAt)

    @Test
    fun screenLoadsFirstPageAndPagesFurther() {
        repository.pages[0] = NotificationPageDto(items = listOf(shareGranted(3, NotificationTargetType.FILE, 30)), hasNext = true)
        repository.pages[1] = NotificationPageDto(items = listOf(shareGranted(3, NotificationTargetType.FILE, 30),
            shareGranted(2, NotificationTargetType.FOLDER, 20)))
        repository.count = 2

        viewModel.onScreenOpened()
        assertEquals(NotificationsStatus.Content, state.status)
        assertEquals(listOf(3L), state.items.map { it.id })
        assertTrue(state.hasNext)
        assertEquals(2L, state.unreadCount)

        viewModel.onListEndReached()
        // Повтор записи на стыке страниц не дублирует строку; дальше страниц нет.
        assertEquals(listOf(3L, 2L), state.items.map { it.id })
        assertFalse(state.hasNext)
        viewModel.onListEndReached()
        assertEquals(listOf("page:0", "page:1"), repository.calls.filter { it.startsWith("page:") })
    }

    @Test
    fun screenEntryLoadsOnceWhenResumeAndSessionCheckCoincide() {
        val gate = repository.hold("page:0")
        viewModel.ensureLoaded()
        // Возврат на экран и проверка сессии пришли одновременно: второй загрузки нет ни во время первой, ни после.
        viewModel.onScreenOpened()
        gate.complete(Unit)
        viewModel.ensureLoaded()
        assertEquals(1, repository.calls.count { it == "page:0" })
        assertEquals(NotificationsStatus.Content, state.status)
    }

    @Test
    fun emptyListAndNetworkErrorWithRetry() {
        repository.fail("page:0", LibraryResult.NetworkError("timeout"))
        viewModel.onScreenOpened()
        assertEquals(NotificationsStatus.Error("Нет связи с сервером: timeout"), state.status)

        repository.failures.clear()
        viewModel.onScreenOpened()
        assertEquals(NotificationsStatus.Content, state.status)
        assertTrue(state.items.isEmpty())
        assertEquals(0L, state.unreadCount)
    }

    @Test
    fun pollingRefreshesCountWithoutParallelRequestsAndStopsInBackground() {
        repository.count = 5
        val gate = repository.hold("count")

        viewModel.onForeground()
        viewModel.onForeground()
        viewModel.refreshCount()
        // Один цикл и один запрос, пока первый не завершён.
        assertEquals(1, repository.calls.count { it == "count" })
        gate.complete(Unit)
        assertEquals(5L, state.unreadCount)

        repository.count = 7
        scheduler.advanceTimeBy(NotificationsViewModel.POLL_INTERVAL_MS + 1)
        assertEquals(2, repository.calls.count { it == "count" })
        assertEquals(7L, state.unreadCount)

        viewModel.onBackground()
        scheduler.advanceTimeBy(NotificationsViewModel.POLL_INTERVAL_MS * 3)
        assertEquals(2, repository.calls.count { it == "count" })
        assertEquals("99+", unreadBadgeText(100))
        assertEquals("99", unreadBadgeText(99))
    }

    @Test
    fun tappingUnreadMarksItReadUpdatesCountAndOpensTarget() {
        val unread = shareGranted(5, NotificationTargetType.FOLDER, 50)
        repository.pages[0] = NotificationPageDto(items = listOf(unread))
        repository.count = 1
        viewModel.onScreenOpened()

        repository.count = 0
        viewModel.open(unread)

        assertEquals(READ_AT, state.items.single().readAt)
        assertEquals(0L, state.unreadCount)
        val target = SharedTarget(LibrarySession(SERVER_A, "alice"), isFolder = true, resourceId = 50)
        assertEquals(target, viewModel.pendingOpen.value)
        viewModel.consumeOpen(target)
        assertNull(viewModel.pendingOpen.value)

        // Уже прочитанное повторно на Server не отправляется, переход выполняется.
        viewModel.open(state.items.single())
        assertEquals(1, repository.calls.count { it == "read:5" })
        assertEquals(target, viewModel.pendingOpen.value)
    }

    @Test
    fun failedReadIsNotShownAsSuccessAndDoesNotNavigate() {
        val unread = shareGranted(5, NotificationTargetType.FILE, 60)
        repository.pages[0] = NotificationPageDto(items = listOf(unread))
        repository.count = 1
        viewModel.onScreenOpened()
        repository.fail("read:5", LibraryResult.NetworkError("timeout"))

        viewModel.open(unread)

        assertTrue(state.items.single().isUnread)
        assertEquals(1L, state.unreadCount)
        assertNull(viewModel.pendingOpen.value)
        assertNull(state.openingId)
        assertTrue(state.notice!!.startsWith("Не удалось отметить уведомление прочитанным"))
    }

    @Test
    fun markAllReadReloadsFromServerAndKeepsStateOnFailure() {
        repository.pages[0] = NotificationPageDto(items = listOf(shareGranted(2, NotificationTargetType.FILE, 20),
            shareGranted(1, NotificationTargetType.FOLDER, 10, readAt = "2026-01-01T00:00:00Z")))
        repository.count = 1
        viewModel.onScreenOpened()

        repository.fail("readAll", LibraryResult.ServerError("busy"))
        viewModel.markAllRead()
        assertEquals(1L, state.unreadCount)
        assertTrue(state.items.first().isUnread)
        assertFalse(state.markingAll)

        repository.failures.clear()
        repository.pages[0] = NotificationPageDto(items = listOf(shareGranted(2, NotificationTargetType.FILE, 20, readAt = READ_AT),
            shareGranted(1, NotificationTargetType.FOLDER, 10, readAt = "2026-01-01T00:00:00Z")))
        repository.count = 0
        viewModel.markAllRead()

        assertEquals(0L, state.unreadCount)
        assertTrue(state.items.none { it.isUnread })
        assertEquals("2026-01-01T00:00:00Z", state.items.last().readAt)
        assertEquals(2, repository.calls.count { it == "readAll" })
    }

    @Test
    fun unknownOrTargetlessNotificationOnlyMarksRead() {
        val system = NotificationDto(9, type = null, title = "Сообщение", body = "Текст")
        repository.pages[0] = NotificationPageDto(items = listOf(system))
        viewModel.onScreenOpened()

        viewModel.open(system)

        assertEquals(READ_AT, state.items.single().readAt)
        assertNull(viewModel.pendingOpen.value)
    }

    @Test
    fun accountOrServerChangeResetsStateAndDropsLateResponses() {
        val unread = shareGranted(5, NotificationTargetType.FOLDER, 50)
        repository.pages[0] = NotificationPageDto(items = listOf(unread))
        repository.count = 1
        viewModel.onForeground()
        viewModel.onScreenOpened()
        assertEquals(1L, state.unreadCount)
        val readGate = repository.hold("read:5")
        viewModel.open(unread)

        // Ответ прочтения приходит уже после смены пользователя: ни список, ни переход другого аккаунта не меняются.
        repository.count = 0
        tokens.saveTokens(tokensOf("bob"))
        assertEquals(NotificationsStatus.Idle, state.status)
        assertTrue(state.items.isEmpty())
        readGate.complete(Unit)
        assertNull(viewModel.pendingOpen.value)
        assertTrue(state.items.isEmpty())
        assertNull(state.openingId)
        // Вход нового пользователя сразу запрашивает его счётчик.
        assertEquals(0L, state.unreadCount)

        val pageGate = repository.hold("page:0")
        viewModel.onScreenOpened()
        baseUrlProvider.baseUrl = SERVER_B
        pageGate.complete(Unit)
        assertEquals(NotificationsStatus.Idle, state.status)
        assertTrue(state.items.isEmpty())

        tokens.clear()
        assertFalse(state.hasSession)
        assertNull(state.unreadCount)
    }

    private fun tokensOf(subject: String): Tokens {
        val payload = """{"sub":"$subject"}""".toByteArray()
        return Tokens("header.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload)}.signature", "refresh")
    }

    /** Ответы задаёт тест; удержанный запрос не реагирует на отмену, как уже выполняющийся HTTP-вызов. */
    private class FakeNotificationRepository : LibraryRepository(mock(ApiServiceFactory::class.java)) {
        val calls = mutableListOf<String>()
        val pages = HashMap<Int, NotificationPageDto>()
        val failures = HashMap<String, LibraryResult.Failure>()
        private val gates = HashMap<String, CompletableDeferred<Unit>>()
        var count = 0L

        fun fail(key: String, failure: LibraryResult.Failure) {
            failures[key] = failure
        }

        fun hold(key: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[key] = it }

        override suspend fun getNotifications(page: Int): LibraryResult<NotificationPageDto> =
            answer("page:$page") { pages[page] ?: NotificationPageDto(items = emptyList()) }

        override suspend fun getUnreadNotificationCount(): LibraryResult<UnreadNotificationCountDto> =
            answer("count") { UnreadNotificationCountDto(count) }

        override suspend fun markNotificationRead(id: Long): LibraryResult<NotificationDto> =
            answer("read:$id") {
                val current = pages.values.flatMap { it.items.orEmpty() }.first { it.id == id }
                current.copy(readAt = current.readAt ?: READ_AT)
            }

        override suspend fun markAllNotificationsRead(): LibraryResult<Unit> = answer("readAll") { }

        // Значение вычисляется после удержания: тест может изменить ответ, пока запрос «в пути».
        private suspend fun <T> answer(key: String, value: () -> T): LibraryResult<T> {
            calls += key
            gates.remove(key)?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return failures[key] ?: LibraryResult.Success(value())
        }
    }
}

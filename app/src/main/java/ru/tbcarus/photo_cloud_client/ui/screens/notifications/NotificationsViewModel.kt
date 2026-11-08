package ru.tbcarus.photo_cloud_client.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.api.models.NotificationDto
import ru.tbcarus.photo_cloud_client.api.models.NotificationTargetType
import ru.tbcarus.photo_cloud_client.api.models.NotificationType
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.library.LibraryRepository
import ru.tbcarus.photo_cloud_client.library.LibraryResult
import ru.tbcarus.photo_cloud_client.library.LibrarySession
import ru.tbcarus.photo_cloud_client.library.librarySessionFlow
import ru.tbcarus.photo_cloud_client.ui.screens.library.SharedTarget
import javax.inject.Inject

/**
 * Серверные уведомления текущей сессии: счётчик непрочитанных для колокольчика и экран списка.
 * Живёт на уровне Activity, как Library. Состояние прочтения хранит только Server; локально ничего не сохраняется.
 * Доставки push и фоновой нет: счётчик опрашивается, пока приложение на экране.
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: LibraryRepository,
    baseUrlProvider: BaseUrlProvider,
    tokenStorage: TokenStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    // Ресурс уведомления, который нужно открыть в «Доступно мне»; забирает экран через consumeOpen.
    private val _pendingOpen = MutableStateFlow<SharedTarget?>(null)
    val pendingOpen: StateFlow<SharedTarget?> = _pendingOpen.asStateFlow()

    private var session: LibrarySession? = null

    // Номер сессии: ответ, начатый при другом номере, относится к прежнему пользователю или Server и отбрасывается.
    private var generation = 0
    // Номер загрузки списка с первой страницы: ответ прежней загрузки не смешивается с новой.
    private var listGeneration = 0
    // Номер запроса счётчика: применяется только ответ последнего.
    private var countSerial = 0

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var countJob: Job? = null
    private var actionJob: Job? = null
    private var pollJob: Job? = null
    private var nextPage = 0
    private var foreground = false

    init {
        viewModelScope.launch {
            librarySessionFlow(baseUrlProvider, tokenStorage).collect(::onSessionChanged)
        }
    }

    private fun onSessionChanged(newSession: LibrarySession) {
        // Отказ Server при том же пользователе: после нового входа уведомления снова можно запросить.
        if (newSession == session && _uiState.value.hasSession) return
        session = newSession
        val valid = newSession.baseUrl.isNotBlank() && newSession.user != null
        resetSession(if (valid) NotificationsStatus.Idle else NotificationsStatus.NoSession)
        // Вход в аккаунт: счётчик запрашивается сразу, не дожидаясь очередного периода опроса.
        if (valid && foreground) refreshCount(force = true)
    }

    // Запросы и переход прежней сессии отменяются, список и счётчик сбрасываются.
    private fun resetSession(status: NotificationsStatus) {
        generation++
        listGeneration++
        loadJob?.cancel()
        loadMoreJob?.cancel()
        countJob?.cancel()
        actionJob?.cancel()
        nextPage = 0
        _pendingOpen.value = null
        _uiState.update { NotificationsUiState(status = status, contentVersion = it.contentVersion + 1) }
    }

    // --- Счётчик и опрос ---

    /**
     * Приложение на переднем плане: один цикл опроса на процесс, первый запрос — сразу. Повторный вызов
     * при работающем цикле ничего не запускает.
     */
    fun onForeground() {
        foreground = true
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshCount()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Приложение свёрнуто: опрос и незавершённый запрос счётчика прекращаются. */
    fun onBackground() {
        foreground = false
        pollJob?.cancel()
        pollJob = null
        countJob?.cancel()
    }

    /**
     * Запрос счётчика. Без [force] не запускается, пока выполняется предыдущий; с [force] (после прочтения и при
     * открытии экрана) предыдущий отменяется, а его ответ не применяется.
     */
    fun refreshCount(force: Boolean = false) {
        if (!_uiState.value.hasSession) return
        if (countJob?.isActive == true) {
            if (!force) return
            countJob?.cancel()
        }
        val current = generation
        val serial = ++countSerial
        countJob = viewModelScope.launch {
            val result = repository.getUnreadNotificationCount()
            if (current != generation || serial != countSerial) return@launch
            when (result) {
                is LibraryResult.Success -> _uiState.update { it.copy(unreadCount = result.value.count) }
                LibraryResult.Unauthorized -> onUnauthorized()
                // Сбой сети или Server: прежнее значение остаётся до следующего обновления.
                is LibraryResult.Failure -> Unit
            }
        }
    }

    // --- Список ---

    /** Открытие экрана и возврат на него: первая страница и счётчик заново. Во время действия прочтения — нет. */
    fun onScreenOpened() {
        val state = _uiState.value
        if (!state.hasSession || state.status == NotificationsStatus.Loading || state.busy) return
        loadFirstPage()
        refreshCount(force = true)
    }

    /**
     * Экран открыт при новой сессии: загрузка только из Idle. Проверяется состояние ViewModel, а не снимок экрана:
     * возврат на экран мог уже начать или завершить загрузку.
     */
    fun ensureLoaded() {
        if (_uiState.value.status == NotificationsStatus.Idle) onScreenOpened()
    }

    fun onListEndReached() {
        if (_uiState.value.loadMoreError == null) loadMore()
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.status != NotificationsStatus.Content || !state.hasNext || state.isLoadingMore) return
        val page = nextPage
        val current = listGeneration
        _uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
        loadMoreJob = viewModelScope.launch {
            val result = repository.getNotifications(page)
            if (current != listGeneration) return@launch
            when (result) {
                is LibraryResult.Success -> {
                    nextPage = page + 1
                    _uiState.update {
                        it.copy(
                            items = merge(it.items, result.value.items),
                            hasNext = result.value.hasNext,
                            isLoadingMore = false
                        )
                    }
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                is LibraryResult.Failure -> _uiState.update { it.copy(isLoadingMore = false, loadMoreError = failureText(result)) }
            }
        }
    }

    private fun loadFirstPage() {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        val current = ++listGeneration
        _uiState.update {
            it.copy(
                status = NotificationsStatus.Loading,
                items = emptyList(),
                hasNext = false,
                isLoadingMore = false,
                loadMoreError = null,
                contentVersion = it.contentVersion + 1
            )
        }
        loadJob = viewModelScope.launch {
            val result = repository.getNotifications(0)
            if (current != listGeneration) return@launch
            when (result) {
                is LibraryResult.Success -> {
                    nextPage = 1
                    _uiState.update {
                        it.copy(
                            status = NotificationsStatus.Content,
                            items = merge(emptyList(), result.value.items),
                            hasNext = result.value.hasNext
                        )
                    }
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                is LibraryResult.Failure -> _uiState.update { it.copy(status = NotificationsStatus.Error(failureText(result))) }
            }
        }
    }

    // --- Прочтение и переход ---

    /**
     * Нажатие: непрочитанное сначала отмечается на Server, и только после успеха открывается его ресурс.
     * Неудачное прочтение не изображается успешным: уведомление остаётся непрочитанным, перехода нет.
     * Уведомление без поддерживаемой цели только отмечается прочитанным.
     */
    fun open(notification: NotificationDto) {
        val state = _uiState.value
        val current = session ?: return
        if (state.status != NotificationsStatus.Content || state.busy) return
        val target = targetOf(notification, current)
        if (!notification.isUnread) {
            _pendingOpen.value = target
            return
        }
        val sessionAt = generation
        _uiState.update { it.copy(openingId = notification.id, notice = null) }
        actionJob = viewModelScope.launch {
            val result = repository.markNotificationRead(notification.id)
            if (sessionAt != generation) return@launch
            when (result) {
                is LibraryResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            items = state.items.map { if (it.id == result.value.id) result.value else it },
                            openingId = null
                        )
                    }
                    refreshCount(force = true)
                    _pendingOpen.value = target
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                LibraryResult.NotFound -> _uiState.update { it.copy(openingId = null, notice = "Уведомление не найдено") }
                is LibraryResult.Failure -> _uiState.update {
                    it.copy(openingId = null, notice = "Не удалось отметить уведомление прочитанным. ${failureText(result)}")
                }
            }
        }
    }

    /** После 204 список перечитывается с начала: readAt назначил Server. */
    fun markAllRead() {
        val state = _uiState.value
        if (state.status != NotificationsStatus.Content || state.busy) return
        val sessionAt = generation
        _uiState.update { it.copy(markingAll = true, notice = null) }
        actionJob = viewModelScope.launch {
            val result = repository.markAllNotificationsRead()
            if (sessionAt != generation) return@launch
            when (result) {
                is LibraryResult.Success -> {
                    _uiState.update { it.copy(markingAll = false) }
                    loadFirstPage()
                    refreshCount(force = true)
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                is LibraryResult.Failure -> _uiState.update {
                    it.copy(markingAll = false, notice = "Не удалось отметить все уведомления прочитанными. ${failureText(result)}")
                }
            }
        }
    }

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }

    /** Переход принят экраном; другой, уже назначенный позже переход не снимается. */
    fun consumeOpen(target: SharedTarget) {
        if (_pendingOpen.value == target) _pendingOpen.value = null
    }

    // Отказ Server в доступе: данные сессии сбрасываются, выход из аккаунта не выполняется.
    private fun onUnauthorized() = resetSession(NotificationsStatus.NoSession)

    private fun targetOf(notification: NotificationDto, session: LibrarySession): SharedTarget? {
        if (notification.type != NotificationType.SHARE_GRANTED) return null
        val type = notification.targetType ?: return null
        val id = notification.targetId ?: return null
        return SharedTarget(session, isFolder = type == NotificationTargetType.FOLDER, resourceId = id)
    }

    // Offset-пагинация при появлении новых уведомлений может вернуть запись повторно, а ключ строки уникален.
    private fun merge(loaded: List<NotificationDto>, page: List<NotificationDto>?): List<NotificationDto> =
        (loaded + page.orEmpty()).distinctBy { it.id }

    private fun failureText(failure: LibraryResult.Failure): String = when (failure) {
        is LibraryResult.NetworkError -> "Нет связи с сервером: ${failure.message}"
        is LibraryResult.ServerError -> "Ошибка сервера: ${failure.message}"
        LibraryResult.NotFound -> "Не найдено"
        LibraryResult.Unauthorized -> "Требуется вход"
        else -> "Сервер отклонил запрос"
    }

    internal companion object {
        // Период опроса счётчика, пока приложение на экране.
        const val POLL_INTERVAL_MS = 90_000L
    }
}

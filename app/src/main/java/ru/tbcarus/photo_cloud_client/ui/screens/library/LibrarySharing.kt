package ru.tbcarus.photo_cloud_client.ui.screens.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.api.models.GrantShareRequest
import ru.tbcarus.photo_cloud_client.api.models.LinkShareDto
import ru.tbcarus.photo_cloud_client.api.models.ShareAccess
import ru.tbcarus.photo_cloud_client.api.models.ShareDto
import ru.tbcarus.photo_cloud_client.library.LibraryRepository
import ru.tbcarus.photo_cloud_client.library.LibraryResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Срок доступа в форме: бессрочно, на N календарных дней от текущего момента, до даты включительно. */
sealed interface ExpiryChoice {
    data object Never : ExpiryChoice
    data class Days(val text: String = "") : ExpiryChoice
    data class Until(val date: LocalDate? = null) : ExpiryChoice
}

/** Проверенный срок: момент для Server (null — бессрочно) или причина отказа. */
sealed interface ExpiryValue {
    data class Valid(val iso: String?) : ExpiryValue
    data class Invalid(val message: String) : ExpiryValue
}

// Защита от ввода, переполняющего дату; на практике сроки много короче.
private const val MAX_SHARE_DAYS = 36_500

/**
 * Срок вычисляется в часовом поясе устройства и передаётся моментом ISO-8601 со смещением. «До даты D» включает
 * весь день D: граница — начало следующего дня. Нулевой, отрицательный и прошедший срок не принимаются.
 */
fun expiryValue(choice: ExpiryChoice, now: ZonedDateTime): ExpiryValue = when (choice) {
    ExpiryChoice.Never -> ExpiryValue.Valid(null)
    is ExpiryChoice.Days -> {
        val days = choice.text.trim().toIntOrNull()
        when {
            days == null || days <= 0 -> ExpiryValue.Invalid("Укажите положительное число дней")
            days > MAX_SHARE_DAYS -> ExpiryValue.Invalid("Слишком большой срок: не более $MAX_SHARE_DAYS дней")
            else -> ExpiryValue.Valid(isoOffset(now.plusDays(days.toLong())))
        }
    }
    is ExpiryChoice.Until -> {
        val date = choice.date
        val end = date?.plusDays(1)?.atStartOfDay(now.zone)
        when {
            end == null -> ExpiryValue.Invalid("Выберите дату окончания")
            !end.isAfter(now) -> ExpiryValue.Invalid("Дата окончания уже прошла")
            else -> ExpiryValue.Valid(isoOffset(end))
        }
    }
}

private fun isoOffset(value: ZonedDateTime): String =
    value.toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/** Начальный выбор при изменении срока: бессрочный доступ — «Бессрочно», иначе последний включённый день срока. */
fun expiryChoiceOf(expiresAt: String?, zone: ZoneId = ZoneId.systemDefault()): ExpiryChoice {
    if (expiresAt == null) return ExpiryChoice.Never
    return try {
        ExpiryChoice.Until(Instant.parse(expiresAt).minusSeconds(1).atZone(zone).toLocalDate())
    } catch (_: DateTimeParseException) {
        ExpiryChoice.Until()
    }
}

/** Адреса через запятую, точку с запятой, пробел или с новой строки; пустые и повторы (без учёта регистра) убираются. */
fun parseEmails(text: String): List<String> =
    text.split(',', ';', '\n', ' ', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }

/** Выдача отклонена целиком: каждый отклонённый адрес с причиной, без заявления о частичной выдаче. */
fun recipientsRejectedText(rejected: Map<String, String>): String {
    if (rejected.isEmpty()) return "Доступ не предоставлен: Server отклонил получателей"
    val reasons = rejected.entries.joinToString("; ") { (email, reason) ->
        val text = when (reason) {
            "Recipient is not available" -> "нет такого пользователя, он не активирован или заблокирован"
            "Cannot share with yourself" -> "это ваш адрес"
            else -> reason
        }
        "$email — $text"
    }
    return "Доступ не предоставлен никому. $reasons"
}

internal const val LINKS_UNAVAILABLE_TEXT =
    "Публичные ссылки на сервере не настроены: администратору нужно задать публичный адрес и ключ ссылок"

/** Режим окна «Поделиться» и раздела «Предоставлено мной»: доступы пользователям или публичные ссылки. */
enum class ShareMode { USERS, LINKS }

/** Список доступов или ссылок, загружаемый страницами Server. */
data class ShareList<T>(
    val entries: List<T> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val hasNext: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val info: String? = null
)

/**
 * Окно «Поделиться» объекта собственной библиотеки. «Пользователям»: выданные доступы и форма новой выдачи.
 * «По ссылке»: ссылки объекта ([links] загружаются при первом переключении) и создание новой.
 */
data class ShareDialog(
    val id: Int,
    val target: LibraryItem,
    val shares: List<ShareDto> = emptyList(),
    val loading: Boolean = true,
    val loadError: String? = null,
    val hasNext: Boolean = false,
    val loadingMore: Boolean = false,
    val emails: String = "",
    val expiry: ExpiryChoice = ExpiryChoice.Never,
    val granting: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val confirmingRevokeAll: Boolean = false,
    val revokingAll: Boolean = false,
    val mode: ShareMode = ShareMode.USERS,
    val links: ShareList<LinkShareDto>? = null,
    val linkExpiry: ExpiryChoice = ExpiryChoice.Never,
    val creatingLink: Boolean = false,
    // Только что созданная ссылка: её адрес показывается с действиями «Копировать» и «Поделиться ссылкой».
    val createdLink: LinkShareDto? = null,
    val confirmingRevokeAllLinks: Boolean = false,
    val revokingAllLinks: Boolean = false
) {
    val fileId: Long? get() = (target as? LibraryItem.File)?.file?.id
    val folderId: Long? get() = (target as? LibraryItem.Folder)?.folder?.id
    val busy: Boolean get() = granting || revokingAll || creatingLink || revokingAllLinks

    /** Папка может содержать фотографии; у файла — по MIME изображения. */
    val mayContainPhotos: Boolean
        get() = when (target) {
            is LibraryItem.Folder -> true
            is LibraryItem.File -> target.file.mimeType?.startsWith("image/") == true
        }
}

/** Изменение срока одного доступа или ссылки (из окна объекта или из «Предоставлено мной»). */
data class ShareEdit(
    val id: Int,
    val share: ShareAccess,
    val expiry: ExpiryChoice,
    val saving: Boolean = false,
    val error: String? = null
)

/** Подтверждение отзыва одного доступа или ссылки. */
data class ShareRevoke(
    val id: Int,
    val share: ShareAccess,
    val revoking: Boolean = false,
    val error: String? = null
)

/** [granted] — USER-доступы раздела «Предоставлено мной», [grantedLinks] — его ссылки, загружаются при первом переключении. */
data class SharingState(
    val dialog: ShareDialog? = null,
    val granted: ShareList<ShareDto>? = null,
    val grantedLinks: ShareList<LinkShareDto>? = null,
    val grantedMode: ShareMode = ShareMode.USERS,
    val edit: ShareEdit? = null,
    val revoke: ShareRevoke? = null
)

/**
 * Управление выданными USER-доступами и публичными ссылками. Состояние живёт рядом с Library и сбрасывается вместе с её контекстом:
 * [reset] отменяет запросы, а ответы, пришедшие после смены сервера или пользователя, отбрасываются по [generation].
 * Список меняется только после ответа Server, без оптимистичных изменений.
 */
class LibrarySharing(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val onUnauthorized: () -> Unit,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() }
) {
    private val _state = MutableStateFlow(SharingState())
    val state: StateFlow<SharingState> = _state.asStateFlow()

    private var generation = 0
    private val jobs = mutableListOf<Job>()
    private var serial = 0
    private var dialogPage = 0
    private var grantedPage = 0
    private var dialogLinkPage = 0
    private var grantedLinkPage = 0

    /** Смена сервера, пользователя или потеря сессии: окна закрываются, данные прежнего аккаунта не показываются. */
    fun reset() {
        generation++
        jobs.forEach { it.cancel() }
        jobs.clear()
        _state.value = SharingState()
    }

    // --- Окно «Поделиться» ---

    fun open(target: LibraryItem) {
        if (_state.value.dialog != null) return
        _state.update { it.copy(dialog = ShareDialog(++serial, target)) }
        loadDialogShares(reset = true)
    }

    /** Пока выдача или отключение всем выполняются, окно не закрывается: итог должен дойти до списка. */
    fun close() {
        val state = _state.value
        if (state.dialog?.busy == true || state.edit?.saving == true || state.revoke?.revoking == true) return
        _state.update { it.copy(dialog = null, edit = null, revoke = null) }
    }

    fun onEmailsChange(text: String) = updateDialog { if (it.granting) it else it.copy(emails = text, error = null, info = null) }

    fun onExpiryChange(choice: ExpiryChoice) = updateDialog { if (it.granting) it else it.copy(expiry = choice, error = null) }

    fun loadMoreDialogShares() {
        val dialog = _state.value.dialog ?: return
        if (dialog.loading || dialog.loadingMore || !dialog.hasNext) return
        loadDialogShares(reset = false)
    }

    fun retryDialogShares() = loadDialogShares(reset = true)

    /**
     * Адреса проверяются до отправки: пустой список, адрес без «@» и собственный адрес [ownEmail] не отправляются.
     * Окончательно получателей проверяет Server: при недопустимом адресе доступ не получает никто.
     */
    fun grant(ownEmail: String?) {
        val dialog = _state.value.dialog ?: return
        if (dialog.busy) return
        val emails = parseEmails(dialog.emails)
        val problem = when {
            emails.isEmpty() -> "Укажите хотя бы один email"
            emails.any { '@' !in it } -> "Некорректный адрес: ${emails.first { '@' !in it }}"
            ownEmail != null && emails.any { it.equals(ownEmail, ignoreCase = true) } -> "Нельзя предоставить доступ самому себе"
            else -> null
        }
        val expiry = expiryValue(dialog.expiry, now())
        val error = problem ?: (expiry as? ExpiryValue.Invalid)?.message
        if (error != null) {
            updateDialog { it.copy(error = error, info = null) }
            return
        }
        val iso = (expiry as ExpiryValue.Valid).iso
        updateDialog { it.copy(granting = true, error = null, info = null) }
        track { current ->
            val result = repository.grantShare(GrantShareRequest(dialog.fileId, dialog.folderId, emails, iso))
            if (!stillFor(current, dialog.id)) return@track
            when (result) {
                is LibraryResult.Success -> {
                    updateDialog {
                        it.copy(
                            granting = false, emails = "", expiry = ExpiryChoice.Never,
                            info = "Доступ предоставлен: ${result.value.mapNotNull { share -> share.recipient?.email }.joinToString(", ")}"
                        )
                    }
                    loadDialogShares(reset = true)
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                LibraryResult.NotFound -> updateDialog {
                    it.copy(granting = false, error = "«${it.target.name}» не найден: возможно, объект удалён")
                }
                is LibraryResult.Failure -> updateDialog { it.copy(granting = false, error = failureText(result)) }
            }
        }
    }

    fun requestRevokeAll() = updateDialog { if (it.busy || it.shares.isEmpty()) it else it.copy(confirmingRevokeAll = true, error = null) }

    fun dismissRevokeAll() = updateDialog { if (it.revokingAll) it else it.copy(confirmingRevokeAll = false) }

    /** Отключает только USER-доступы этого объекта: сам объект и отдельные доступы к вложенным объектам не меняются. */
    fun confirmRevokeAll() {
        val dialog = _state.value.dialog ?: return
        if (!dialog.confirmingRevokeAll || dialog.busy) return
        updateDialog { it.copy(revokingAll = true, error = null, info = null) }
        track { current ->
            val result = repository.revokeAllShares(dialog.fileId, dialog.folderId)
            if (!stillFor(current, dialog.id)) return@track
            when (result) {
                is LibraryResult.Success -> {
                    updateDialog { it.copy(revokingAll = false, confirmingRevokeAll = false, info = "Доступ всем пользователям отключён") }
                    loadDialogShares(reset = true)
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                LibraryResult.NotFound -> updateDialog {
                    it.copy(revokingAll = false, confirmingRevokeAll = false, error = "«${it.target.name}» не найден: возможно, объект удалён")
                }
                is LibraryResult.Failure -> updateDialog {
                    it.copy(revokingAll = false, confirmingRevokeAll = false, error = failureText(result))
                }
            }
        }
    }

    fun selectMode(mode: ShareMode) {
        val dialog = _state.value.dialog ?: return
        if (dialog.busy || dialog.mode == mode) return
        updateDialog { it.copy(mode = mode, error = null, info = null) }
        if (mode == ShareMode.LINKS && dialog.links == null) {
            updateDialog { it.copy(links = ShareList()) }
            loadLinks(dialog.id, reset = true)
        }
    }

    fun onLinkExpiryChange(choice: ExpiryChoice) = updateDialog { if (it.creatingLink) it else it.copy(linkExpiry = choice, error = null) }

    fun loadMoreDialogLinks() {
        val links = _state.value.dialog?.links ?: return
        if (links.loading || links.loadingMore || !links.hasNext || links.loadMoreError != null) return
        loadLinks(_state.value.dialog?.id, reset = false)
    }

    fun retryDialogLinks() {
        val dialog = _state.value.dialog ?: return
        val links = dialog.links ?: return
        loadLinks(dialog.id, reset = links.error != null || links.entries.isEmpty())
    }

    /**
     * Каждое создание — новая независимая ссылка. Адрес показывается только из ответа Server; ответ без адреса —
     * ошибка без сообщения об успехе, а список ссылок перечитывается, чтобы созданную можно было отозвать.
     */
    fun createLink() {
        val dialog = _state.value.dialog ?: return
        if (dialog.busy) return
        val expiry = expiryValue(dialog.linkExpiry, now())
        if (expiry is ExpiryValue.Invalid) {
            updateDialog { it.copy(error = expiry.message, info = null) }
            return
        }
        updateDialog { it.copy(creatingLink = true, createdLink = null, error = null, info = null) }
        track { current ->
            val result = repository.createLink(dialog.fileId, dialog.folderId, (expiry as ExpiryValue.Valid).iso)
            if (!stillFor(current, dialog.id)) return@track
            when (result) {
                is LibraryResult.Success -> {
                    val link = result.value
                    updateDialog {
                        if (link.shareableUrl == null) {
                            it.copy(creatingLink = false, error = "Server не вернул адрес ссылки. Отзовите её в списке и создайте новую")
                        } else {
                            it.copy(creatingLink = false, createdLink = link, linkExpiry = ExpiryChoice.Never, info = "Ссылка создана")
                        }
                    }
                    loadLinks(dialog.id, reset = true)
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                LibraryResult.NotFound -> updateDialog { it.copy(creatingLink = false, error = goneText(it)) }
                is LibraryResult.Failure -> updateDialog { it.copy(creatingLink = false, error = failureText(result)) }
            }
        }
    }

    fun requestRevokeAllLinks() = updateDialog {
        if (it.busy || it.links?.entries.isNullOrEmpty()) it else it.copy(confirmingRevokeAllLinks = true, error = null)
    }

    fun dismissRevokeAllLinks() = updateDialog { if (it.revokingAllLinks) it else it.copy(confirmingRevokeAllLinks = false) }

    /** Отключает только ссылки этого объекта: USER-доступы и ссылки вложенных объектов не меняются. */
    fun confirmRevokeAllLinks() {
        val dialog = _state.value.dialog ?: return
        if (!dialog.confirmingRevokeAllLinks || dialog.busy) return
        updateDialog { it.copy(revokingAllLinks = true, error = null, info = null) }
        track { current ->
            val result = repository.revokeAllLinks(dialog.fileId, dialog.folderId)
            if (!stillFor(current, dialog.id)) return@track
            when (result) {
                is LibraryResult.Success -> {
                    updateDialog {
                        it.copy(revokingAllLinks = false, confirmingRevokeAllLinks = false, createdLink = null,
                            info = "Все ссылки на объект отключены")
                    }
                    loadLinks(dialog.id, reset = true)
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                LibraryResult.NotFound -> updateDialog {
                    it.copy(revokingAllLinks = false, confirmingRevokeAllLinks = false, error = goneText(it))
                }
                is LibraryResult.Failure -> updateDialog {
                    it.copy(revokingAllLinks = false, confirmingRevokeAllLinks = false, error = failureText(result))
                }
            }
        }
    }

    /** Подтверждение копирования: адрес уже в буфере обмена, в состояние он не записывается повторно. */
    fun onLinkCopied() = announce("Ссылка скопирована")

    private fun loadDialogShares(reset: Boolean) {
        val dialog = _state.value.dialog ?: return
        val page = if (reset) 0 else dialogPage
        updateDialog {
            if (reset) it.copy(loading = true, loadError = null) else it.copy(loadingMore = true, loadError = null)
        }
        track { current ->
            val result = repository.getShares(dialog.fileId, dialog.folderId, page)
            if (!stillFor(current, dialog.id)) return@track
            when (result) {
                is LibraryResult.Success -> {
                    dialogPage = page + 1
                    updateDialog {
                        val loaded = if (reset) emptyList() else it.shares
                        it.copy(
                            shares = (loaded + result.value.items.orEmpty()).distinctBy { share -> share.id },
                            hasNext = result.value.hasNext,
                            loading = false,
                            loadingMore = false
                        )
                    }
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                LibraryResult.NotFound -> updateDialog {
                    it.copy(loading = false, loadingMore = false, loadError = "«${it.target.name}» не найден: возможно, объект удалён")
                }
                is LibraryResult.Failure -> updateDialog {
                    it.copy(loading = false, loadingMore = false, loadError = failureText(result))
                }
            }
        }
    }

    // --- Изменение срока и отзыв одного доступа ---

    fun startEdit(share: ShareAccess) {
        val state = _state.value
        if (state.edit != null || state.revoke != null || state.dialog?.busy == true) return
        _state.update { it.copy(edit = ShareEdit(++serial, share, expiryChoiceOf(share.expiresAt, now().zone))) }
    }

    fun onEditExpiryChange(choice: ExpiryChoice) = updateEdit { if (it.saving) it else it.copy(expiry = choice, error = null) }

    fun dismissEdit() = _state.update { if (it.edit?.saving == true) it else it.copy(edit = null) }

    /** Бессрочный выбор отправляется явным {"expiresAt":null}; истёкший доступ так же продлевается. */
    fun confirmEdit() {
        val edit = _state.value.edit ?: return
        if (edit.saving) return
        val expiry = expiryValue(edit.expiry, now())
        if (expiry is ExpiryValue.Invalid) {
            updateEdit { it.copy(error = expiry.message) }
            return
        }
        updateEdit { it.copy(saving = true, error = null) }
        track { current ->
            val iso = (expiry as ExpiryValue.Valid).iso
            val result: LibraryResult<ShareAccess> = when (val share = edit.share) {
                is LinkShareDto -> repository.updateLinkExpiry(share.id, iso)
                else -> repository.updateShareExpiry(share.id, iso)
            }
            if (current != generation || _state.value.edit?.id != edit.id) return@track
            when (result) {
                is LibraryResult.Success -> {
                    _state.update { it.copy(edit = null) }
                    replaceShare(result.value)
                    announce(if (result.value is LinkShareDto) "Срок ссылки изменён" else "Срок доступа для ${recipientLabel(result.value)} изменён")
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                LibraryResult.NotFound -> {
                    _state.update { it.copy(edit = null) }
                    shareGone(edit.share)
                }
                is LibraryResult.Failure -> updateEdit { it.copy(saving = false, error = failureText(result)) }
            }
        }
    }

    fun startRevoke(share: ShareAccess) {
        val state = _state.value
        if (state.edit != null || state.revoke != null || state.dialog?.busy == true) return
        _state.update { it.copy(revoke = ShareRevoke(++serial, share)) }
    }

    fun dismissRevoke() = _state.update { if (it.revoke?.revoking == true) it else it.copy(revoke = null) }

    /** После 204 доступ убирается из показанных списков; 404 — его уже нет, списки перечитываются. */
    fun confirmRevoke() {
        val revoke = _state.value.revoke ?: return
        if (revoke.revoking) return
        _state.update { it.copy(revoke = revoke.copy(revoking = true, error = null)) }
        track { current ->
            val link = revoke.share is LinkShareDto
            val result = if (link) repository.revokeLink(revoke.share.id) else repository.revokeShare(revoke.share.id)
            if (current != generation || _state.value.revoke?.id != revoke.id) return@track
            when (result) {
                is LibraryResult.Success -> {
                    _state.update { it.copy(revoke = null) }
                    removeShare(revoke.share)
                    announce(if (link) "Ссылка отозвана" else "Доступ для ${recipientLabel(revoke.share)} отозван")
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                LibraryResult.NotFound -> {
                    _state.update { it.copy(revoke = null) }
                    shareGone(revoke.share)
                }
                is LibraryResult.Failure -> _state.update { state ->
                    state.copy(revoke = state.revoke?.copy(revoking = false, error = failureText(result)))
                }
            }
        }
    }

    // --- «Предоставлено мной» ---

    fun openGranted() {
        _state.update { it.copy(granted = ShareList(), grantedLinks = null, grantedMode = ShareMode.USERS) }
        loadGranted(reset = true)
    }

    fun closeGranted() = _state.update {
        it.copy(granted = null, grantedLinks = null, grantedMode = ShareMode.USERS, edit = null, revoke = null)
    }

    /** Режимы раздела загружаются и листаются независимо; ссылки — при первом переключении. */
    fun selectGrantedMode(mode: ShareMode) {
        val state = _state.value
        if (state.granted == null || state.grantedMode == mode) return
        _state.update { it.copy(grantedMode = mode) }
        if (mode == ShareMode.LINKS && state.grantedLinks == null) {
            _state.update { it.copy(grantedLinks = ShareList()) }
            loadLinks(null, reset = true)
        }
    }

    fun refreshGranted() {
        val state = _state.value
        if (state.granted == null) return
        if (state.grantedMode == ShareMode.LINKS) loadLinks(null, reset = true) else loadGranted(reset = true)
    }

    fun loadMoreGrantedLinks() {
        val links = _state.value.grantedLinks ?: return
        if (links.loading || links.loadingMore || !links.hasNext || links.loadMoreError != null) return
        loadLinks(null, reset = false)
    }

    fun retryMoreGrantedLinks() {
        updateLinks(null) { it.copy(loadMoreError = null) }
        loadMoreGrantedLinks()
    }

    fun loadMoreGranted() {
        val granted = _state.value.granted ?: return
        if (granted.loading || granted.loadingMore || !granted.hasNext || granted.loadMoreError != null) return
        loadGranted(reset = false)
    }

    fun retryMoreGranted() {
        updateGranted { it.copy(loadMoreError = null) }
        loadMoreGranted()
    }

    private fun loadGranted(reset: Boolean, info: String? = null) {
        val page = if (reset) 0 else grantedPage
        updateGranted {
            if (reset) it.copy(loading = true, error = null, info = info ?: it.info) else it.copy(loadingMore = true, loadMoreError = null)
        }
        track { current ->
            val result = repository.getShares(null, null, page)
            if (current != generation || _state.value.granted == null) return@track
            when (result) {
                is LibraryResult.Success -> {
                    grantedPage = page + 1
                    updateGranted {
                        val loaded = if (reset) emptyList() else it.entries
                        it.copy(
                            entries = (loaded + result.value.items.orEmpty()).distinctBy { share -> share.id },
                            hasNext = result.value.hasNext,
                            loading = false,
                            loadingMore = false
                        )
                    }
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                is LibraryResult.Failure -> updateGranted {
                    if (reset) it.copy(loading = false, error = failureText(result))
                    else it.copy(loadingMore = false, loadMoreError = failureText(result))
                }
            }
        }
    }

    // --- Общее ---

    /**
     * Ссылки окна объекта ([dialogId]) или раздела «Предоставлено мной» (null). Ответ применяется, только если
     * тот же список ещё показан в той же сессии.
     */
    private fun loadLinks(dialogId: Int?, reset: Boolean, info: String? = null) {
        val dialog = if (dialogId == null) null else _state.value.dialog?.takeIf { it.id == dialogId } ?: return
        val page = if (reset) 0 else if (dialog != null) dialogLinkPage else grantedLinkPage
        updateLinks(dialogId) {
            if (reset) it.copy(loading = true, error = null, loadMoreError = null, info = info ?: it.info)
            else it.copy(loadingMore = true, loadMoreError = null)
        }
        track { current ->
            val result = repository.getLinks(dialog?.fileId, dialog?.folderId, page)
            val shown = if (dialogId == null) _state.value.grantedLinks != null else _state.value.dialog?.id == dialogId
            if (current != generation || !shown) return@track
            when (result) {
                is LibraryResult.Success -> {
                    if (dialog != null) dialogLinkPage = page + 1 else grantedLinkPage = page + 1
                    updateLinks(dialogId) {
                        val loaded = if (reset) emptyList() else it.entries
                        it.copy(
                            entries = (loaded + result.value.items.orEmpty()).distinctBy { link -> link.id },
                            hasNext = result.value.hasNext,
                            loading = false,
                            loadingMore = false
                        )
                    }
                }
                LibraryResult.Unauthorized -> onUnauthorized()
                is LibraryResult.Failure -> {
                    val text = if (result == LibraryResult.NotFound && dialog != null) goneText(dialog) else failureText(result)
                    updateLinks(dialogId) {
                        if (reset) it.copy(loading = false, error = text) else it.copy(loadingMore = false, loadMoreError = text)
                    }
                }
            }
        }
    }

    private fun replaceShare(share: ShareAccess) = _state.update { state ->
        when (share) {
            is LinkShareDto -> state.copy(
                dialog = state.dialog?.let { dialog ->
                    dialog.copy(
                        links = dialog.links?.let { it.copy(entries = it.entries.map { old -> if (old.id == share.id) share else old }) },
                        createdLink = dialog.createdLink?.let { if (it.id == share.id) share else it }
                    )
                },
                grantedLinks = state.grantedLinks?.let { it.copy(entries = it.entries.map { old -> if (old.id == share.id) share else old }) }
            )
            is ShareDto -> state.copy(
                dialog = state.dialog?.let { it.copy(shares = it.shares.map { old -> if (old.id == share.id) share else old }) },
                granted = state.granted?.let { it.copy(entries = it.entries.map { old -> if (old.id == share.id) share else old }) }
            )
            else -> state
        }
    }

    private fun removeShare(share: ShareAccess) = _state.update { state ->
        if (share is LinkShareDto) {
            state.copy(
                dialog = state.dialog?.let { dialog ->
                    dialog.copy(
                        links = dialog.links?.let { it.copy(entries = it.entries.filter { link -> link.id != share.id }) },
                        createdLink = dialog.createdLink?.takeIf { it.id != share.id }
                    )
                },
                grantedLinks = state.grantedLinks?.let { it.copy(entries = it.entries.filter { link -> link.id != share.id }) }
            )
        } else {
            state.copy(
                dialog = state.dialog?.let { it.copy(shares = it.shares.filter { old -> old.id != share.id }) },
                granted = state.granted?.let { it.copy(entries = it.entries.filter { old -> old.id != share.id }) }
            )
        }
    }

    // Доступ или ссылка уже отозваны или удалены вместе с объектом: показанные списки перечитываются с начала.
    private fun shareGone(share: ShareAccess) {
        if (share is LinkShareDto) {
            val message = "Ссылка уже отозвана или удалена"
            removeShare(share)
            val dialog = _state.value.dialog
            if (dialog != null) {
                updateDialog { it.copy(info = message) }
                if (dialog.links != null) loadLinks(dialog.id, reset = true)
            }
            if (_state.value.grantedLinks != null) loadLinks(null, reset = true, info = message)
            return
        }
        val message = "Доступ для ${recipientLabel(share)} уже отозван или удалён"
        if (_state.value.dialog != null) {
            updateDialog { it.copy(info = message) }
            loadDialogShares(reset = true)
        }
        if (_state.value.granted != null) loadGranted(reset = true, info = message)
    }

    private fun announce(message: String) = _state.update { state ->
        when {
            state.dialog != null -> state.copy(dialog = state.dialog.copy(info = message, error = null))
            state.grantedMode == ShareMode.LINKS -> state.copy(grantedLinks = state.grantedLinks?.copy(info = message))
            else -> state.copy(granted = state.granted?.copy(info = message))
        }
    }

    private fun recipientLabel(share: ShareAccess): String =
        (share as? ShareDto)?.recipient?.let { it.email ?: it.displayName } ?: "получателя"

    private fun goneText(dialog: ShareDialog): String = "«${dialog.target.name}» не найден: возможно, объект удалён"

    private fun updateLinks(dialogId: Int?, change: (ShareList<LinkShareDto>) -> ShareList<LinkShareDto>) = _state.update { state ->
        if (dialogId == null) {
            state.grantedLinks?.let { state.copy(grantedLinks = change(it)) } ?: state
        } else {
            val dialog = state.dialog?.takeIf { it.id == dialogId }
            dialog?.links?.let { state.copy(dialog = dialog.copy(links = change(it))) } ?: state
        }
    }

    private fun stillFor(current: Int, dialogId: Int): Boolean = current == generation && _state.value.dialog?.id == dialogId

    private fun track(block: suspend (Int) -> Unit) {
        val current = generation
        val job = scope.launch { block(current) }
        jobs += job
        job.invokeOnCompletion { jobs.remove(job) }
    }

    private fun updateDialog(change: (ShareDialog) -> ShareDialog) =
        _state.update { state -> state.dialog?.let { state.copy(dialog = change(it)) } ?: state }

    private fun updateEdit(change: (ShareEdit) -> ShareEdit) =
        _state.update { state -> state.edit?.let { state.copy(edit = change(it)) } ?: state }

    private fun updateGranted(change: (ShareList<ShareDto>) -> ShareList<ShareDto>) =
        _state.update { state -> state.granted?.let { state.copy(granted = change(it)) } ?: state }

    private fun failureText(failure: LibraryResult.Failure): String = when (failure) {
        is LibraryResult.NetworkError -> "Нет связи с сервером: ${failure.message}"
        is LibraryResult.ServerError -> "Ошибка сервера: ${failure.message}"
        is LibraryResult.Rejected -> "Сервер отклонил операцию: ${failure.message}"
        is LibraryResult.RecipientsRejected -> recipientsRejectedText(failure.rejected)
        LibraryResult.LinksUnavailable -> LINKS_UNAVAILABLE_TEXT
        is LibraryResult.UnknownResult -> "Результат операции неизвестен: ${failure.message}"
        LibraryResult.NotFound -> "Не найдено: возможно, объект или доступ удалены"
        LibraryResult.Unauthorized -> "Требуется вход"
        LibraryResult.NameTaken, LibraryResult.Managed -> "Сервер отклонил операцию"
    }
}

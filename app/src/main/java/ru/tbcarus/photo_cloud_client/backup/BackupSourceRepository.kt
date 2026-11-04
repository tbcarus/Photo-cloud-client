package ru.tbcarus.photo_cloud_client.backup

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.transfer.libraryNameProblem
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Итог действия пользователя с источником; message — текст для Settings. */
sealed interface SourceActionResult {
    data object Done : SourceActionResult

    /** Запись источника сохранена, регистрация не подтверждена. */
    data class Failed(val message: String) : SourceActionResult

    /** Подключение не начато: записи источника нет. */
    data class NotConnected(val message: String) : SourceActionResult
}

/**
 * Подключение выбранной Android-папки как источника автоматического резервирования (этап 5.2): разрешение SAF,
 * запись в Room и регистрация на Server. Файлы не сканируются и не загружаются.
 *
 * Регистрация выполняется только по действию пользователя: при старте приложения и входе повторов нет,
 * а ACTIVE-источник повторно не регистрируется никогда.
 *
 * Отключение (этап 5.5) — один метод для Settings и Library: подтверждённое Server отключение переводит запись
 * в REMOTE_DISABLED, ничего не удаляя; повторное подключение не выполняется.
 */
@Singleton
class BackupSourceRepository @Inject constructor(
    private val dao: BackupSourceDao,
    private val remote: BackupSourceRemote,
    private val access: SourceAccessChecker,
    private val accounts: BackupAccountProvider,
    private val scheduler: FolderBackupScheduler
) {

    internal companion object {
        const val NO_ACCOUNT = "Войдите в аккаунт и задайте адрес сервера"
        const val ALREADY_CONNECTED = "Эта папка уже подключена"
        const val ACCESS_NOT_SAVED = "Не удалось сохранить доступ к папке. Выберите её заново"
        const val OTHER_ACCOUNT = "Источник подключён в другом аккаунте или на другом сервере"
        const val NAME_LOCKED = "Имя нельзя изменить, пока не известен результат прошлого запроса: повторите с прежним именем"
        const val AUTH_REQUIRED = "Требуется вход"
        const val ACCOUNT_CHANGED = "Аккаунт сменился во время подключения. Повторите"
        const val MISMATCHED_RESPONSE = "Ответ сервера не соответствует запросу. Повторите"
        const val UNKNOWN_SUFFIX = ". Результат неизвестен — повторите с тем же именем"
        const val REMOTE_DISABLED = "Источник отключён на сервере: повторная регистрация не выполняется"
        const val DISABLE_ACCOUNT_CHANGED = "Аккаунт или сервер сменились: отключение не выполнено"
        const val DISABLE_CONTEXT_CHANGED = "Аккаунт или сервер сменились во время отключения. Откройте источник заново и повторите"
        const val DISABLE_NOT_FOUND = "Папка источника не найдена на сервере: отключение не подтверждено"
        const val DISABLE_UNKNOWN_SUFFIX = ". Источник не отключён — повторите"
    }

    // Одна регистрация одновременно: двойное нажатие не отправит два запроса одного источника.
    private val mutex = Mutex()

    fun observe(account: BackupAccount): Flow<List<BackupSource>> = dao.observe(account.serverUrl, account.accountId)

    /**
     * Сохраняет разрешение и запись источника до первого запроса: потерянный ответ повторяется с тем же UUID.
     * Имя не исправляется: недопустимое имя возвращается как ошибка до создания записи.
     */
    suspend fun connect(treeUri: Uri, localName: String, serverName: String): SourceActionResult {
        val account = accounts.current() ?: return SourceActionResult.NotConnected(NO_ACCOUNT)
        libraryNameProblem(serverName)?.let { return SourceActionResult.NotConnected(it) }
        val id = withContext(Dispatchers.IO) {
            if (dao.countWithTree(account.serverUrl, account.accountId, treeUri.toString()) > 0) return@withContext null
            if (!access.persist(treeUri)) return@withContext -1L
            dao.insert(
                BackupSource(
                    sourceUuid = UUID.randomUUID().toString(),
                    treeUri = treeUri.toString(),
                    localName = localName,
                    serverName = serverName,
                    serverRootFolderId = null,
                    serverUrl = account.serverUrl,
                    accountId = account.accountId,
                    registrationState = RegistrationState.PENDING_REGISTRATION,
                    registrationError = null,
                    accessState = SourceAccess.AVAILABLE,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return when (id) {
            null -> SourceActionResult.NotConnected(ALREADY_CONNECTED)
            -1L -> SourceActionResult.NotConnected(ACCESS_NOT_SAVED)
            else -> register(id)
        }
    }

    /**
     * Явный повтор. После окончательного отказа имя можно сменить; при неизвестном исходе прошлого запроса
     * повтор идёт только с прежним именем, иначе Server мог бы уже держать этот UUID под старым именем.
     */
    suspend fun retry(id: Long, serverName: String? = null): SourceActionResult {
        val source = dao.get(id) ?: return SourceActionResult.Done
        if (source.registrationState == RegistrationState.ACTIVE) return SourceActionResult.Done
        if (source.registrationState == RegistrationState.REMOTE_DISABLED) return SourceActionResult.Failed(REMOTE_DISABLED)
        if (serverName != null && serverName != source.serverName) {
            if (source.registrationState != RegistrationState.REGISTRATION_FAILED) {
                return SourceActionResult.Failed(NAME_LOCKED)
            }
            libraryNameProblem(serverName)?.let { return SourceActionResult.Failed(it) }
            dao.renameFailed(id, serverName)
        }
        return register(id)
    }

    /** Отмена записи, которую Server окончательно отклонил: на Server её нет, URI и UUID больше не нужны. */
    suspend fun cancelFailed(id: Long) {
        dao.deleteFailed(id)
    }

    /**
     * Отключает источник по ID его корня на Server. expected — Server и аккаунт, в которых пользователь видел источник:
     * в другом контексте DELETE не отправляется, а ответ, полученный после смены контекста, не меняет Room.
     * Запись Room (если она есть на этом устройстве) меняется только после 204; повтор идёт к тому же ID.
     */
    suspend fun disable(expected: BackupAccount, folderId: Long, sourceUuid: String?): SourceActionResult = mutex.withLock {
        if (accounts.current() != expected) return SourceActionResult.Failed(DISABLE_ACCOUNT_CHANGED)
        val outcome = remote.disable(folderId)
        if (accounts.current() != expected) return SourceActionResult.Failed(DISABLE_CONTEXT_CHANGED)
        when (outcome) {
            DisableOutcome.Disabled -> {
                withContext(Dispatchers.IO) { dao.markDisabled(expected.serverUrl, expected.accountId, folderId, sourceUuid) }
                SourceActionResult.Done
            }
            DisableOutcome.NotFound -> SourceActionResult.Failed(DISABLE_NOT_FOUND)
            is DisableOutcome.Rejected -> SourceActionResult.Failed("Сервер отклонил отключение: ${outcome.message}")
            DisableOutcome.Unauthorized -> SourceActionResult.Failed(AUTH_REQUIRED)
            is DisableOutcome.Unknown -> SourceActionResult.Failed(outcome.message + DISABLE_UNKNOWN_SUFFIX)
        }
    }

    /** Перечитывает доступ к деревьям источников аккаунта: при открытии Settings и возврате на экран. */
    suspend fun refreshAccess(account: BackupAccount) = withContext(Dispatchers.IO) {
        for (source in dao.list(account.serverUrl, account.accountId)) {
            val current = access.check(Uri.parse(source.treeUri))
            if (current != source.accessState) dao.updateAccess(source.id, current)
        }
    }

    private suspend fun register(id: Long): SourceActionResult = mutex.withLock {
        val source = dao.get(id) ?: return SourceActionResult.Done
        if (source.registrationState == RegistrationState.ACTIVE) return SourceActionResult.Done
        if (source.registrationState == RegistrationState.REMOTE_DISABLED) return SourceActionResult.Failed(REMOTE_DISABLED)
        val account = accounts.current() ?: return SourceActionResult.Failed(NO_ACCOUNT)
        if (account != source.account) return SourceActionResult.Failed(OTHER_ACCOUNT)

        // С этого момента исход неизвестен до ответа: прерванный запрос оставит имя неизменяемым.
        if (source.registrationState != RegistrationState.PENDING_REGISTRATION) {
            dao.markNotRegistered(id, RegistrationState.PENDING_REGISTRATION, null)
        }
        val outcome = remote.register(source.serverName, source.sourceUuid)
        if (accounts.current() != account) {
            dao.markNotRegistered(id, RegistrationState.PENDING_REGISTRATION, ACCOUNT_CHANGED)
            return SourceActionResult.Failed(ACCOUNT_CHANGED)
        }
        when (outcome) {
            is RegistrationOutcome.Registered -> {
                val folder = outcome.folder
                if (folder.id <= 0 || !folder.backupSource.equals(source.sourceUuid, ignoreCase = true) ||
                    folder.name != source.serverName
                ) {
                    dao.markNotRegistered(id, RegistrationState.PENDING_REGISTRATION, MISMATCHED_RESPONSE)
                    return SourceActionResult.Failed(MISMATCHED_RESPONSE)
                }
                dao.markActive(id, folder.id)
                // Подключённый источник сразу начинает резервирование, не дожидаясь periodic.
                scheduler.enqueuePeriodic()
                scheduler.enqueue()
                SourceActionResult.Done
            }
            is RegistrationOutcome.Rejected -> {
                val message = if (outcome.code == 409) nameConflict(source.serverName) else "Сервер отклонил имя: ${outcome.message}"
                dao.markNotRegistered(id, RegistrationState.REGISTRATION_FAILED, message)
                SourceActionResult.Failed(message)
            }
            RegistrationOutcome.Unauthorized -> {
                dao.markNotRegistered(id, RegistrationState.PENDING_REGISTRATION, AUTH_REQUIRED)
                SourceActionResult.Failed(AUTH_REQUIRED)
            }
            is RegistrationOutcome.Unknown -> {
                val message = outcome.message + UNKNOWN_SUFFIX
                dao.markNotRegistered(id, RegistrationState.PENDING_REGISTRATION, message)
                SourceActionResult.Failed(message)
            }
        }
    }

    private fun nameConflict(name: String) =
        "Имя «$name» уже занято в AutoUploads на сервере. Измените имя или освободите его на сервере и повторите"

    private val BackupSource.account get() = BackupAccount(serverUrl, accountId)
}

package ru.tbcarus.photo_cloud_client.backup

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.media.SyncConfig
import ru.tbcarus.photo_cloud_client.media.WorkBudget
import ru.tbcarus.photo_cloud_client.transfer.LocalDocuments
import ru.tbcarus.photo_cloud_client.transfer.LocalSourceException
import ru.tbcarus.photo_cloud_client.transfer.MAX_TREE_DEPTH
import ru.tbcarus.photo_cloud_client.transfer.TransferManager
import ru.tbcarus.photo_cloud_client.transfer.UploadRequestBody
import ru.tbcarus.photo_cloud_client.transfer.UploadTooLargeException
import ru.tbcarus.photo_cloud_client.transfer.libraryNameProblem
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Итог прогона: был ли прогресс, осталась ли работа и нужен ли повтор с backoff. */
data class FolderBackupRun(val progress: Boolean, val remaining: Boolean, val retry: Boolean)

/**
 * Автоматическое резервирование новых файлов и папок активных источников (этап 5.3).
 *
 * Обход дерева SAF идёт по поколениям через Room: за ход читается ограниченное число папок, прочитанная папка
 * отмечается в той же транзакции, поэтому прерванный обход продолжается с непрочитанных папок, а не с начала.
 * Источники обрабатываются ходами по очереди: большой первый источник не задерживает остальные.
 * Изменённые после прежней отправки файлы не отправляются: они ждут архивирования прежней версии (этап 5.4).
 * Серверные файлы никогда не удаляются и не заменяются.
 */
@Singleton
open class FolderBackupEngine @Inject constructor(
    private val sources: BackupSourceDao,
    private val tree: BackupTreeDao,
    private val remote: FolderBackupRemote,
    private val documents: LocalDocuments,
    private val access: SourceAccessChecker,
    private val accounts: BackupAccountProvider
) {

    internal companion object {
        const val TAG = "FolderBackupEngine"
        const val MAX_BYTES = TransferManager.MAX_UPLOAD_BYTES

        const val EMPTY = "Пустой файл: Server пока не принимает такие файлы"
        const val TOO_LARGE = "Файл больше 100 МиБ"
        const val VIRTUAL = "Документ без локального содержимого"
        const val UNREADABLE = "Файл не читается; повтор при следующем обходе"
        const val CHANGED_DURING_UPLOAD = "Файл изменился во время передачи; сверка при следующем обходе"
        const val FOLDER_NOT_READY = "Папка назначения ещё не создана на сервере"
        const val PARENT_NOT_CREATED = "Родительская папка не создана на сервере"
        const val FOLDER_NAME_TAKEN = "Имя занято на сервере файлом или папкой с другим регистром"
        const val FILE_NAME_TAKEN = "Имя занято на сервере папкой"
        const val TOO_DEEP = "Слишком глубокая вложенность"
        const val SOURCE_DISABLED = "Источник отключён на сервере"
        const val SOURCE_GONE = "Папка источника не найдена на сервере"
    }

    private enum class Turn { PROGRESS, IDLE, STOP, RETRY, ACCOUNT_CHANGED }

    /** Состояние источника в пределах одного прогона. */
    private class SourceRun(val source: BackupSource, val treeUri: Uri, var generation: Int) {
        var verified = false
        var scanDecided = false
    }

    // Один прогон одновременно: разовый и periodic не обрабатывают одно дерево параллельно.
    private val mutex = Mutex()

    /** null — идёт другой прогон: он сам продолжит работу. */
    open suspend fun run(budget: WorkBudget, fullScan: Boolean): FolderBackupRun? {
        if (!mutex.tryLock()) return null
        try {
            return withContext(Dispatchers.IO) { runLocked(budget, fullScan) }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runLocked(budget: WorkBudget, fullScan: Boolean): FolderBackupRun {
        // Отправка, прерванная гибелью процесса или отменой, начинается заново: Server идемпотентен по имени и checksum.
        tree.resetUploading()
        val account = accounts.current() ?: return FolderBackupRun(progress = false, remaining = false, retry = false)
        val runs = sources.listActive(account.serverUrl, account.accountId).mapNotNull { source ->
            val uri = Uri.parse(source.treeUri)
            val state = access.check(uri)
            if (state != source.accessState) sources.updateAccess(source.id, state)
            if (state == SourceAccess.AVAILABLE) SourceRun(source, uri, source.scanGeneration) else null
        }.toMutableList()

        var progress = false
        while (runs.isNotEmpty() && !budget.isExhausted()) {
            var turnProgress = false
            for (run in runs.toList()) {
                if (budget.isExhausted()) break
                when (turn(run, account, fullScan, budget)) {
                    Turn.PROGRESS -> {
                        turnProgress = true
                        progress = true
                    }
                    Turn.IDLE -> Unit
                    Turn.STOP -> runs.remove(run)
                    Turn.RETRY -> return FolderBackupRun(progress, remaining = true, retry = true)
                    // Работа другого аккаунта начнётся его собственным запуском.
                    Turn.ACCOUNT_CHANGED -> return FolderBackupRun(progress, remaining = false, retry = false)
                }
            }
            if (!turnProgress) break
        }
        val remaining = runs.any { hasWork(it) }
        return FolderBackupRun(progress, remaining, retry = false)
    }

    private suspend fun hasWork(run: SourceRun): Boolean =
        tree.countFolderWork(run.source.id, run.generation) > 0 || tree.countToProcess(run.source.id) > 0

    private suspend fun turn(run: SourceRun, account: BackupAccount, fullScan: Boolean, budget: WorkBudget): Turn {
        // Перед первой записью прогона: корень по-прежнему активный источник с этим UUID.
        if (!run.verified) {
            when (verifyActive(run)) {
                Turn.PROGRESS -> run.verified = true
                Turn.STOP -> return Turn.STOP
                else -> return Turn.RETRY
            }
        }
        var progress = scan(run, fullScan, budget)
        when (val folders = createFolders(run, account, budget)) {
            Turn.PROGRESS -> progress = true
            Turn.IDLE -> Unit
            else -> return folders
        }
        when (val files = uploadFiles(run, account, budget)) {
            Turn.PROGRESS -> progress = true
            Turn.IDLE -> Unit
            else -> return files
        }
        return if (progress) Turn.PROGRESS else Turn.IDLE
    }

    // --- Проверка активности источника ---

    /**
     * Сопоставляет корень источника с потомком AutoUploads по ID и UUID через read-only API.
     * PROGRESS — активен, STOP — отключён или удалён на Server (записано в Room), RETRY — нет ответа.
     */
    private suspend fun verifyActive(run: SourceRun): Turn {
        val source = run.source
        val root = when (val r = remote.root()) {
            is BackupResult.Ok -> r.value
            else -> return Turn.RETRY
        }
        val container = when (val r = remote.children(root.id)) {
            is BackupResult.Ok -> r.value.firstOrNull { it.isAutoUploads }
            else -> return Turn.RETRY
        } ?: return disable(source, SOURCE_GONE)
        val sourceFolder = when (val r = remote.children(container.id)) {
            is BackupResult.Ok -> r.value.firstOrNull { it.id == source.serverRootFolderId }
            else -> return Turn.RETRY
        } ?: return disable(source, SOURCE_GONE)
        return if (sourceFolder.backupSource.equals(source.sourceUuid, ignoreCase = true)) Turn.PROGRESS
        else disable(source, SOURCE_DISABLED)
    }

    private suspend fun disable(source: BackupSource, reason: String): Turn {
        Log.w(TAG, "Источник ${source.id} больше не активен на сервере: $reason")
        sources.markRemoteDisabled(source.id, reason)
        return Turn.STOP
    }

    /** 409 FOLDER_MANAGED или 404 корня: повторная регистрация не выполняется, решение — по read-only проверке. */
    private suspend fun recheck(run: SourceRun): Turn = when (verifyActive(run)) {
        Turn.STOP -> Turn.STOP
        else -> Turn.RETRY
    }

    // --- Обход дерева ---

    private suspend fun scan(run: SourceRun, fullScan: Boolean, budget: WorkBudget): Boolean {
        val sourceId = run.source.id
        if (!run.scanDecided) {
            run.scanDecided = true
            val inProgress = run.generation > 0 && tree.foldersToList(sourceId, run.generation, 1).isNotEmpty()
            // Продолжение прогона не начинает новый обход: оно дочитывает начатый и отправляет очередь.
            if (run.generation == 0 || (fullScan && !inProgress)) startScan(run)
        }
        var listed = 0
        while (listed < SyncConfig.FOLDER_SCAN_PER_TURN && !budget.isExhausted()) {
            val folder = tree.foldersToList(sourceId, run.generation, 1).firstOrNull() ?: break
            listFolder(run, folder)
            listed++
        }
        if (listed > 0 && tree.foldersToList(sourceId, run.generation, 1).isEmpty()) finishScan(run)
        return listed > 0
    }

    private suspend fun startScan(run: SourceRun) {
        val generation = run.generation + 1
        val source = run.source
        sources.startScan(source.id, generation)
        run.generation = generation
        val rootDocument = runCatching { DocumentsContract.getTreeDocumentId(run.treeUri) }.getOrNull() ?: return
        val root = tree.folderByPath(source.id, "")
        if (root == null) {
            tree.insertFolder(
                BackupFolder(
                    sourceId = source.id, parentId = null, name = source.localName, relativePath = "",
                    documentId = rootDocument, serverFolderId = source.serverRootFolderId, problem = null,
                    seenGeneration = generation, listedGeneration = 0
                )
            )
        } else {
            tree.updateFolder(root.copy(documentId = rootDocument, serverFolderId = source.serverRootFolderId, seenGeneration = generation, problem = null))
        }
    }

    /** Полный обход завершён; исчезнувшие файлы отмечаются, только если все папки прочитаны без ошибок. */
    private suspend fun finishScan(run: SourceRun) {
        val now = System.currentTimeMillis()
        val errors = sources.get(run.source.id)?.scanErrors ?: 1
        if (errors == 0) tree.markMissing(run.source.id, run.generation, now)
        sources.finishScan(run.source.id, now)
    }

    private suspend fun listFolder(run: SourceRun, folder: BackupFolder) {
        val generation = run.generation
        val depth = if (folder.relativePath.isEmpty()) 0 else folder.relativePath.count { it == '/' } + 1
        if (depth >= MAX_TREE_DEPTH) {
            tree.setFolderProblem(folder.id, TOO_DEEP)
            tree.markListed(folder.id, generation)
            return
        }
        val rows = documents.children(run.treeUri, folder.documentId)
        if (rows == null) {
            // Неполный обход: файлы этой папки не будут отмечены исчезнувшими.
            sources.countScanError(run.source.id)
            tree.markListed(folder.id, generation)
            return
        }
        val folders = tree.childFolders(folder.id).associateBy { it.name }
        val files = tree.filesIn(folder.id).associateBy { it.name }
        val newFolders = ArrayList<BackupFolder>()
        val seenFolders = ArrayList<BackupFolder>()
        val newFiles = ArrayList<BackupFile>()
        val changedFiles = ArrayList<BackupFile>()
        // Ссылка на саму папку и повтор имени в ответе провайдера не учитываются.
        for (row in rows.filter { it.id != folder.documentId && it.name != null }.distinctBy { it.name }) {
            val name = row.name!!
            val path = if (folder.relativePath.isEmpty()) name else "${folder.relativePath}/$name"
            if (row.isDirectory) {
                val known = folders[name]
                if (known == null) {
                    newFolders += BackupFolder(
                        sourceId = run.source.id, parentId = folder.id, name = name, relativePath = path,
                        documentId = row.id, serverFolderId = null, problem = null,
                        seenGeneration = generation, listedGeneration = 0
                    )
                } else {
                    seenFolders += known.copy(documentId = row.id, seenGeneration = generation, problem = null)
                }
            } else {
                val known = files[name]
                if (known == null) {
                    newFiles += BackupFile(
                        sourceId = run.source.id, folderId = folder.id, name = name, relativePath = path,
                        documentId = row.id, mimeType = row.mimeType, size = row.size, lastModified = row.lastModified,
                        checksum = null, serverFileId = null, syncedChecksum = null,
                        status = if (row.isVirtual) BackupFileStatus.SKIPPED else BackupFileStatus.PENDING,
                        failureReason = if (row.isVirtual) VIRTUAL else null,
                        lastFailureAt = null, seenGeneration = generation, missingSince = null
                    )
                } else {
                    changedFiles += rescanned(known, row, generation)
                }
            }
        }
        tree.applyListing(folder, generation, newFolders, seenFolders, newFiles, changedFiles)
    }

    /**
     * Сведения файла из обхода. Изменение размера или времени, а также их отсутствие у отправленного файла
     * требуют сверки содержимого: метаданные не доказывают неизменность.
     */
    private fun rescanned(known: BackupFile, row: ru.tbcarus.photo_cloud_client.transfer.DocumentRow, generation: Int): BackupFile {
        val metadataChanged = row.size != known.size || row.lastModified != known.lastModified
        val unreliable = row.size == null || row.lastModified == null
        val status = when {
            row.isVirtual -> BackupFileStatus.SKIPPED
            metadataChanged -> BackupFileStatus.PENDING
            unreliable && known.status == BackupFileStatus.SYNCED -> BackupFileStatus.PENDING
            known.status == BackupFileStatus.FAILED || known.status == BackupFileStatus.UPLOADING -> BackupFileStatus.PENDING
            else -> known.status
        }
        val reason = when {
            row.isVirtual -> VIRTUAL
            status == BackupFileStatus.PENDING -> null
            else -> known.failureReason
        }
        return known.copy(
            documentId = row.id, mimeType = row.mimeType, size = row.size, lastModified = row.lastModified,
            status = status, failureReason = reason, seenGeneration = generation, missingSince = null
        )
    }

    // --- Папки на Server ---

    private suspend fun createFolders(run: SourceRun, account: BackupAccount, budget: WorkBudget): Turn {
        var handled = 0
        while (handled < SyncConfig.FOLDER_CREATE_PER_TURN && !budget.isExhausted()) {
            val folder = tree.foldersToCreate(run.source.id, run.generation, 1).firstOrNull() ?: break
            handled++
            val parent = folder.parentId?.let { tree.folder(it) }
            val parentServerId = parent?.serverFolderId
            if (parentServerId == null) {
                // Родитель обнаружен раньше и уже обработан: без его папки вложенную создать нельзя.
                tree.setFolderProblem(folder.id, parent?.problem?.let { "$PARENT_NOT_CREATED: $it" } ?: PARENT_NOT_CREATED)
                continue
            }
            val nameProblem = libraryNameProblem(folder.name)
            if (nameProblem != null) {
                tree.setFolderProblem(folder.id, nameProblem)
                continue
            }
            if (accounts.current() != account) return Turn.ACCOUNT_CHANGED
            when (val result = remote.createFolder(parentServerId, folder.name, run.source.sourceUuid)) {
                is BackupResult.Ok ->
                    if (result.value.id > 0 && result.value.parentId == parentServerId && result.value.name == folder.name) {
                        tree.setServerFolder(folder.id, result.value.id)
                    } else {
                        return Turn.RETRY
                    }
                is BackupResult.Http -> when {
                    result.isFolderManaged -> return recheck(run)
                    // Папка уже есть, например ответ прошлого запроса потерян: используется существующая.
                    result.code == 409 -> when (val existing = remote.children(parentServerId)) {
                        is BackupResult.Ok -> {
                            val same = existing.value.firstOrNull { it.name == folder.name }
                            if (same != null) tree.setServerFolder(folder.id, same.id) else tree.setFolderProblem(folder.id, FOLDER_NAME_TAKEN)
                        }
                        else -> return Turn.RETRY
                    }
                    result.code == 400 -> tree.setFolderProblem(folder.id, "Сервер отклонил имя: ${result.message}")
                    result.code == 404 -> {
                        if (parent.parentId == null) return recheck(run)
                        // Сохранённый ID родителя устарел: при следующем прогоне папка будет найдена заново.
                        tree.forgetServerFolder(parent.id)
                        return Turn.STOP
                    }
                    else -> return Turn.RETRY
                }
                is BackupResult.Network -> return Turn.RETRY
            }
        }
        return if (handled > 0) Turn.PROGRESS else Turn.IDLE
    }

    // --- Файлы ---

    private suspend fun uploadFiles(run: SourceRun, account: BackupAccount, budget: WorkBudget): Turn {
        var handled = 0
        while (handled < SyncConfig.FOLDER_FILES_PER_TURN && !budget.isExhausted()) {
            val file = tree.filesToProcess(run.source.id, 1).firstOrNull() ?: break
            handled++
            val result = processFile(run, account, file, budget)
            if (result != Turn.PROGRESS) return result
        }
        return if (handled > 0) Turn.PROGRESS else Turn.IDLE
    }

    /** PROGRESS — строка вышла из PENDING; остальные исходы возвращают её в PENDING и останавливают ход. */
    private suspend fun processFile(run: SourceRun, account: BackupAccount, file: BackupFile, budget: WorkBudget): Turn {
        val folder = tree.folder(file.folderId)
        val serverFolderId = folder?.serverFolderId
            ?: return finish(file, BackupFileStatus.FAILED, folder?.problem?.let { "Папка не создана: $it" } ?: FOLDER_NOT_READY)
        libraryNameProblem(file.name)?.let { return finish(file, BackupFileStatus.SKIPPED, it) }
        if (file.size == 0L) return finish(file, BackupFileStatus.SKIPPED, EMPTY)
        if (file.size != null && file.size > MAX_BYTES) return finish(file, BackupFileStatus.SKIPPED, TOO_LARGE)

        val uploading = file.copy(status = BackupFileStatus.UPLOADING)
        tree.updateFile(uploading)
        val uri = documents.documentUri(run.treeUri, file.documentId)
        val read = try {
            documents.open(uri).use(::digest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Файл источника не читается: ${e.message}")
            return finish(uploading, BackupFileStatus.FAILED, UNREADABLE)
        }
        if (read.size == 0L) return finish(uploading, BackupFileStatus.SKIPPED, EMPTY)
        if (read.size > MAX_BYTES) return finish(uploading, BackupFileStatus.SKIPPED, TOO_LARGE)
        val hashed = uploading.copy(checksum = read.sha256)

        // Путь уже связан с копией на Server: при другом содержимом новая версия ждёт архивирования прежней (5.4).
        if (hashed.serverFileId != null && hashed.syncedChecksum != null) {
            val same = hashed.syncedChecksum.equals(read.sha256, ignoreCase = true)
            return finish(hashed, if (same) BackupFileStatus.SYNCED else BackupFileStatus.CHANGED, null)
        }

        if (accounts.current() != account) {
            tree.updateFile(hashed.copy(status = BackupFileStatus.PENDING))
            return Turn.ACCOUNT_CHANGED
        }
        return when (val result = send(run, hashed, serverFolderId, budget)) {
            is BackupResult.Ok -> uploaded(run, hashed, result.value)
            is BackupResult.Http -> when {
                result.isFolderManaged -> back(hashed, recheck(run))
                result.code == 409 -> occupied(hashed, serverFolderId)
                result.code == 400 -> finish(hashed, BackupFileStatus.SKIPPED, "Сервер отклонил файл: ${result.message}")
                result.code == 413 -> finish(hashed, BackupFileStatus.SKIPPED, TOO_LARGE)
                result.code == 404 -> {
                    if (folder.parentId == null) return back(hashed, recheck(run))
                    tree.forgetServerFolder(folder.id)
                    back(hashed, Turn.STOP)
                }
                else -> back(hashed, Turn.RETRY)
            }
            is BackupResult.Network -> when (result.cause) {
                is LocalSourceException -> finish(hashed, BackupFileStatus.FAILED, UNREADABLE)
                is UploadTooLargeException -> finish(hashed, BackupFileStatus.SKIPPED, TOO_LARGE)
                else -> back(hashed, Turn.RETRY)
            }
        }
    }

    /** Одна немедленная повторная отправка после обрыва: повтор того же имени и содержимого Server не дублирует. */
    private suspend fun send(run: SourceRun, file: BackupFile, folderId: Long, budget: WorkBudget): BackupResult<FileItemDto> {
        val uri = documents.documentUri(run.treeUri, file.documentId)
        suspend fun attempt() = remote.upload(
            folderId, file.name, UploadRequestBody({ documents.open(uri) }, file.mimeType, MAX_BYTES) {}, run.source.sourceUuid
        )
        val first = attempt()
        if (first !is BackupResult.Network || first.cause is LocalSourceException || first.cause is UploadTooLargeException) return first
        currentCoroutineContext().ensureActive()
        if (budget.isExhausted()) return first
        return attempt()
    }

    /**
     * SYNCED — только если Server принял то же содержимое, что было прочитано, и файл после передачи не изменился.
     * Иначе связь с принятой копией сохраняется, а сверка выполняется при следующем обходе.
     */
    private suspend fun uploaded(run: SourceRun, file: BackupFile, dto: FileItemDto): Turn {
        val id = dto.id ?: 0
        if (id <= 0) return back(file, Turn.RETRY)
        val accepted = dto.checksum?.lowercase()
        val after = documents.stat(run.treeUri, file.documentId)
        val stable = after != null && after.size == file.size && after.lastModified == file.lastModified
        return if (accepted == file.checksum && stable) {
            finish(file.copy(serverFileId = id, syncedChecksum = accepted), BackupFileStatus.SYNCED, null)
        } else {
            finish(file.copy(serverFileId = id, syncedChecksum = accepted), BackupFileStatus.FAILED, CHANGED_DURING_UPLOAD)
        }
    }

    /**
     * 409 без FOLDER_MANAGED: имя занято. В активное дерево пишет только резервирование этого источника, поэтому
     * файл с тем же именем — прежняя копия этого пути: связь сохраняется, при другом содержимом строка ждёт 5.4.
     * Имя, занятое папкой, — конфликт без изменений на Server.
     */
    private suspend fun occupied(file: BackupFile, folderId: Long): Turn {
        var page = 0
        while (true) {
            when (val result = remote.files(folderId, page)) {
                is BackupResult.Ok -> {
                    val same = result.value.items.orEmpty().firstOrNull { it.originalFilename == file.name && (it.id ?: 0) > 0 }
                    if (same != null) {
                        val linked = file.copy(serverFileId = same.id, syncedChecksum = same.checksum?.lowercase())
                        val equal = linked.syncedChecksum == file.checksum
                        return finish(linked, if (equal) BackupFileStatus.SYNCED else BackupFileStatus.CHANGED, null)
                    }
                    if (!result.value.hasNext) return finish(file, BackupFileStatus.CONFLICT, FILE_NAME_TAKEN)
                    page++
                }
                else -> return back(file, Turn.RETRY)
            }
        }
    }

    private suspend fun finish(file: BackupFile, status: BackupFileStatus, reason: String?): Turn {
        val failed = status == BackupFileStatus.FAILED || status == BackupFileStatus.CONFLICT || status == BackupFileStatus.SKIPPED
        tree.updateFile(
            file.copy(
                status = status,
                failureReason = reason,
                retryCount = if (failed) file.retryCount + 1 else 0,
                lastFailureAt = if (failed) System.currentTimeMillis() else null
            )
        )
        return Turn.PROGRESS
    }

    /** Строка возвращается в очередь без изменения сведений; ход останавливается с исходом [turn]. */
    private suspend fun back(file: BackupFile, turn: Turn): Turn {
        tree.updateFile(file.copy(status = BackupFileStatus.PENDING))
        return if (turn == Turn.PROGRESS) Turn.RETRY else turn
    }

    private class Read(val size: Long, val sha256: String)

    // Читает не больше лимита + 1 байт: размер больше лимита известен без чтения всего файла.
    private fun digest(input: InputStream): Read {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
            size += read
            if (size > MAX_BYTES) break
        }
        return Read(size, digest.digest().joinToString("") { "%02x".format(it) })
    }
}

package ru.tbcarus.photo_cloud_client.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.library.LibrarySession
import ru.tbcarus.photo_cloud_client.library.librarySessionFlow
import ru.tbcarus.photo_cloud_client.media.ChecksumUtil
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ручная передача файлов Library: одна операция за раз, в памяти процесса. С очередью Camera/A8 не связана.
 * Операция переживает смену вкладок и пересоздание Activity; при гибели процесса не восстанавливается.
 * Смена сервера или пользователя отменяет операцию: её файлы относятся к прежней сессии.
 */
@Singleton
class TransferManager internal constructor(
    private val remote: TransferRemote,
    private val documents: LocalDocuments,
    private val tempDir: File,
    sessions: Flow<LibrarySession>,
    dispatcher: CoroutineDispatcher
) {

    @Inject
    constructor(
        remote: TransferRemote,
        documents: LocalDocuments,
        @ApplicationContext context: Context,
        baseUrlProvider: BaseUrlProvider,
        tokenStorage: TokenStorage
    ) : this(
        remote,
        documents,
        File(context.cacheDir, TEMP_DIR),
        librarySessionFlow(baseUrlProvider, tokenStorage),
        Dispatchers.IO
    )

    companion object {
        private const val TAG = "TransferManager"
        private const val TEMP_DIR = "transfer"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 150L

        // Равен storage.max-file-size-bytes Server: API лимит не сообщает, а отказ приходит только после передачи.
        const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024

        internal const val AUTH_REQUIRED = "Требуется вход"
        internal const val NO_CONNECTION = "Нет связи с сервером"
        internal const val DESTINATION_GONE = "Папка назначения не найдена"
        internal const val EMPTY_FILE = "Пустой файл: Server пока не принимает такие файлы"
        internal const val TOO_LARGE = "Файл больше 100 МиБ"
        internal const val RESERVED_NAME = "Имя Camera зарезервировано в корне"
        internal const val NAME_TAKEN_BY_FOLDER = "Имя занято папкой"
        internal const val NAME_TAKEN_BY_FILE = "Имя занято файлом"
        internal const val CONTENT_CONFLICT = "На сервере есть файл с этим именем и другим содержимым"
        internal const val SERVER_CONFLICT = "Имя уже занято на сервере"
        internal const val NO_NAME = "Имя файла недоступно"
        internal const val CORRUPTED = "Скачанные данные не совпадают с файлом на сервере"
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private var session: LibrarySession? = null
    private var job: Job? = null
    private var operationId = 0L
    private var pendingDownload: PendingDownload? = null

    private class PendingDownload(val id: Long, val temp: File, val name: String)

    init {
        // Временные файлы прошлого процесса не принадлежат ни одной операции.
        tempDir.listFiles()?.forEach { it.delete() }
        scope.launch { sessions.collect(::onSession) }
    }

    /** Скачивает файл во временный файл и проверяет его; место сохранения выбирается после проверки. */
    fun download(file: FileItemDto): Boolean {
        val fileId = file.id ?: return false
        val name = file.originalFilename?.takeIf { it.isNotBlank() } ?: "file-$fileId"
        return launchOperation(TransferKind.DOWNLOAD) { id, _ -> runDownload(id, fileId, name, file.size, file.checksum) }
    }

    /** Место сохранения выбрано; uri = null — пользователь отказался от сохранения. */
    fun saveDownload(id: Long, uri: Uri?) {
        synchronized(lock) {
            val pending = pendingDownload?.takeIf { it.id == id && id == operationId } ?: return
            pendingDownload = null
            if (uri == null) {
                pending.temp.delete()
                _state.value = TransferState.Finished(id, TransferKind.DOWNLOAD, cancelled = true)
                return
            }
            _state.value = TransferState.Running(id, TransferKind.DOWNLOAD, 1, 1, pending.name, 0, pending.temp.length())
            job = scope.launch { publish(id, copyToDocument(id, pending, uri)) }.also { onCancelled(it, id, TransferKind.DOWNLOAD) }
        }
    }

    fun uploadFiles(destination: UploadDestination, uris: List<Uri>): Boolean {
        if (uris.isEmpty()) return false
        return launchOperation(TransferKind.UPLOAD) { id, session ->
            val files = uris.map(documents::describe)
            Upload(id, destination, session).run(LocalDir(name = null, files = files), createRoot = false)
        }
    }

    fun uploadFolder(destination: UploadDestination, treeUri: Uri): Boolean =
        launchOperation(TransferKind.UPLOAD) { id, session ->
            Upload(id, destination, session).run(documents.walk(treeUri), createRoot = true)
        }

    fun cancel() {
        synchronized(lock) {
            when (val current = _state.value) {
                is TransferState.AwaitingDestination -> {
                    discardPendingDownload()
                    _state.value = TransferState.Finished(current.id, TransferKind.DOWNLOAD, cancelled = true)
                }
                is TransferState.Running -> job?.cancel()
                else -> Unit
            }
        }
    }

    /** Убирает итог завершённой операции. */
    fun dismiss() {
        synchronized(lock) {
            if (_state.value is TransferState.Finished) _state.value = TransferState.Idle
        }
    }

    private fun onSession(newSession: LibrarySession) {
        synchronized(lock) {
            val previous = session
            session = newSession
            if (previous == null || previous == newSession) return
            job?.cancel()
            job = null
            discardPendingDownload()
            // Поздний итог отменённой операции не публикуется: номер операции уже другой.
            operationId++
            _state.value = TransferState.Idle
        }
    }

    private fun launchOperation(
        kind: TransferKind,
        block: suspend (Long, LibrarySession) -> TransferState.Finished?
    ): Boolean = synchronized(lock) {
        if (_state.value.isActive) return false
        val current = session?.takeIf { it.user != null && it.baseUrl.isNotBlank() } ?: return false
        val id = ++operationId
        _state.value = TransferState.Running(id, kind, 0, 0, null, 0, null)
        job = scope.launch {
            val finished = try {
                block(id, current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Необработанное исключение в scope завершило бы процесс приложения.
                Log.e(TAG, "Ручная передача прервана ошибкой", e)
                TransferState.Finished(id, kind, stopReason = "Непредвиденная ошибка: ${e.message}")
            }
            publish(id, finished)
        }.also { onCancelled(it, id, kind) }
        true
    }

    // Отмена до первой точки приостановки не доходит до обработчиков операции: итог публикуется здесь.
    private fun onCancelled(job: Job, id: Long, kind: TransferKind) {
        job.invokeOnCompletion { cause ->
            if (cause !is CancellationException) return@invokeOnCompletion
            synchronized(lock) {
                val current = _state.value
                if (id == operationId && current is TransferState.Running && current.id == id) {
                    _state.value = TransferState.Finished(id, kind, cancelled = true)
                }
            }
        }
    }

    private fun publish(id: Long, finished: TransferState.Finished?) {
        if (finished == null) return
        synchronized(lock) {
            if (id == operationId) _state.value = finished
        }
    }

    private fun update(id: Long, running: TransferState.Running) {
        synchronized(lock) {
            if (id == operationId && _state.value is TransferState.Running) _state.value = running
        }
    }

    private fun discardPendingDownload() {
        pendingDownload?.temp?.delete()
        pendingDownload = null
    }

    private suspend fun runDownload(id: Long, fileId: Long, name: String, size: Long?, checksum: String?): TransferState.Finished? {
        tempDir.mkdirs()
        val temp = File(tempDir, "download-$id.tmp")
        var kept = false
        val progress = Throttle()
        try {
            update(id, TransferState.Running(id, TransferKind.DOWNLOAD, 1, 1, name, 0, size))
            val reason = when (val result = remote.download(fileId, temp) { bytes ->
                if (progress.ready()) update(id, TransferState.Running(id, TransferKind.DOWNLOAD, 1, 1, name, bytes, size))
            }) {
                is RemoteResult.Success -> {
                    val downloaded = result.value
                    val intact = (size == null || downloaded.size == size) &&
                        (checksum == null || downloaded.sha256.equals(checksum, ignoreCase = true))
                    if (!intact) {
                        CORRUPTED
                    } else {
                        synchronized(lock) {
                            if (id != operationId) return null
                            pendingDownload = PendingDownload(id, temp, name)
                            _state.value = TransferState.AwaitingDestination(id, name)
                        }
                        kept = true
                        return null
                    }
                }
                is RemoteResult.HttpError -> when (result.code) {
                    401, 403 -> AUTH_REQUIRED
                    404 -> "Файл не найден на сервере"
                    else -> "Ошибка сервера: ${result.message}"
                }
                is RemoteResult.NetworkError -> NO_CONNECTION
            }
            return TransferState.Finished(id, TransferKind.DOWNLOAD, issues = listOf(TransferIssue(name, reason)))
        } catch (e: CancellationException) {
            publish(id, TransferState.Finished(id, TransferKind.DOWNLOAD, cancelled = true))
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Скачивание не выполнено", e)
            return TransferState.Finished(id, TransferKind.DOWNLOAD, issues = listOf(TransferIssue(name, "Ошибка записи: ${e.message}")))
        } finally {
            if (!kept) temp.delete()
        }
    }

    private suspend fun copyToDocument(id: Long, pending: PendingDownload, uri: Uri): TransferState.Finished {
        val total = pending.temp.length()
        val progress = Throttle()
        var completed = false
        try {
            documents.openOutput(uri).use { output ->
                pending.temp.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (progress.ready()) {
                            update(id, TransferState.Running(id, TransferKind.DOWNLOAD, 1, 1, pending.name, copied, total))
                        }
                    }
                }
            }
            completed = true
            return TransferState.Finished(id, TransferKind.DOWNLOAD, savedName = pending.name)
        } catch (e: CancellationException) {
            publish(id, TransferState.Finished(id, TransferKind.DOWNLOAD, cancelled = true))
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Скачанный файл не сохранён", e)
            return TransferState.Finished(
                id, TransferKind.DOWNLOAD,
                issues = listOf(TransferIssue(pending.name, "Не удалось сохранить файл: ${e.message}"))
            )
        } finally {
            pending.temp.delete()
            // Недописанный документ бесполезен: оставлять его под именем файла нельзя.
            if (!completed) documents.delete(uri)
        }
    }

    /** Дальнейшая передача невозможна: нет входа, связи или папки назначения. */
    private class StopTransfer(message: String) : Exception(message)

    /** Прямые потомки папки Server; ключи — имена без учёта регистра, как сравнивает Server. */
    private class Listing(
        val folders: MutableMap<String, FolderDto> = HashMap(),
        val files: MutableMap<String, FileItemDto> = HashMap()
    )

    /** Папка Server для локальной папки либо причина пропустить её вместе с содержимым. */
    private sealed interface FolderTarget {
        class Ready(val folderId: Long, val listing: Listing) : FolderTarget
        class Skip(val reason: String) : FolderTarget
    }

    private fun key(name: String) = name.lowercase(Locale.ROOT)

    private inner class Upload(
        private val id: Long,
        private val destination: UploadDestination,
        private val session: LibrarySession
    ) {
        private var current = 0
        private var total = 0
        private var bytesDone = 0L
        private var bytesTotal: Long? = null
        private var uploaded = 0
        private var existing = 0
        private val issues = ArrayList<TransferIssue>()
        private val progress = Throttle()

        suspend fun run(tree: LocalDir, createRoot: Boolean): TransferState.Finished {
            total = tree.fileCount
            bytesTotal = allFiles(tree).map { it.size }.takeIf { sizes -> sizes.all { it != null } }?.sumOf { it ?: 0 }
            report(null)
            var stopReason: String? = null
            try {
                val listing = loadListing(destination.folderId) ?: throw StopTransfer(DESTINATION_GONE)
                if (createRoot) {
                    uploadDir(tree, destination.folderId, listing, destination.isRoot, "")
                } else {
                    uploadFiles(tree.files, destination.folderId, listing, destination.isRoot, "")
                }
            } catch (e: StopTransfer) {
                stopReason = e.message
            } catch (e: CancellationException) {
                publish(id, finished(cancelled = true, stopReason = null))
                throw e
            }
            return finished(cancelled = false, stopReason = stopReason)
        }

        private fun finished(cancelled: Boolean, stopReason: String?) = TransferState.Finished(
            id = id,
            kind = TransferKind.UPLOAD,
            uploaded = uploaded,
            existing = existing,
            issues = issues.toList(),
            cancelled = cancelled,
            stopReason = stopReason,
            destinationFolderId = destination.folderId,
            session = session
        )

        private fun allFiles(dir: LocalDir): List<LocalFile> = dir.files + dir.dirs.flatMap(::allFiles)

        private fun report(name: String?, fileBytes: Long = 0) {
            update(id, TransferState.Running(id, TransferKind.UPLOAD, current, total, name, bytesDone + fileBytes, bytesTotal))
        }

        private suspend fun uploadDir(dir: LocalDir, parentId: Long, parentListing: Listing, parentIsRoot: Boolean, prefix: String) {
            currentCoroutineContext().ensureActive()
            val path = prefix + (dir.name ?: "?") + "/"
            val name = dir.name
            val problem = dir.problem ?: libraryNameProblem(name)
                ?: RESERVED_NAME.takeIf { parentIsRoot && isReservedRootName(name!!) }
            if (problem != null || name == null) return skipTree(dir, path, problem ?: NO_NAME)

            val existingFolder = parentListing.folders[key(name)]
            val target = when {
                // Существующая папка объединяется с выбранной; её файлы проходят обычную проверку конфликтов.
                existingFolder != null -> openFolder(existingFolder)
                parentListing.files.containsKey(key(name)) -> FolderTarget.Skip(NAME_TAKEN_BY_FILE)
                else -> createFolder(parentId, name, parentListing)
            }
            when (target) {
                is FolderTarget.Skip -> skipTree(dir, path, target.reason)
                is FolderTarget.Ready -> {
                    uploadFiles(dir.files, target.folderId, target.listing, isRoot = false, prefix = path)
                    for (child in dir.dirs) uploadDir(child, target.folderId, target.listing, parentIsRoot = false, prefix = path)
                }
            }
        }

        private suspend fun openFolder(folder: FolderDto): FolderTarget =
            loadListing(folder.id)?.let { FolderTarget.Ready(folder.id, it) } ?: FolderTarget.Skip(DESTINATION_GONE)

        private suspend fun createFolder(parentId: Long, name: String, parentListing: Listing): FolderTarget =
            when (val result = retrying { remote.createFolder(parentId, name) }) {
                is RemoteResult.Success -> {
                    parentListing.folders[key(name)] = result.value
                    FolderTarget.Ready(result.value.id, Listing())
                }
                is RemoteResult.NetworkError -> throw StopTransfer(NO_CONNECTION)
                is RemoteResult.HttpError -> {
                    requireAuthorized(result)
                    when (result.code) {
                        // Папку мог создать первый запрос, ответ которого потерян, или другой клиент.
                        409 -> reloadFolders(parentId)?.get(key(name))
                            ?.also { parentListing.folders[key(name)] = it }
                            ?.let { openFolder(it) }
                            ?: FolderTarget.Skip(SERVER_CONFLICT)
                        400 -> FolderTarget.Skip("Сервер отклонил имя: ${result.message}")
                        404 -> FolderTarget.Skip(DESTINATION_GONE)
                        else -> FolderTarget.Skip("Ошибка сервера: ${result.message}")
                    }
                }
            }

        private fun skipTree(dir: LocalDir, path: String, reason: String) {
            val files = allFiles(dir)
            val suffix = if (files.isEmpty()) "" else " (файлов: ${files.size})"
            issues += TransferIssue(path, reason + suffix)
            current += files.size
            bytesDone += files.sumOf { it.size ?: 0 }
            report(null)
        }

        private suspend fun uploadFiles(files: List<LocalFile>, folderId: Long, listing: Listing, isRoot: Boolean, prefix: String) {
            var folderGone = false
            for (file in files) {
                currentCoroutineContext().ensureActive()
                current++
                report(file.name)
                val reason = when (val check = precheck(file, listing, isRoot, folderGone)) {
                    Precheck.Send -> send(file, folderId, listing)
                    Precheck.Existing -> {
                        existing++
                        null
                    }
                    is Precheck.Skip -> check.reason
                }
                if (reason == DESTINATION_GONE) folderGone = true
                if (reason != null) issues += TransferIssue(prefix + (file.name ?: "?"), reason)
                bytesDone += file.size ?: 0
                report(file.name)
            }
        }

        private fun precheck(file: LocalFile, listing: Listing, isRoot: Boolean, folderGone: Boolean): Precheck {
            file.problem?.let { return Precheck.Skip(it) }
            libraryNameProblem(file.name)?.let { return Precheck.Skip(it) }
            val name = file.name!!
            val size = file.size
            val remoteFile = listing.files[key(name)]
            val reason = when {
                isRoot && isReservedRootName(name) -> RESERVED_NAME
                size == 0L -> EMPTY_FILE
                size != null && size > MAX_UPLOAD_BYTES -> TOO_LARGE
                folderGone -> DESTINATION_GONE
                listing.folders.containsKey(key(name)) -> NAME_TAKEN_BY_FOLDER
                remoteFile != null -> when (sameContent(file, remoteFile)) {
                    true -> return Precheck.Existing
                    false -> CONTENT_CONFLICT
                    null -> UNREADABLE
                }
                else -> return Precheck.Send
            }
            return Precheck.Skip(reason)
        }

        private fun sameContent(file: LocalFile, remoteFile: FileItemDto): Boolean? {
            if (file.size != null && remoteFile.size != null && file.size != remoteFile.size) return false
            return try {
                documents.open(file.uri).use(ChecksumUtil::sha256).equals(remoteFile.checksum, ignoreCase = true)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось прочитать файл для сравнения: ${e.message}")
                null
            }
        }

        /** Отправляет файл; null — Server принял его, иначе причина. */
        private suspend fun send(file: LocalFile, folderId: Long, listing: Listing): String? {
            val name = file.name!!
            val result = retrying(retryable = { it.cause !is LocalSourceException && it.cause !is UploadTooLargeException }) {
                val body = UploadRequestBody({ documents.open(file.uri) }, file.mimeType, MAX_UPLOAD_BYTES) { written ->
                    if (progress.ready()) report(name, written)
                }
                remote.upload(folderId, name, body)
            }
            return when (result) {
                is RemoteResult.Success -> {
                    uploaded++
                    listing.files[key(name)] = result.value
                    null
                }
                is RemoteResult.NetworkError -> when (result.cause) {
                    is LocalSourceException -> UNREADABLE
                    is UploadTooLargeException -> TOO_LARGE
                    else -> throw StopTransfer(NO_CONNECTION)
                }
                is RemoteResult.HttpError -> {
                    requireAuthorized(result)
                    when (result.code) {
                        404 -> DESTINATION_GONE
                        409 -> SERVER_CONFLICT
                        413 -> TOO_LARGE
                        400 -> "Сервер отклонил файл: ${result.message}"
                        else -> "Ошибка сервера: ${result.message}"
                    }
                }
            }
        }

        /** Содержимое папки Server; null — папка удалена. */
        private suspend fun loadListing(folderId: Long): Listing? {
            val folders = reloadFolders(folderId) ?: return null
            val files = HashMap<String, FileItemDto>()
            var page = 0
            while (true) {
                when (val result = retrying { remote.files(folderId, page) }) {
                    is RemoteResult.Success -> {
                        result.value.items.orEmpty().forEach { file ->
                            file.originalFilename?.let { files[key(it)] = file }
                        }
                        if (!result.value.hasNext) break
                        page++
                    }
                    is RemoteResult.NetworkError -> throw StopTransfer(NO_CONNECTION)
                    is RemoteResult.HttpError -> {
                        requireAuthorized(result)
                        if (result.code == 404) return null
                        throw StopTransfer("Ошибка сервера: ${result.message}")
                    }
                }
            }
            return Listing(folders, files)
        }

        private suspend fun reloadFolders(folderId: Long): MutableMap<String, FolderDto>? =
            when (val result = retrying { remote.children(folderId) }) {
                is RemoteResult.Success -> result.value.associateByTo(HashMap()) { key(it.name) }
                is RemoteResult.NetworkError -> throw StopTransfer(NO_CONNECTION)
                is RemoteResult.HttpError -> {
                    requireAuthorized(result)
                    if (result.code == 404) null else throw StopTransfer("Ошибка сервера: ${result.message}")
                }
            }

        private fun requireAuthorized(result: RemoteResult.HttpError) {
            if (result.code == 401 || result.code == 403) throw StopTransfer(AUTH_REQUIRED)
        }
    }

    /** Одна повторная попытка при сетевой ошибке; ответ Server не повторяется. */
    private suspend fun <T> retrying(
        retryable: (RemoteResult.NetworkError) -> Boolean = { true },
        block: suspend () -> RemoteResult<T>
    ): RemoteResult<T> {
        val first = block()
        if (first !is RemoteResult.NetworkError || !retryable(first)) return first
        currentCoroutineContext().ensureActive()
        return block()
    }

    /** Ограничивает частоту обновления прогресса: тело файла пишется блоками по 64 КиБ. */
    private class Throttle {
        private var last = 0L

        fun ready(): Boolean {
            val now = System.nanoTime() / 1_000_000
            if (now - last < PROGRESS_INTERVAL_MS) return false
            last = now
            return true
        }
    }
}

/** Решение до отправки файла. */
private sealed interface Precheck {
    data object Send : Precheck
    /** На Server уже есть файл с тем же именем и содержимым. */
    data object Existing : Precheck
    data class Skip(val reason: String) : Precheck
}

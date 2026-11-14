package ru.tbcarus.photo_cloud_client.transfer

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.backup.BackupAccount
import ru.tbcarus.photo_cloud_client.backup.BackupAccountProvider
import ru.tbcarus.photo_cloud_client.media.ResumableResult
import ru.tbcarus.photo_cloud_client.media.ResumableUpload
import ru.tbcarus.photo_cloud_client.media.SyncConfig
import ru.tbcarus.photo_cloud_client.media.UploadSizeClass
import ru.tbcarus.photo_cloud_client.media.WorkBudget
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** FIFO ручных файлов. Room хранит задания, upload_sessions — протокол больших файлов. */
@Singleton
class ManualUploadQueue @Inject constructor(
    private val dao: ManualUploadDao,
    private val accounts: BackupAccountProvider,
    private val documents: LocalDocuments,
    private val remote: TransferRemote,
    private val resumable: ResumableUpload,
    private val scheduler: ManualUploadScheduler
) {
    private val mutex = Mutex()
    private val enqueueMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueueInBackground(destination: UploadDestination, uris: List<Uri>) {
        scope.launch {
            try { enqueue(destination, uris) }
            catch (e: Exception) { Log.e("ManualUploadQueue", "Не удалось сохранить ручные задания", e) }
        }
    }

    fun observe(): Flow<List<ManualUploadJob>> = combine(dao.observeAll(), accounts.observe()) { jobs, account ->
        jobs.filter { it.serverUrl == account?.serverUrl && it.accountId == account.accountId }
    }

    /** Разрешения закрепляются до записи заданий; каждый файл затем живёт независимо от экрана. */
    suspend fun enqueue(destination: UploadDestination, uris: List<Uri>) = withContext(Dispatchers.IO) {
        enqueueMutex.withLock { persist(destination, uris) }
    }

    private suspend fun persist(destination: UploadDestination, uris: List<Uri>) {
        val account = accounts.current() ?: return
        val firstOrder = maxOf(System.currentTimeMillis(), (dao.latestCreatedAt() ?: 0L) + 1L)
        val jobs = uris.mapIndexed { index, uri ->
            val permission = try { documents.persistRead(uri); null } catch (e: Exception) { "Нет постоянного доступа к файлу" }
            val file = documents.describe(uri)
            ManualUploadJob(
                id = UUID.randomUUID().toString(), serverUrl = account.serverUrl, accountId = account.accountId,
                uri = uri.toString(), name = file.name ?: uri.lastPathSegment ?: "?", size = file.size,
                mimeType = file.mimeType, lastModified = documents.lastModified(uri),
                folderId = destination.folderId, isRoot = destination.isRoot,
                status = if (permission == null && file.name != null) ManualUploadJob.QUEUED else ManualUploadJob.FAILED,
                error = permission ?: if (file.name == null) TransferManager.NO_NAME else null,
                createdAt = firstOrder + index
            )
        }
        dao.insert(jobs)
        scheduler.enqueue()
    }

    suspend fun cancel(id: String) {
        if (dao.cancelUnstarted(id) > 0) return
        if (dao.requestCancel(id) > 0) scheduler.enqueue()
    }

    suspend fun retry(id: String) {
        if (dao.retry(id) > 0) scheduler.enqueue()
    }

    suspend fun dismiss(id: String) = enqueueMutex.withLock {
        val job = dao.get(id) ?: return@withLock
        if (dao.dismiss(id) == 0) return@withLock
        if (dao.countUri(job.uri) == 0) {
            try { documents.releaseRead(Uri.parse(job.uri)) } catch (_: Exception) { }
        }
    }

    /** true — временная проблема или исчерпан бюджет; WorkManager повторит с backoff. */
    suspend fun run(budget: WorkBudget): Boolean = mutex.withLock {
        val account = accounts.current() ?: return@withLock false
        coroutineScope {
            val parent = currentCoroutineContext()
            val watcher = launch {
                accounts.observe().collect { if (it != account) parent.cancel() }
            }
            try {
                if (!resumable.cleanupManual(account, budget)) return@coroutineScope true
                var processed = 0
                while (!budget.isExhausted()) {
                    val job = dao.next(account.serverUrl, account.accountId) ?: return@coroutineScope false
                    if (accounts.current() != account) return@coroutineScope false
                    if (process(job, account, budget)) return@coroutineScope true
                    if (++processed >= 20) return@coroutineScope dao.next(account.serverUrl, account.accountId) != null
                }
                dao.next(account.serverUrl, account.accountId) != null
            } finally {
                watcher.cancel()
            }
        }
    }

    private suspend fun process(job: ManualUploadJob, account: BackupAccount, budget: WorkBudget): Boolean {
        if (job.status == ManualUploadJob.CANCEL_PENDING) return cancelPending(job)
        if (job.status == ManualUploadJob.RETRY_PENDING) {
            val requestId = job.sessionRequestId
            if (requestId != null) {
                when (resumable.cancelManual(requestId)) {
                    is ResumableResult.Complete -> { dao.setStatus(job.id, ManualUploadJob.COMPLETED); return false }
                    ResumableResult.Progress -> Unit
                    else -> return true
                }
            }
            val uri = Uri.parse(job.uri)
            if (!documents.hasPersistedRead(uri)) return fail(job, "Нет постоянного доступа к файлу")
            val current = documents.describe(uri)
            if (current.problem != null || current.name == null) return fail(job, "Исходный файл недоступен")
            dao.retryReady(job.id, current.name, current.size, current.mimeType, documents.lastModified(uri))
            return false
        }
        if (job.status == ManualUploadJob.UNCERTAIN) return reconcileMultipart(job, cancel = false)
        val sizeClass = job.size?.let(SyncConfig::uploadSizeClass) ?: UploadSizeClass.UNKNOWN
        when (sizeClass) {
            UploadSizeClass.UNKNOWN -> return fail(job, "Размер файла неизвестен (SIZE_UNKNOWN)")
            UploadSizeClass.TOO_LARGE -> return fail(job, "Файл больше 20 ГиБ (FILE_TOO_LARGE)")
            else -> Unit
        }
        libraryNameProblem(job.name)?.let { return fail(job, it) }
        if (job.isRoot && isReservedRootName(job.name)) return fail(job, TransferManager.RESERVED_NAME)
        val uri = Uri.parse(job.uri)
        if (!documents.hasPersistedRead(uri)) return fail(job, "Нет постоянного доступа к файлу")
        val current = documents.describe(uri)
        if (current.problem != null) return fail(job, "Исходный файл недоступен или удалён")
        if (current.size != job.size || current.name != job.name ||
            (job.lastModified != null && documents.lastModified(uri)?.let { it != job.lastModified } == true)) {
            return fail(job, "Исходный файл изменился")
        }
        if (job.checksum == null) {
            if (dao.markVerifying(job.id) == 0) return false
            val checksum = try { hash(uri, budget) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { return fail(job, "Не удалось прочитать исходный файл") }
            if (checksum == null) {
                dao.resetVerifying(job.id)
                return true
            }
            if (dao.setChecksum(job.id, checksum) == 0) return false
        }
        val ready = dao.get(job.id) ?: return false
        if (ready.status == ManualUploadJob.CANCEL_PENDING) return cancelPending(ready)
        if (ready.sessionRequestId == null) {
            val matches = findByName(ready) ?: return true
            if (dao.get(ready.id)?.status == ManualUploadJob.FAILED) return false
            val existing = matches.firstOrNull()
            if (existing != null) {
                if (existing.checksum.equals(ready.checksum, ignoreCase = true) && existing.size == ready.size) {
                    dao.setStatus(ready.id, ManualUploadJob.COMPLETED)
                    return false
                }
                return fail(ready, TransferManager.CONTENT_CONFLICT)
            }
        }
        if (ready.status == ManualUploadJob.QUEUED && dao.markRunning(ready.id) == 0) return false
        if (ready.status != ManualUploadJob.QUEUED && ready.status != ManualUploadJob.RUNNING) return false
        return if (sizeClass == UploadSizeClass.MULTIPART) multipart(ready) else large(ready, budget)
    }

    private suspend fun hash(uri: Uri, budget: WorkBudget): String? = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        documents.open(uri).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                if (budget.isExhausted()) return@withContext null
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun large(job: ManualUploadJob, budget: WorkBudget): Boolean {
        var current = dao.get(job.id) ?: return false
        if (current.sessionRequestId == null) {
            val id = UUID.randomUUID().toString()
            if (dao.setSessionRequestId(job.id, id) == 0) return false
            current = current.copy(sessionRequestId = id)
        }
        val result = resumable.manual(current, budget,
            cancelled = { dao.get(job.id)?.status == ManualUploadJob.CANCEL_PENDING },
            onOffset = { dao.setOffset(job.id, it) })
        return when (result) {
            is ResumableResult.Complete -> {
                if (result.file.checksum.equals(job.checksum, ignoreCase = true) && (result.file.id ?: 0L) > 0) {
                    dao.setStatus(job.id, ManualUploadJob.COMPLETED)
                    resumable.forget(current.sessionRequestId!!)
                    false
                } else fail(job, "Контрольная сумма ответа Server не совпадает")
            }
            ResumableResult.Progress -> {
                if (dao.get(job.id)?.status == ManualUploadJob.CANCEL_PENDING) cancelPending(current) else true
            }
            ResumableResult.Retry -> {
                if (resumable.manualRecord(current.sessionRequestId!!) == null) dao.clearSessionRequestId(job.id)
                true
            }
            ResumableResult.SourceChanged -> fail(job, "Исходный файл изменился или недоступен")
            ResumableResult.ChecksumMismatch -> fail(job, "Контрольная сумма файла изменилась")
            is ResumableResult.Failed -> fail(job, "Server отклонил файл: ${result.reason}")
            ResumableResult.ManagedTree -> fail(job, "Папка управляется автоматическим резервированием")
        }
    }

    private suspend fun multipart(job: ManualUploadJob): Boolean {
        val body = UploadRequestBody({ documents.open(Uri.parse(job.uri)) }, job.mimeType,
            SyncConfig.MULTIPART_MAX_BYTES) { }
        val result = remote.upload(job.folderId, job.name, body)
        return when (result) {
            is RemoteResult.Success -> {
                if ((result.value.id ?: 0L) > 0 && result.value.checksum.equals(job.checksum, ignoreCase = true)) {
                    dao.setStatus(job.id, ManualUploadJob.COMPLETED)
                    false
                } else fail(job, "Некорректная квитанция Server")
            }
            is RemoteResult.NetworkError -> {
                if (result.cause is LocalSourceException || result.cause is UploadTooLargeException) {
                    return fail(job, if (result.cause is UploadTooLargeException) "Файл превысил лимит 50 МиБ" else "Не удалось прочитать исходный файл")
                }
                // Ответ мог потеряться после публикации: повтор только после сверки папки по имени.
                dao.markUncertain(job.id)
                true
            }
            is RemoteResult.HttpError -> when (result.code) {
                400, 409, 413 -> fail(job, "Server отклонил файл: ${result.message}")
                401, 403, 404 -> fail(job, "Server отклонил файл: ${result.message}")
                else -> { dao.markUncertain(job.id); true }
            }
        }
    }

    /** null с внешнего метода означает сетевую ошибку; внутренний nullable — файл отсутствует. */
    private suspend fun findByName(job: ManualUploadJob): List<ru.tbcarus.photo_cloud_client.api.models.FileItemDto>? {
        when (val folders = remote.children(job.folderId)) {
            is RemoteResult.NetworkError -> return null
            is RemoteResult.HttpError -> {
                if (folders.code == 404) { fail(job, TransferManager.DESTINATION_GONE); return emptyList() }
                return null
            }
            is RemoteResult.Success -> {
                if (folders.value.any { it.name.equals(job.name, ignoreCase = true) }) {
                    fail(job, TransferManager.NAME_TAKEN_BY_FOLDER)
                    return emptyList()
                }
            }
        }
        var page = 0
        val found = ArrayList<ru.tbcarus.photo_cloud_client.api.models.FileItemDto>()
        while (true) {
            when (val result = remote.files(job.folderId, page++)) {
                is RemoteResult.NetworkError -> return null
                is RemoteResult.HttpError -> return null
                is RemoteResult.Success -> {
                    found += result.value.items.orEmpty().filter {
                        it.originalFilename?.lowercase(Locale.ROOT) == job.name.lowercase(Locale.ROOT)
                    }
                    if (!result.value.hasNext) return found
                }
            }
        }
    }

    private suspend fun reconcileMultipart(job: ManualUploadJob, cancel: Boolean): Boolean {
        val matches = findByName(job) ?: return true
        if (matches.any { it.checksum.equals(job.checksum, ignoreCase = true) && it.size == job.size }) {
            dao.setStatus(job.id, ManualUploadJob.COMPLETED)
            return false
        }
        if (cancel) {
            dao.setStatus(job.id, ManualUploadJob.CANCELLED)
            return false
        }
        if (matches.isNotEmpty()) return fail(job, TransferManager.CONTENT_CONFLICT)
        dao.resetForRetry(job.id)
        return true
    }

    private suspend fun cancelPending(job: ManualUploadJob): Boolean {
        val requestId = job.sessionRequestId
        if (requestId == null) return reconcileMultipart(job, cancel = true)
        return when (val result = resumable.cancelManual(requestId)) {
            is ResumableResult.Complete -> {
                dao.setStatus(job.id, ManualUploadJob.COMPLETED)
                false
            }
            ResumableResult.Progress -> { dao.setStatus(job.id, ManualUploadJob.CANCELLED); false }
            else -> true
        }
    }

    private suspend fun fail(job: ManualUploadJob, reason: String): Boolean {
        job.sessionRequestId?.let { resumable.requestManualCancel(it) }
        dao.failIfActive(job.id, reason)
        return false
    }
}

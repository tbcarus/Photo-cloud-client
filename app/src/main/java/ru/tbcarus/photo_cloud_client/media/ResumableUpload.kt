package ru.tbcarus.photo_cloud_client.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import ru.tbcarus.photo_cloud_client.api.CreateUploadSessionRequest
import ru.tbcarus.photo_cloud_client.api.UploadSessionDto
import ru.tbcarus.photo_cloud_client.api.UploadSessionService
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.backup.BackupAccount
import ru.tbcarus.photo_cloud_client.backup.BackupAccountProvider
import ru.tbcarus.photo_cloud_client.core.network.ApiErrorParser
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

sealed interface ResumableResult {
    data class Complete(val file: FileItemDto) : ResumableResult
    data object Progress : ResumableResult
    data object Retry : ResumableResult
    data object ChecksumMismatch : ResumableResult
    data object SourceChanged : ResumableResult
    data class Failed(val reason: FailureReason) : ResumableResult
}

/** Устойчивый протокол; источник байтов передан отдельным адаптером Camera. */
@Singleton
class ResumableUpload @Inject constructor(
    private val sessions: UploadSessionDao,
    private val mediaFiles: MediaFileDao,
    private val accounts: BackupAccountProvider,
    private val api: ApiServiceFactory,
    private val source: CameraUploadSource,
    private val wifi: WifiUploadNetwork
) {
    private suspend fun service(account: BackupAccount): UploadSessionService {
        if (accounts.current() != account) throw IOException("Сессия или Server изменились")
        val network = wifi.current() ?: throw IOException("Для большой Camera-загрузки требуется Wi-Fi")
        return api.authUploadSessionService(account.serverUrl, network)
    }

    /** Сохраняет записи удалённых/изменённых файлов до подтверждённой отмены на том же Server и аккаунте. */
    suspend fun cleanupOrphans(budget: WorkBudget = WorkBudget.UNLIMITED): Boolean {
        val account = accounts.current() ?: return false
        for (record in sessions.forAccount(account.serverUrl, account.accountId)) {
            if (budget.isExhausted()) return false
            val mediaId = record.sourceId.toLongOrNull()
            val file = mediaId?.let { mediaFiles.getById(it) }
            val state = source.state(record)
            val stale = file == null || file.status == MediaFileStatus.SYNCED || file.uri != record.uri ||
                file.displayName.ifBlank { "file_${file.mediaStoreId}" } != record.originalFilename ||
                file.size != record.size || file.lastModified != record.lastModified ||
                file.checksum?.lowercase() != record.checksum ||
                state is CameraSourceState.Changed || state is CameraSourceState.Missing
            if (stale || record.cancelPending) {
                sessions.requestCancel(record.clientRequestId)
                if (!cancel(record, account)) return false
            }
        }
        return true
    }

    suspend fun camera(file: MediaFile, folderId: Long, budget: WorkBudget, isPaused: suspend () -> Boolean): ResumableResult {
        val checksum = file.checksum?.lowercase() ?: return ResumableResult.SourceChanged
        val account = accounts.current() ?: return ResumableResult.Retry
        val sourceId = file.mediaStoreId.toString()
        val originalMode = source.originalMode()
        val records = sessions.forSource(account.serverUrl, account.accountId, "CAMERA", sourceId)
        for (old in records) {
            if (old.cancelPending || old.uri != file.uri || old.folderId != folderId ||
                old.originalFilename != file.displayName.ifBlank { "file_${file.mediaStoreId}" } ||
                old.size != file.size || old.lastModified != file.lastModified ||
                old.checksum != checksum || old.originalMode != originalMode) {
                sessions.requestCancel(old.clientRequestId)
                if (!cancel(old, account)) return ResumableResult.Retry
            }
        }
        var record = records.firstOrNull { !it.cancelPending && it.uri == file.uri && it.folderId == folderId &&
            it.originalFilename == file.displayName.ifBlank { "file_${file.mediaStoreId}" } &&
            it.size == file.size && it.lastModified == file.lastModified && it.checksum == checksum &&
            it.originalMode == originalMode }
        if (record == null) {
            record = UploadSessionRecord(
                clientRequestId = UUID.randomUUID().toString(), serverUrl = account.serverUrl,
                accountId = account.accountId, sourceKind = "CAMERA", sourceId = sourceId,
                uri = file.uri, folderId = folderId,
                originalFilename = file.displayName.ifBlank { "file_${file.mediaStoreId}" },
                size = file.size, lastModified = file.lastModified,
                originalMode = originalMode, checksum = checksum
            )
            when (source.state(record)) {
                CameraSourceState.Current -> sessions.insert(record)
                CameraSourceState.Missing, CameraSourceState.Changed -> return ResumableResult.SourceChanged
                CameraSourceState.Unavailable -> return ResumableResult.Retry
            }
        }
        return transfer(record, account, budget, isPaused)
    }

    private suspend fun transfer(initial: UploadSessionRecord, account: BackupAccount, budget: WorkBudget,
                                 isPaused: suspend () -> Boolean): ResumableResult {
        var record = initial
        var progressed = false
        var finalizingPolls = 0
        var lastConflictOffset: Long? = null
        var uncertainCompleteAttempts = 0
        fun pending() = if (progressed) ResumableResult.Progress else ResumableResult.Retry
        while (true) {
            currentCoroutineContext().ensureActive()
            if (budget.isExhausted()) return pending()
            when (source.state(record)) {
                CameraSourceState.Missing, CameraSourceState.Changed -> {
                    sessions.requestCancel(record.clientRequestId)
                    return if (cancel(record, account)) ResumableResult.SourceChanged else pending()
                }
                CameraSourceState.Unavailable -> return pending()
                CameraSourceState.Current -> Unit
            }
            val remote = try {
                val response = if (record.serverSessionId == null) {
                    service(account).create(record.createRequest()).await()
                } else {
                    service(account).get(record.serverSessionId).await()
                }
                if (response.code() == 404 && record.serverSessionId != null) {
                    sessions.delete(record.clientRequestId)
                    return pending()
                }
                if (!response.isSuccessful) {
                    val result = failure(response)
                    if (result is ResumableResult.Failed) sessions.requestCancel(record.clientRequestId)
                    return if (result == ResumableResult.Retry) pending() else result
                }
                response.body()?.valid(record.size) ?: return pending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return pending()
            }
            if (sessions.setServerState(record.clientRequestId, remote.id!!, remote.confirmedOffset!!) == 0) {
                return pending()
            }
            if (remote.confirmedOffset!! > record.confirmedOffset) progressed = true
            record = record.copy(serverSessionId = remote.id, confirmedOffset = remote.confirmedOffset)
            when (remote.state) {
                "COMPLETED" -> return remote.result?.let { ResumableResult.Complete(it) } ?: pending()
                "FAILED" -> {
                    sessions.delete(record.clientRequestId)
                    return if (remote.error == "CHECKSUM_MISMATCH") ResumableResult.ChecksumMismatch
                    else pending() // Терминальная сессия не продолжима; новый UUID создаётся в следующем прогоне.
                }
                "CANCELLED", "EXPIRED" -> {
                    sessions.delete(record.clientRequestId)
                    return pending()
                }
                "FINALIZING" -> {
                    if (++finalizingPolls >= 3) return pending()
                    delay(2_000)
                    continue
                }
                "UPLOADING" -> Unit
                else -> return pending()
            }
            val offset = remote.confirmedOffset!!
            currentCoroutineContext().ensureActive()
            if (budget.isExhausted()) return pending()
            if (isPaused()) return ResumableResult.Progress
            if (offset == record.size) {
                try {
                    val response = service(account).complete(remote.id!!).await()
                    if (!response.isSuccessful) {
                        val result = failure(response)
                        return if (result == ResumableResult.Retry) pending() else result
                    }
                    // Даже после 202/потерянного ответа состояние уточняется GET.
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++uncertainCompleteAttempts > 2) return pending()
                    continue // Потерянный ответ complete уточняется GET в начале цикла.
                }
                continue
            }
            val length = min(min(SyncConfig.RESUMABLE_CHUNK_BYTES, remote.maxChunkSizeBytes ?: 0), record.size - offset)
            if (length <= 0) return pending()
            val response = try {
                service(account).patch(remote.id!!, offset, ChunkBody(source, record, offset, length)).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Исход неизвестен: следующий цикл начнётся с GET, без повторной отправки старого offset.
                try {
                    val state = service(account).get(remote.id!!).await()
                    val confirmed = state.body()?.confirmedOffset
                    if (state.isSuccessful && confirmed != null && confirmed > offset) {
                        sessions.setOffset(record.clientRequestId, confirmed)
                        progressed = true
                        continue
                    }
                    return pending()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    return pending()
                }
            }
            if (!response.isSuccessful) {
                if (response.code() == 409) {
                    val reason = ApiErrorParser.parse(response)
                    if (reason == "OFFSET_MISMATCH" && lastConflictOffset != offset) {
                        lastConflictOffset = offset
                        continue
                    }
                    return pending()
                }
                val result = failure(response)
                if (result is ResumableResult.Failed) sessions.requestCancel(record.clientRequestId)
                return if (result == ResumableResult.Retry) pending() else result
            }
            val acknowledged = response.body()?.valid(record.size) ?: return pending()
            if (acknowledged.confirmedOffset!! <= offset) return pending()
            sessions.setOffset(record.clientRequestId, acknowledged.confirmedOffset)
            progressed = true
            lastConflictOffset = null
        }
    }

    private suspend fun cancel(record: UploadSessionRecord, account: BackupAccount): Boolean {
        return try {
            val id = record.serverSessionId ?: run {
                val response = service(account).create(record.createRequest()).await()
                if (!response.isSuccessful) return false
                val remote = response.body()?.valid(record.size) ?: return false
                sessions.setServerState(record.clientRequestId, remote.id!!, remote.confirmedOffset!!)
                remote.id!!
            }
            val response = service(account).cancel(id).await()
            val completed = if (response.code() == 409) {
                val state = service(account).get(id).await()
                state.isSuccessful && state.body()?.state == "COMPLETED"
            } else false
            if (response.isSuccessful || response.code() == 404 || completed) {
                sessions.delete(record.clientRequestId)
                true
            } else false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun forget(requestId: String) = sessions.delete(requestId)

    suspend fun forgetBySource(file: MediaFile) {
        val account = accounts.current() ?: return
        sessions.forSource(account.serverUrl, account.accountId, "CAMERA", file.mediaStoreId.toString())
            .filter { it.checksum == file.checksum?.lowercase() && it.size == file.size && it.lastModified == file.lastModified }
            .forEach { sessions.delete(it.clientRequestId) }
    }

    private fun UploadSessionRecord.createRequest() = CreateUploadSessionRequest(
        clientRequestId, folderId, originalFilename, size, checksum
    )

    private fun UploadSessionDto.valid(size: Long): UploadSessionDto? =
        takeIf { !id.isNullOrBlank() && confirmedOffset != null && confirmedOffset in 0..size && !state.isNullOrBlank() }

    private fun failure(response: Response<*>): ResumableResult = when (response.code()) {
        400 -> ResumableResult.Failed(FailureReason.HTTP_400)
        413 -> ResumableResult.Failed(FailureReason.HTTP_413)
        else -> { ApiErrorParser.read(response); ResumableResult.Retry }
    }
}

private class ChunkBody(
    private val source: CameraUploadSource,
    private val record: UploadSessionRecord,
    private val offset: Long,
    private val length: Long
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = length
    override fun writeTo(sink: BufferedSink) {
        source.open(record, offset).use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Исходный файл закончился во время блока")
                sink.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}

/** Retrofit Call отменяется сразу при остановке Worker; новый Call открывает поток заново. */
private suspend fun <T> Call<T>.await(): Response<T> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback<T> {
        override fun onResponse(call: Call<T>, response: Response<T>) {
            if (continuation.isActive) continuation.resume(response)
        }
        override fun onFailure(call: Call<T>, error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    })
}

package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.api.models.ChecksumExistsRequest
import ru.tbcarus.photo_cloud_client.backup.BackupAccount
import ru.tbcarus.photo_cloud_client.backup.BackupAccountProvider
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ручная сверка Camera с Server (Q-01) поверх существующего pipeline, без своего Worker:
 * scan и checksum выполняет Поставщик, запрос checksum-exists — Загрузчик; состояние — в Room.
 *
 * Стадии: SCAN (полный scan после запуска) → HASH (checksum всех строк набора) → SERVER (партии checksum-exists)
 * → AWAITING_DECISION либо FINISHED. Пока проверка не FINISHED, Загрузчик не начинает новые Camera upload.
 * Записи стадий условны по runId: отменённая или перезапущенная проверка поздний результат не получает.
 * Отсутствие файла фиксируется только по явному missing из успешного ответа; статусы LocalMedia до решения
 * пользователя не понижаются.
 */
@Singleton
class CameraCheckRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val apiServiceFactory: ApiServiceFactory,
    private val streamOpener: MediaStreamOpener,
    private val accounts: BackupAccountProvider,
    private val syncScheduler: SyncScheduler,
    private val tokenStorage: TokenStorage
) {
    @Inject lateinit var automaticBackupNetwork: AutomaticBackupNetwork

    private suspend fun fileService() = if (::automaticBackupNetwork.isInitialized) {
        val network = automaticBackupNetwork.current() ?: throw IOException("Нет разрешённой сети для Camera backup")
        apiServiceFactory.authFileService(network)
    } else apiServiceFactory.authFileService()

    private companion object {
        const val TAG = "CameraCheck"
    }

    private val dao get() = db.cameraCheckDao()
    private val media get() = db.mediaFileDao()

    // Действия пользователя сериализуются между собой; стадии Worker — отдельно, чтобы отмена не ждала партию.
    private val actionMutex = Mutex()
    private val stageMutex = Mutex()

    /** Проверка и счётчики её набора; null — проверки нет. */
    fun observe(): Flow<CameraCheckView?> =
        combine(dao.observe(), dao.observeCounts()) { check, counts -> check?.let { CameraCheckView(it, counts) } }
            .distinctUntilChanged()

    // --- Действия пользователя ---

    suspend fun start(): CheckStartResult = actionMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!context.hasMediaReadPermission() || !context.hasVideoReadPermission()) return@withContext CheckStartResult.NoPermission
            val account = accounts.current()
                ?: return@withContext if (tokenStorage.getTokens() == null) {
                    CheckStartResult.NotSignedIn
                } else {
                    CheckStartResult.NoServer
                }
            val started = db.withTransaction {
                if (dao.get()?.state.let { it != null && it != CameraCheckState.FINISHED }) return@withTransaction false
                dao.deleteItems()
                dao.put(newCheck(account))
                true
            }
            if (!started) return@withContext CheckStartResult.AlreadyRunning
            // Продолжение, а не KEEP: scan выполнит прогон, начатый после записи проверки.
            CheckStartResult.Started(scheduled = schedule { syncScheduler.continueProvider().await() })
        }
    }

    /**
     * «Отменить проверку» и «Отказаться от проверки»: незавершённый результат удаляется, пауза снимается.
     * Статусы LocalMedia проверка до подтверждения не меняла, поэтому восстанавливать нечего.
     * @return false — активной проверки уже нет (повторное нажатие, завершение раньше отмены).
     */
    suspend fun cancel(): Boolean = actionMutex.withLock {
        withContext(Dispatchers.IO) {
            val removed = db.withTransaction {
                val check = dao.get()
                if (check == null || check.state == CameraCheckState.FINISHED) return@withTransaction null
                dao.deleteItems()
                dao.deleteCheck()
                check
            } ?: return@withContext false
            resumeUploads(afterError = removed.state == CameraCheckState.ERROR)
            true
        }
    }

    /** «Позже»: результат сохраняется свёрнутым, пауза остаётся. */
    suspend fun defer(): Boolean = actionMutex.withLock {
        withContext(Dispatchers.IO) { dao.setDeferred(true) > 0 }
    }

    /** Закрыть итог завершённой проверки. */
    suspend fun dismiss(): Unit = actionMutex.withLock {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                if (dao.get()?.state == CameraCheckState.FINISHED) {
                    dao.deleteItems()
                    dao.deleteCheck()
                }
            }
        }
    }

    /**
     * Продолжить остановленную ошибкой проверку с места остановки. Смена назначения требует новой проверки:
     * прежние ответы к нему не относятся.
     */
    suspend fun retry(): CheckStartResult {
        val check = withContext(Dispatchers.IO) { dao.get() }
        if (check?.state != CameraCheckState.ERROR) return CheckStartResult.AlreadyRunning
        if (check.error == CameraCheckError.DESTINATION_CHANGED) {
            if (!cancel()) return CheckStartResult.AlreadyRunning
            return start()
        }
        return actionMutex.withLock {
            withContext(Dispatchers.IO) {
                // Под мьютексом заново: предыдущее нажатие уже могло перевести проверку в RUNNING.
                val current = dao.get()
                if (current?.runId != check.runId || current.state != CameraCheckState.ERROR ||
                    dao.setActiveState(check.runId, CameraCheckState.RUNNING, null) == 0
                ) {
                    return@withContext CheckStartResult.AlreadyRunning
                }
                val scheduled = when (check.stage) {
                    CameraCheckStage.SCAN, CameraCheckStage.HASH -> schedule { syncScheduler.continueProvider().await() }
                    // Продолжение разовой цепочки ждало бы её накопленный backoff — нужен отдельный ручной прогон.
                    CameraCheckStage.SERVER -> schedule { syncScheduler.retryUploadNow().await() }
                    CameraCheckStage.DONE -> true
                }
                CheckStartResult.Started(scheduled)
            }
        }
    }

    /**
     * «Загрузить N файлов»: отсутствующие на Server файлы той же версии возвращаются в обычную синхронизацию,
     * затем пауза снимается. SYNCED → CHECKSUM_READY (обычный pre-check спросит Server заново), FAILED —
     * ручной повтор этой строки; файлы, уже идущие по pipeline, не трогаются; изменённые после проверки
     * обрабатывает обычная синхронизация.
     */
    suspend fun confirmUpload(): ConfirmUploadResult = actionMutex.withLock {
        withContext(Dispatchers.IO) {
            val check = dao.get()
            if (check?.state != CameraCheckState.AWAITING_DECISION) return@withContext ConfirmUploadResult.NotAwaiting
            if (!context.hasMediaReadPermission() || !context.hasVideoReadPermission()) return@withContext ConfirmUploadResult.NoPermission
            val account = accounts.current() ?: return@withContext ConfirmUploadResult.NotSignedIn
            if (!check.matches(account)) return@withContext ConfirmUploadResult.DestinationChanged

            var queued = 0
            var resetFailed = false
            val applied = db.withTransaction {
                if (dao.get()?.let { it.runId == check.runId && it.state == CameraCheckState.AWAITING_DECISION } != true) {
                    return@withTransaction false
                }
                for (item in dao.missingItems()) {
                    val row = media.getByUri(item.uri) ?: continue
                    if (row.size != item.size || row.lastModified != item.lastModified) continue
                    when (row.status) {
                        MediaFileStatus.SYNCED ->
                            if (row.checksum.equals(item.checksum, ignoreCase = true) &&
                                media.updateStatusIfUnchanged(row, MediaFileStatus.SYNCED, MediaFileStatus.CHECKSUM_READY) > 0
                            ) queued++
                        MediaFileStatus.FAILED ->
                            if (media.resetFailedForManualRetryIfUnchanged(row.uri, row.size, row.lastModified) > 0) {
                                queued++
                                resetFailed = true
                            }
                        MediaFileStatus.LOCAL_DELETED -> Unit
                        // PENDING … UPLOADING уже обрабатываются: после снятия паузы дойдут до upload сами.
                        else -> queued++
                    }
                }
                dao.deleteItems()
                dao.put(
                    check.copy(
                        state = CameraCheckState.FINISHED,
                        deferred = false,
                        finishedAt = System.currentTimeMillis(),
                        queuedForUpload = queued
                    )
                )
                true
            }
            if (!applied) return@withContext ConfirmUploadResult.NotAwaiting
            val providerScheduled = !resetFailed || schedule { syncScheduler.continueProvider().await() }
            ConfirmUploadResult.Queued(queued, scheduled = resumeUploads() && providerScheduled)
        }
    }

    // --- Состояние для Worker ---

    /** Новые Camera upload приостановлены: проверка выполняется или ждёт решения пользователя. */
    suspend fun isUploadPaused(): Boolean = dao.countPausing() > 0

    /** Загрузчику есть работа сверки. */
    suspend fun awaitsServer(): Boolean =
        dao.get()?.let { it.isActive && it.stage == CameraCheckStage.SERVER } == true

    /** runId проверки, ждущей scan; Поставщик запрашивает его до своего scan — подходит только scan после запуска. */
    suspend fun runAwaitingScan(): String? =
        dao.get()?.takeIf { it.isActive && it.stage == CameraCheckStage.SCAN }?.runId

    /** Ошибка локальной стадии (SCAN или HASH) — видна пользователю, следующий успешный прогон её снимает. */
    suspend fun markLocalError(error: CameraCheckError) {
        val check = dao.get() ?: return
        if (check.stage == CameraCheckStage.SCAN || check.stage == CameraCheckStage.HASH) {
            dao.setActiveState(check.runId, CameraCheckState.ERROR, error)
        }
    }

    /** Ошибка серверной стадии; без неё вызов ничего не меняет. */
    suspend fun markServerError(error: CameraCheckError) {
        val check = dao.get() ?: return
        if (check.stage == CameraCheckStage.SERVER) dao.setActiveState(check.runId, CameraCheckState.ERROR, error)
    }

    /**
     * Scan Поставщика, начатый после запуска [runId], завершён. Неполный индекс набором не становится —
     * иначе отсутствующая часть Camera выглядела бы проверенной.
     */
    suspend fun onScanFinished(runId: String, complete: Boolean) {
        db.withTransaction {
            val check = dao.get()
            if (check?.runId != runId || check.stage != CameraCheckStage.SCAN || !check.isActive) return@withTransaction
            if (!complete) {
                dao.setActiveState(runId, CameraCheckState.ERROR, CameraCheckError.SCAN_INCOMPLETE)
                return@withTransaction
            }
            dao.deleteItems()
            dao.snapshotItems()
            dao.advanceStage(runId, CameraCheckStage.SCAN, CameraCheckStage.HASH)
        }
    }

    /**
     * Стадия HASH, вызывается Поставщиком после своего хеширования: checksum строк набора берётся из LocalMedia
     * текущей версии; PENDING и HASHING ждут Поставщика; для FAILED без checksum файл читается здесь, а статус,
     * причина и retryCount строки не меняются. Нечитаемый файл — UNVERIFIABLE. Перед каждым файлом — бюджет.
     */
    suspend fun collectChecksums(budget: WorkBudget): StageProgress = stageMutex.withLock {
        withContext(Dispatchers.IO) {
            val check = dao.get()
            if (check == null || !check.isActive || check.stage != CameraCheckStage.HASH) return@withContext StageProgress.NONE
            val runId = check.runId
            if (!context.hasMediaReadPermission() || !context.hasVideoReadPermission()) {
                markLocalError(CameraCheckError.NO_MEDIA_PERMISSION)
                return@withContext StageProgress.NONE
            }
            // Поставщик дошёл сюда после успешных scan и hash: прежняя локальная ошибка больше не актуальна.
            if (check.state == CameraCheckState.ERROR) dao.setActiveState(runId, CameraCheckState.RUNNING, null)

            var progressed = false
            var afterId = Long.MIN_VALUE
            var afterUri = ""
            loop@ while (!budget.isExhausted()) {
                val batch = dao.itemsWithoutChecksum(afterId, afterUri, SyncConfig.PROVIDER_BATCH_SIZE)
                if (batch.isEmpty()) break
                for (item in batch) {
                    if (budget.isExhausted()) break@loop
                    if (!isCurrent(runId)) return@withContext StageProgress.NONE
                    afterId = item.mediaStoreId
                    afterUri = item.uri
                    if (collectOne(runId, item)) progressed = true
                }
            }
            val remaining = dao.countWithoutChecksum() > 0
            if (!remaining && dao.advanceStage(runId, CameraCheckStage.HASH, CameraCheckStage.SERVER) > 0) {
                requestServerStage()
            }
            StageProgress(progressed, remaining)
        }
    }

    /** @return true — строка набора получила checksum или итог. */
    private suspend fun collectOne(runId: String, item: CameraCheckItem): Boolean {
        val row = media.getByUri(item.uri) ?: return inRun(runId) { dao.deleteItem(item.uri) }
        val known = row.checksum
        return when {
            row.status == MediaFileStatus.PENDING || row.status == MediaFileStatus.HASHING -> false
            known != null -> inRun(runId) { dao.setItemChecksum(item.uri, known, row.size, row.lastModified) }
            row.status == MediaFileStatus.FAILED -> hashFailed(runId, row)
            // Строка без checksum вне hash-стадий не должна встречаться — не проверяется.
            else -> inRun(runId) { dao.setItemResult(item.uri, CameraCheckResult.UNVERIFIABLE) }
        }
    }

    private suspend fun hashFailed(runId: String, row: MediaFile): Boolean {
        val checksum = try {
            val uri = Uri.parse(row.uri)
            (if (row.collection == MediaCollection.IMAGE) streamOpener.open(uri)
             else streamOpener.open(uri, row.collection))?.use { ChecksumUtil.sha256(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Файл не прочитан, mediaStoreId=${row.mediaStoreId}: ${e.message}")
            null
        }
        return inRun(runId) {
            val current = media.getByUri(row.uri)
            when {
                current == null -> dao.deleteItem(row.uri)
                // Файл изменился во время чтения — следующий проход возьмёт новую версию.
                current.size != row.size || current.lastModified != row.lastModified -> return@inRun
                checksum == null -> dao.setItemResult(row.uri, CameraCheckResult.UNVERIFIABLE)
                else -> dao.setItemChecksum(row.uri, checksum, row.size, row.lastModified)
            }
        }
    }

    /**
     * Стадия SERVER, вызывается Загрузчиком с ID CAMERA этого прогона. Партии checksum-exists по
     * [SyncConfig.PRECHECK_BATCH_SIZE]; результат партии и снятие ошибки — одной транзакцией. Ошибка запроса
     * или неполный ответ ничего не помечают отсутствующим: следующий прогон продолжит с той же партии.
     */
    suspend fun checkOnServer(folderId: Long, budget: WorkBudget): ServerCheckOutcome = stageMutex.withLock {
        withContext(Dispatchers.IO) {
            val check = dao.get()
            if (check == null || !check.isActive || check.stage != CameraCheckStage.SERVER) {
                return@withContext ServerCheckOutcome.NotActive
            }
            val runId = check.runId
            val account = accounts.current()
            if (account == null) {
                val error = if (tokenStorage.getTokens() == null) CameraCheckError.NOT_SIGNED_IN else CameraCheckError.NO_SERVER_ADDRESS
                return@withContext fail(error, retry = false)
            }
            if (!check.matches(account)) return@withContext fail(CameraCheckError.DESTINATION_CHANGED, retry = false)
            if (check.cameraFolderId == null) {
                dao.setCameraFolder(runId, folderId)
            } else if (check.cameraFolderId != folderId) {
                return@withContext fail(CameraCheckError.DESTINATION_CHANGED, retry = false)
            }

            var progressed = false
            while (!budget.isExhausted()) {
                if (!isCurrent(runId)) return@withContext ServerCheckOutcome.NotActive
                val batch = dao.itemsAwaitingServer(SyncConfig.PRECHECK_BATCH_SIZE)
                if (batch.isEmpty()) break
                val byChecksum = batch.groupBy { it.checksum!!.lowercase() }

                val response = try {
                    fileService()
                        .checksumsExist(ChecksumExistsRequest(folderId, byChecksum.keys.toList()))
                        .execute()
                } catch (e: IOException) {
                    Log.w(TAG, "Сверка не получила ответ Server: ${e.message}")
                    return@withContext fail(CameraCheckError.SERVER_UNAVAILABLE, retry = true)
                }
                if (!response.isSuccessful) {
                    val error = when (response.code()) {
                        401, 403 -> CameraCheckError.AUTH
                        else -> CameraCheckError.SERVER_ERROR
                    }
                    return@withContext fail(error, retry = true)
                }
                val body = response.body() ?: return@withContext fail(CameraCheckError.BAD_RESPONSE, retry = true)
                // Ответ относится к назначению запроса: адрес или аккаунт могли смениться, пока он выполнялся.
                if (accounts.current() != account) return@withContext fail(CameraCheckError.DESTINATION_CHANGED, retry = false)

                val existing = body.existing.mapTo(HashSet()) { it.lowercase() }
                val missing = body.missing.mapTo(HashSet()) { it.lowercase() }
                var resolved = 0
                val applied = inRun(runId) {
                    for ((checksum, items) in byChecksum) {
                        val result = when (checksum) {
                            in existing -> CameraCheckResult.EXISTS
                            in missing -> CameraCheckResult.MISSING
                            else -> continue // нет достоверного ответа — остаётся без результата
                        }
                        items.forEach { item -> resolved += applyServerResult(item, checksum, result) }
                    }
                    dao.setActiveState(runId, CameraCheckState.RUNNING, null)
                }
                if (!applied) return@withContext ServerCheckOutcome.NotActive
                // Ответ не разрешил ни одной строки — продолжать по нему нельзя.
                if (resolved == 0) return@withContext fail(CameraCheckError.BAD_RESPONSE, retry = true)
                progressed = true
            }
            if (dao.countAwaitingServer() > 0) return@withContext ServerCheckOutcome.Remaining(progressed)
            finish(runId)
            ServerCheckOutcome.Done
        }
    }

    /** Результат одной строки внутри транзакции партии; @return 1 — строка разрешена. */
    private suspend fun applyServerResult(item: CameraCheckItem, checksum: String, result: CameraCheckResult): Int {
        val row = media.getByUri(item.uri)
        when {
            row == null -> {
                dao.deleteItem(item.uri)
                return 1
            }
            // Файл изменился после получения checksum: ответ относится к прежнему содержимому.
            row.size != item.size || row.lastModified != item.lastModified ->
                dao.setItemResult(item.uri, CameraCheckResult.UNVERIFIABLE)
            else -> {
                dao.setItemResult(item.uri, result)
                // Подтверждение existing для ещё не проверенной строки — тот же переход, что у pre-check.
                if (result == CameraCheckResult.EXISTS && row.status == MediaFileStatus.CHECKSUM_READY &&
                    row.checksum.equals(checksum, ignoreCase = true)
                ) {
                    media.updateStatusIfUnchanged(row, MediaFileStatus.CHECKSUM_READY, MediaFileStatus.SYNCED)
                }
            }
        }
        return 1
    }

    /** Все строки набора получили результат: без отсутствующих ручной режим завершается и пауза снимается. */
    private suspend fun finish(runId: String) {
        db.withTransaction {
            val check = dao.get()
            if (check?.runId != runId || !check.isActive || check.stage != CameraCheckStage.SERVER) return@withTransaction
            val counts = dao.counts()
            val result = check.copy(
                stage = CameraCheckStage.DONE,
                error = null,
                checked = counts.total,
                confirmed = counts.confirmed,
                missing = counts.missing,
                unverifiable = counts.unverifiable
            )
            if (counts.missing > 0) {
                dao.put(result.copy(state = CameraCheckState.AWAITING_DECISION, deferred = false))
            } else {
                dao.deleteItems()
                dao.put(result.copy(state = CameraCheckState.FINISHED, finishedAt = System.currentTimeMillis()))
            }
        }
    }

    private suspend fun fail(error: CameraCheckError, retry: Boolean): ServerCheckOutcome {
        markServerError(error)
        return ServerCheckOutcome.Failed(error, retry)
    }

    /**
     * Загрузчик для серверной стадии сразу после HASH → SERVER (один раз на переход): продолжение в цепочке
     * ручных прогонов, чтобы не потеряться за работающим прогоном и не ждать backoff разовой цепочки (Q-01.3).
     */
    private suspend fun requestServerStage() {
        if (tokenStorage.getTokens() == null) {
            markServerError(CameraCheckError.NOT_SIGNED_IN)
            return
        }
        schedule { syncScheduler.continueUploadNow().await() }
    }

    /**
     * Сигнал Поставщика при RUNNING/SERVER, в том числе после перезапуска процесса: прогон в цепочке ручных
     * прогонов без ожидания backoff разовой цепочки. KEEP: ждущий или работающий ручной прогон выполнит стадию
     * сам, повторные сигналы очередь не растят. ERROR не трогается — его повторяют backoff и «Повторить».
     * @return true — прогон для серверной стадии записан в WorkManager.
     */
    suspend fun resumeServerStage(): Boolean {
        val check = dao.get() ?: return false
        if (check.state != CameraCheckState.RUNNING || check.stage != CameraCheckStage.SERVER) return false
        if (tokenStorage.getTokens() == null) return false
        return schedule { syncScheduler.retryUploadNow().await() }
    }

    /**
     * После снятия паузы: ожидающая очередь Camera продолжает обычную синхронизацию. После ERROR разовая цепочка
     * обычно ждёт backoff — тогда ещё и немедленная попытка в цепочке ручных прогонов (Q-01.2); работающий или
     * ждущий прогон не отменяется, параллельную отправку одного файла исключают Mutex pre-check и upload.
     */
    private suspend fun resumeUploads(afterError: Boolean = false): Boolean {
        if (tokenStorage.getTokens() == null) return true
        val chained = schedule { syncScheduler.continueUpload().await() }
        val immediate = !afterError || schedule { syncScheduler.continueUploadNow().await() }
        return chained && immediate
    }

    private suspend fun schedule(enqueue: suspend () -> Unit): Boolean = try {
        enqueue()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Задача не записана в WorkManager: ${e.message}")
        false
    }

    private suspend fun isCurrent(runId: String): Boolean = dao.get()?.let { it.runId == runId && it.isActive } == true

    /** [block] в транзакции, только пока проверка [runId] активна; @return false — проверка отменена или сменилась. */
    private suspend fun inRun(runId: String, block: suspend () -> Unit): Boolean = db.withTransaction {
        if (!isCurrent(runId)) return@withTransaction false
        block()
        true
    }

    private fun CameraCheck.matches(account: BackupAccount) =
        serverUrl == account.serverUrl && accountId == account.accountId

    private fun newCheck(account: BackupAccount) = CameraCheck(
        runId = UUID.randomUUID().toString(),
        state = CameraCheckState.RUNNING,
        stage = CameraCheckStage.SCAN,
        serverUrl = account.serverUrl,
        accountId = account.accountId,
        startedAt = System.currentTimeMillis()
    )
}

sealed interface CheckStartResult {
    data object NoPermission : CheckStartResult
    data object NotSignedIn : CheckStartResult
    data object NoServer : CheckStartResult
    /** Другая проверка выполняется или ждёт решения. */
    data object AlreadyRunning : CheckStartResult
    /** [scheduled] — следующая задача записана в WorkManager; иначе её подхватят старт процесса и periodic. */
    data class Started(val scheduled: Boolean) : CheckStartResult
}

sealed interface ConfirmUploadResult {
    data object NotAwaiting : ConfirmUploadResult
    data object NoPermission : ConfirmUploadResult
    data object NotSignedIn : ConfirmUploadResult
    /** Текущие Server или аккаунт не те, для которых получен результат. */
    data object DestinationChanged : ConfirmUploadResult
    data class Queued(val count: Int, val scheduled: Boolean) : ConfirmUploadResult
}

/** Итог локальной стадии за прогон: [progressed] — строки набора продвинулись; [remaining] — работа осталась. */
data class StageProgress(val progressed: Boolean, val remaining: Boolean) {
    companion object {
        val NONE = StageProgress(progressed = false, remaining = false)
    }
}

sealed interface ServerCheckOutcome {
    /** Серверной стадии нет: проверка отменена, на другой стадии или завершена. */
    data object NotActive : ServerCheckOutcome
    data object Done : ServerCheckOutcome
    data class Remaining(val progressed: Boolean) : ServerCheckOutcome
    /** [retry] — временная причина, Загрузчик повторяет прогон. */
    data class Failed(val error: CameraCheckError, val retry: Boolean) : ServerCheckOutcome
}

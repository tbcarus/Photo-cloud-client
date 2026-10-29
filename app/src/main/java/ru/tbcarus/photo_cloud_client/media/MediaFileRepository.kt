package ru.tbcarus.photo_cloud_client.media

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class MediaFileRepository @Inject constructor(
    private val dao: MediaFileDao,
    private val mediaStoreRepository: MediaStoreRepository
) {

    private companion object {
        const val TAG = "MediaFileRepository"

        // Исчезновение большой части индекса может быть временным сбоем MediaStore или тома:
        // такое удаление подтверждается повторным полным снимком через паузу.
        const val MASS_DELETE_MIN_ROWS = 10
        const val MASS_DELETE_RECHECK_DELAY_MS = 5_000L
    }

    // Один scan одновременно на весь процесс: Поставщик (one-time и periodic) и UI не применяют снимки параллельно.
    private val mutex = Mutex()

    /** Поток всех файлов локального индекса, отсортированных по createdAt DESC. */
    fun observeAll(): Flow<List<MediaFile>> = dao.getAll()

    /** Счётчики индекса по группам состояний; повторы одинакового значения отбрасываются. */
    fun observeCounters(): Flow<MediaFileCounters> = dao.observeCounters().distinctUntilChanged()

    /**
     * Сканирует фото из MediaStore и синхронизирует локальный индекс Room:
     * - добавляет новые записи;
     * - обновляет metadata существующих (не трогая checksum/status/serverFileId);
     * - удаляет stale-записи (файлы, которых больше нет в MediaStore) — только по полному снимку;
     *   массовое исчезновение удаляется, только если его подтверждает повторный снимок.
     *
     * Снимок применяется порциями по [SyncConfig.SCAN_WRITE_BATCH_SIZE] — каждая одной транзакцией, без общей
     * транзакции на весь архив. Удаление выполняется только после применения всех порций.
     */
    suspend fun scanImages(): ScanOutcome = mutex.withLock {
        try {
            when (val outcome = mediaStoreRepository.loadImages()) {
                is MediaStoreScanOutcome.PermissionDenied -> ScanOutcome.PermissionDenied
                is MediaStoreScanOutcome.Error -> ScanOutcome.Error(outcome.message)
                is MediaStoreScanOutcome.Success -> applyToRoom(outcome.images, outcome.isComplete)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanOutcome.Error(e.message ?: "Ошибка при синхронизации индекса")
        }
    }

    /** Есть ли в Room записи для Загрузчика (граница Поставщик → Загрузчик). */
    suspend fun hasReadyForUpload(): Boolean = dao.countReadyForUpload() > 0

    private suspend fun applyToRoom(images: List<MediaStoreImage>, isComplete: Boolean): ScanOutcome.Success {
        // 1–2. Порция за порцией: новые записи вставляются (существующие по mediaStoreId игнорируются),
        // у всех обновляются MediaStore-поля. Изменившийся файл (size/lastModified) в том же UPDATE
        // возвращается в PENDING из любого статуса; у неизменённого статус и checksum сохраняются.
        // Ошибка порции прерывает scan до удаления — отсутствующие строки не трогаются.
        images.chunked(SyncConfig.SCAN_WRITE_BATCH_SIZE).forEach { chunk ->
            dao.applyScanBatch(chunk.map { it.toMediaFile() })
        }

        // 3. Stale detection: удаляем из Room файлы, которых больше нет в MediaStore.
        // Неполный снимок не доказывает удаления — строки не трогаем. Server при этом не затрагивается.
        val staleIds = if (isComplete) {
            confirmedStaleIds(images)
        } else {
            Log.w(TAG, "Снимок MediaStore неполный — удаление отсутствующих строк пропущено")
            emptyList()
        }

        if (staleIds.isNotEmpty()) {
            // SQLite имеет лимит bind-параметров (~999), удаляем пачками
            staleIds.chunked(500).forEach { chunk ->
                dao.deleteByMediaStoreIds(chunk)
            }
        }

        return ScanOutcome.Success(
            ScanResult(
                scanned            = images.size,
                // insertedOrUpdated — кол-во записей, прошедших insert+update pipeline,
                // а не только новых; insert с IGNORE не различает «добавлено» и «проигнорировано»
                insertedOrUpdated  = images.size,
                deletedStale       = staleIds.size
            )
        )
    }

    /**
     * Строки Room, отсутствующие в полном снимке. Если снимок пуст при непустом индексе или пропала
     * большая часть индекса, через паузу выполняется повторный снимок: удаляются только строки,
     * отсутствующие в обоих. Неудачный или неполный повтор — ничего не удаляем до следующего scan.
     */
    private suspend fun confirmedStaleIds(images: List<MediaStoreImage>): List<Long> {
        val dbIds      = dao.getAllMediaStoreIds()
        val scannedIds = images.mapTo(HashSet()) { it.mediaStoreId }
        val staleIds   = dbIds.filter { it !in scannedIds }

        val massDisappearance = staleIds.isNotEmpty() && (images.isEmpty() ||
            (staleIds.size >= MASS_DELETE_MIN_ROWS && staleIds.size * 2 >= dbIds.size))
        if (!massDisappearance) return staleIds

        Log.w(TAG, "Из снимка пропало ${staleIds.size} из ${dbIds.size} строк — повторная проверка")
        delay(MASS_DELETE_RECHECK_DELAY_MS)
        val recheck = mediaStoreRepository.loadImages()
        if (recheck !is MediaStoreScanOutcome.Success || !recheck.isComplete) {
            Log.w(TAG, "Повторный снимок недостоверен — удаление отсутствующих строк пропущено")
            return emptyList()
        }
        val recheckIds = recheck.images.mapTo(HashSet()) { it.mediaStoreId }
        return staleIds.filter { it !in recheckIds }
    }

    /** Преобразует MediaStore-снимок в новую запись локального индекса. */
    private fun MediaStoreImage.toMediaFile() = MediaFile(
        mediaStoreId = mediaStoreId,
        serverFileId = null,
        uri          = uri,
        displayName  = displayName,
        relativePath = relativePath,
        mimeType     = mimeType,
        size         = size,
        createdAt    = createdAt,
        lastModified = lastModified,
        checksum     = null,
        status       = MediaFileStatus.PENDING
    )
}

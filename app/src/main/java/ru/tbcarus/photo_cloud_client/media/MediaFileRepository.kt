package ru.tbcarus.photo_cloud_client.media

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaFileRepository @Inject constructor(
    private val dao: MediaFileDao,
    private val mediaStoreRepository: MediaStoreRepository
) {

    /** Поток всех файлов локального индекса, отсортированных по createdAt DESC. */
    fun observeAll(): Flow<List<MediaFile>> = dao.getAll()

    /**
     * Сканирует фото из MediaStore и синхронизирует локальный индекс Room:
     * - добавляет новые записи;
     * - обновляет metadata существующих (не трогая checksum/status/serverFileId);
     * - удаляет stale-записи (файлы, которых больше нет в MediaStore).
     *
     * // TODO: при усложнении sync-логики обернуть insert/update/delete в Room transaction.
     */
    suspend fun scanImages(): ScanOutcome {
        return try {
            when (val outcome = mediaStoreRepository.loadImages()) {
                is MediaStoreScanOutcome.PermissionDenied -> ScanOutcome.PermissionDenied
                is MediaStoreScanOutcome.Error -> ScanOutcome.Error(outcome.message)
                is MediaStoreScanOutcome.Success -> applyToRoom(outcome.images)
            }
        } catch (e: Exception) {
            ScanOutcome.Error(e.message ?: "Ошибка при синхронизации индекса")
        }
    }

    private suspend fun applyToRoom(images: List<MediaStoreImage>): ScanOutcome.Success {
        // 1. Вставляем новые записи; существующие (по mediaStoreId) игнорируются
        dao.insertAll(images.map { it.toMediaFile() })

        // 2. Обновляем MediaStore-поля для всех записей.
        // checksum, status и serverFileId не затрагиваются — это зона sync/upload этапов.
        images.forEach { image ->
            // Сбрасываем checksum, если файл изменился с момента последнего расчёта.
            // Вызов ДО updateLocalMetadata — иначе новые size/lastModified уже в БД и сравнение не сработает.
            // TODO: когда появится upload/sync, уточнить откат статусов SYNCED/PENDING_UPLOAD
            // при локальном изменении файла. Сейчас checksum сбрасывается только для локального индекса.
            dao.resetChecksumIfFileChanged(
                mediaStoreId = image.mediaStoreId,
                size         = image.size,
                lastModified = image.lastModified
            )
            dao.updateLocalMetadata(
                mediaStoreId = image.mediaStoreId,
                displayName  = image.displayName,
                relativePath = image.relativePath,
                mimeType     = image.mimeType,
                size         = image.size,
                createdAt    = image.createdAt,
                lastModified = image.lastModified,
                uri          = image.uri
            )
        }

        // 3. Stale detection: удаляем из Room файлы, которых больше нет в MediaStore
        val dbIds      = dao.getAllMediaStoreIds().toHashSet()
        val scannedIds = images.map { it.mediaStoreId }.toHashSet()
        val staleIds   = dbIds.filter { it !in scannedIds }

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

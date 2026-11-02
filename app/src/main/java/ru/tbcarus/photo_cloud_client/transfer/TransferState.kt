package ru.tbcarus.photo_cloud_client.transfer

import ru.tbcarus.photo_cloud_client.library.LibrarySession

enum class TransferKind { DOWNLOAD, UPLOAD }

/** Файл или папка, которые не переданы, и причина. */
data class TransferIssue(val name: String, val reason: String)

/** Папка Server, в которую загружаются файлы. Запоминается в начале операции. */
data class UploadDestination(val folderId: Long, val isRoot: Boolean)

sealed interface TransferState {
    data object Idle : TransferState

    /**
     * Идёт передача. [current] — номер текущего файла с единицы, 0 — подготовка.
     * [bytesTotal] = null — размер неизвестен, процент не показывается.
     */
    data class Running(
        val id: Long,
        val kind: TransferKind,
        val current: Int,
        val total: Int,
        val currentName: String?,
        val bytesDone: Long,
        val bytesTotal: Long?
    ) : TransferState

    /** Файл скачан и проверен, ждёт места сохранения. */
    data class AwaitingDestination(val id: Long, val fileName: String) : TransferState

    /**
     * Операция завершена. [stopReason] — почему остальные файлы не обработаны; [cancelled] — отменена пользователем.
     * [destinationFolderId] и [session] нужны, чтобы обновить открытую папку только той же сессии.
     */
    data class Finished(
        val id: Long,
        val kind: TransferKind,
        val uploaded: Int = 0,
        val existing: Int = 0,
        val issues: List<TransferIssue> = emptyList(),
        val cancelled: Boolean = false,
        val stopReason: String? = null,
        val savedName: String? = null,
        val destinationFolderId: Long? = null,
        val session: LibrarySession? = null
    ) : TransferState

    val isActive: Boolean get() = this is Running || this is AwaitingDestination
}

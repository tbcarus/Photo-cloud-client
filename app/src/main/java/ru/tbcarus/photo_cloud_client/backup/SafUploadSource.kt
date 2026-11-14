package ru.tbcarus.photo_cloud_client.backup

import android.net.Uri
import ru.tbcarus.photo_cloud_client.media.CameraSourceState
import ru.tbcarus.photo_cloud_client.media.UploadSessionRecord
import ru.tbcarus.photo_cloud_client.media.UploadByteSource
import ru.tbcarus.photo_cloud_client.transfer.LocalDocuments
import java.io.InputStream
import javax.inject.Inject

/** Проверяет именно сохранённую версию SAF-файла перед каждым блоком. Неудача stat не означает удаление. */
class SafUploadSource @Inject constructor(
    private val sources: BackupSourceDao,
    private val tree: BackupTreeDao,
    private val documents: LocalDocuments,
    private val access: SourceAccessChecker
) : UploadByteSource {
    override suspend fun currentState(record: UploadSessionRecord): CameraSourceState {
        val fileId = record.sourceId.toLongOrNull() ?: return CameraSourceState.Changed
        val file = tree.file(fileId) ?: return CameraSourceState.Missing
        val source = sources.get(file.sourceId) ?: return CameraSourceState.Missing
        if (source.serverUrl != record.serverUrl || source.accountId != record.accountId ||
            source.sourceUuid != record.backupSource || source.registrationState != RegistrationState.ACTIVE) {
            return CameraSourceState.Changed
        }
        val treeUri = Uri.parse(source.treeUri)
        if (access.check(treeUri) != SourceAccess.AVAILABLE) return CameraSourceState.Unavailable
        if (file.missingSince != null) return CameraSourceState.Missing
        if (file.status == BackupFileStatus.SYNCED) return CameraSourceState.Changed
        if (file.size == null || (file.lastModified == null && record.lastModified != 0L))
            return CameraSourceState.Unavailable
        if (file.checksum?.lowercase() != record.checksum || file.size != record.size ||
            (file.lastModified ?: 0L) != record.lastModified || file.name != record.originalFilename ||
            tree.folder(file.folderId)?.serverFolderId != record.folderId ||
            documents.documentUri(treeUri, file.documentId).toString() != record.uri) {
            return CameraSourceState.Changed
        }
        val current = documents.stat(treeUri, file.documentId) ?: return CameraSourceState.Unavailable
        if (current.isVirtual) return CameraSourceState.Unavailable
        if (current.size == null || (current.lastModified == null && record.lastModified != 0L))
            return CameraSourceState.Unavailable
        return if (current.size == record.size && (current.lastModified ?: 0L) == record.lastModified)
            CameraSourceState.Current else CameraSourceState.Changed
    }

    override fun open(record: UploadSessionRecord, offset: Long): InputStream =
        documents.openAt(Uri.parse(record.uri), offset)
}

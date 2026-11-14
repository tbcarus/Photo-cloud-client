package ru.tbcarus.photo_cloud_client.transfer

import android.net.Uri
import ru.tbcarus.photo_cloud_client.media.CameraSourceState
import ru.tbcarus.photo_cloud_client.media.UploadByteSource
import ru.tbcarus.photo_cloud_client.media.UploadSessionRecord
import java.io.InputStream
import javax.inject.Inject

/** Persisted OpenDocument URI позволяет перечитать блок после рестарта процесса. */
class ManualUploadSource @Inject constructor(private val documents: LocalDocuments) : UploadByteSource {
    override suspend fun currentState(record: UploadSessionRecord): CameraSourceState {
        val uri = Uri.parse(record.uri)
        if (!documents.hasPersistedRead(uri)) return CameraSourceState.Unavailable
        val file = documents.describe(uri)
        if (file.problem != null) return CameraSourceState.Unavailable
        if (file.size == null) return CameraSourceState.Unavailable
        if (file.size != record.size || file.name != record.originalFilename) return CameraSourceState.Changed
        val modified = documents.lastModified(uri)
        if (record.lastModified != 0L && modified != null && record.lastModified != modified) return CameraSourceState.Changed
        return CameraSourceState.Current
    }

    override fun open(record: UploadSessionRecord, offset: Long): InputStream =
        documents.openAt(Uri.parse(record.uri), offset)
}

package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject

sealed interface CameraSourceState {
    data object Current : CameraSourceState
    data object Changed : CameraSourceState
    data object Missing : CameraSourceState
    data object Unavailable : CameraSourceState
}

/** Только адаптер Camera; протокол передачи не знает о MediaStore и будет пригоден для SAF. */
interface UploadByteSource {
    suspend fun currentState(record: UploadSessionRecord): CameraSourceState
    fun open(record: UploadSessionRecord, offset: Long): InputStream
}

open class CameraUploadSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val opener: MediaStreamOpener
) : UploadByteSource {
    override suspend fun currentState(record: UploadSessionRecord): CameraSourceState = state(record)

    open fun state(record: UploadSessionRecord): CameraSourceState {
        if (!context.hasMediaReadPermission(record.collection)) return CameraSourceState.Unavailable
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return CameraSourceState.Unavailable
        val uri = Uri.parse(record.uri)
        return try {
            val projection = arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
                ?: return CameraSourceState.Unavailable
            cursor.use {
                if (!it.moveToFirst()) return CameraSourceState.Missing
                val size = if (it.isNull(0)) -1L else it.getLong(0)
                val modified = if (it.isNull(1) || it.getLong(1) <= 0) record.lastModified
                    else it.getLong(1) * 1000L
                if (size != record.size || modified != record.lastModified ||
                    (record.collection == MediaCollection.IMAGE && opener.readsOriginal() != record.originalMode)) {
                    CameraSourceState.Changed
                } else CameraSourceState.Current
            }
        } catch (_: SecurityException) {
            CameraSourceState.Unavailable
        } catch (_: Exception) {
            CameraSourceState.Unavailable
        }
    }

    override fun open(record: UploadSessionRecord, offset: Long): InputStream =
        opener.openAt(Uri.parse(record.uri), record.originalMode, offset)

    open fun originalMode(): Boolean = opener.readsOriginal()
}

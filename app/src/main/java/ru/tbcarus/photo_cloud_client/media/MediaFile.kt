package ru.tbcarus.photo_cloud_client.media

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_files")
data class MediaFile(
    @PrimaryKey
    val mediaStoreId: Long,
    val serverFileId: Long? = null, // ID файла на сервере, null до загрузки
    val uri: String,
    val displayName: String,        // DISPLAY_NAME из MediaStore
    val relativePath: String?,      // RELATIVE_PATH из MediaStore (DCIM/Camera/, Pictures/…)
    val mimeType: String,
    val size: Long,
    val createdAt: Long,            // DATE_TAKEN из MediaStore (ms)
    val lastModified: Long,         // DATE_MODIFIED из MediaStore, нормализован в milliseconds
    val checksum: String?,          // SHA-256, null пока не посчитан
    val status: MediaFileStatus = MediaFileStatus.PENDING
)

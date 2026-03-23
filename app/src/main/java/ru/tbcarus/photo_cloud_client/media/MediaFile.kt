package ru.tbcarus.photo_cloud_client.media

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_files")
data class MediaFile(
    @PrimaryKey
    val id: Long,
    val mediaStoreId: Long,
    val remoteId: Long? = null, // ID файла на сервере, null до загрузки
    val uri: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,       // DATE_TAKEN из MediaStore (ms)
    val checksum: String?,          // SHA-256, null пока не посчитан
    val status: MediaFileStatus = MediaFileStatus.PENDING
)

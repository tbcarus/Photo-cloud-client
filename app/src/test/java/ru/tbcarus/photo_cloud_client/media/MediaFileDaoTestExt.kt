package ru.tbcarus.photo_cloud_client.media

import kotlinx.coroutines.flow.first

/** Текущая строка Room по MediaStore ID; null — строки нет. */
suspend fun MediaFileDao.find(id: Long): MediaFile? = getAll().first().firstOrNull { it.mediaStoreId == id }

suspend fun MediaFileDao.single(id: Long): MediaFile = requireNotNull(find(id)) { "Нет строки $id" }

/** Запись scan для строки [file] с новыми size и lastModified (остальные сведения прежние). */
suspend fun MediaFileDao.applyScan(file: MediaFile, size: Long, lastModified: Long, displayName: String = file.displayName) =
    applyScannedMetadata(
        file.mediaStoreId, displayName, file.relativePath, file.mimeType,
        size, file.createdAt, lastModified, file.uri
    )

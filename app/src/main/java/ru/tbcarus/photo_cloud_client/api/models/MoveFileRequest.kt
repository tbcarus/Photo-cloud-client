package ru.tbcarus.photo_cloud_client.api.models

/** Тело `POST /api/v1/files/{id}/move`: ID файла сохраняется. */
data class MoveFileRequest(val targetFolderId: Long)

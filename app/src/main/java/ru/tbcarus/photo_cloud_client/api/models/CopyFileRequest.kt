package ru.tbcarus.photo_cloud_client.api.models

/** Тело `POST /api/v1/files/{id}/copy`: копия получает новый ID и имя [originalName] без автоматических суффиксов. */
data class CopyFileRequest(val targetFolderId: Long, val originalName: String)

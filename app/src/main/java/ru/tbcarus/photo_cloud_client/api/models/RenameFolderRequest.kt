package ru.tbcarus.photo_cloud_client.api.models

/** Тело `PATCH /api/v1/folders/{id}`. Ручное переименование backupSource не передаёт. */
data class RenameFolderRequest(val name: String)

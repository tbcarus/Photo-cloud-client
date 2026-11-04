package ru.tbcarus.photo_cloud_client.api.models

/** Тело `POST /api/v1/folders/{id}/move`: ID папки и вложенных объектов сохраняются. */
data class MoveFolderRequest(val targetParentId: Long)

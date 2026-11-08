package ru.tbcarus.photo_cloud_client.api

object ApiPaths {
    const val API_V1 = "api/v1"

    const val TEST = "$API_V1/test"
    const val TEST_AUTH = "$API_V1/test/auth"

    const val AUTH_REGISTER = "$API_V1/auth/register"
    const val AUTH_LOGIN = "$API_V1/auth/login"
    const val AUTH_REFRESH_TOKEN = "$API_V1/auth/refresh-token"
    const val AUTH_LOGOUT = "$API_V1/auth/logout"

    const val FILES = "$API_V1/files"
    const val FILES_UPLOAD = "$FILES/upload"
    const val FILES_CHECKSUMS_EXISTS = "$FILES/checksums/exists"
    const val FILES_DOWNLOAD = "$FILES/{id}/download"
    const val FILE_ID = "$FILES/{id}"
    const val FILE_MOVE = "$FILES/{id}/move"
    const val FILE_COPY = "$FILES/{id}/copy"

    const val FOLDERS = "$API_V1/folders"
    const val FOLDERS_ROOT = "$FOLDERS/root"
    const val FOLDERS_CAMERA = "$FOLDERS/camera"
    const val FOLDERS_BACKUP = "$FOLDERS/backup"
    const val FOLDERS_CHILDREN = "$FOLDERS/{id}/children"
    const val FOLDER_ID = "$FOLDERS/{id}"
    const val FOLDER_MOVE = "$FOLDERS/{id}/move"
    const val FOLDER_BACKUP_SOURCE = "$FOLDERS/{id}/backup"

    const val SHARED_WITH_ME = "$API_V1/shared-with-me"
    const val SHARED_WITH_ME_ID = "$SHARED_WITH_ME/{shareId}"

    const val SHARES = "$API_V1/shares"
    const val SHARES_USERS = "$SHARES/users"
    const val SHARE_ID = "$SHARES/{id}"
}

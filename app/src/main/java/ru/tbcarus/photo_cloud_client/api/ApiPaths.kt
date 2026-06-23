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
    const val FILES_CHECKSUMS_EXISTS = "$FILES/checksums/exists"

    const val FOLDERS = "$API_V1/folders"
    const val FOLDERS_ROOT = "$FOLDERS/root"
    const val FOLDERS_CHILDREN = "$FOLDERS/{id}/children"
}

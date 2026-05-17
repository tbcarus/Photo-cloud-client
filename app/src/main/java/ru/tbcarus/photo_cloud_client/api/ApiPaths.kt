package ru.tbcarus.photo_cloud_client.api

object ApiPaths {
    const val API_V1 = "api/v1"

    const val TEST = "$API_V1/test"
    const val TEST_AUTH = "$API_V1/test/auth"

    const val AUTH_REGISTER = "$API_V1/auth/register"
    const val AUTH_LOGIN = "$API_V1/auth/login"
    const val AUTH_REFRESH_TOKEN = "$API_V1/auth/refresh-token"
    const val AUTH_LOGOUT = "$API_V1/auth/logout"
}

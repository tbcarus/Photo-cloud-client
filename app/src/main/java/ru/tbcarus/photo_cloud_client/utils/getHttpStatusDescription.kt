package ru.tbcarus.photo_cloud_client.utils

fun getHttpStatusDescription(code: Int): String {
    return when (code) {
        400 -> "$code Bad Request"
        401 -> "$code Unauthorized"
        403 -> "$code Forbidden"
        404 -> "$code Not Found"
        500 -> "$code Internal Server Error"
        502 -> "$code Bad Gateway"
        503 -> "$code Service Unavailable"
        else -> "HTTP $code"
    }
}
package ru.tbcarus.photo_cloud_client.utils

fun isValidIpAddress(ip: String): Boolean {
    val regex = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(\\.|$)){4}")
    return regex.matches(ip)
}
package ru.tbcarus.photo_cloud_client.core.server

import ru.tbcarus.photo_cloud_client.utils.isValidIpAddress

/**
 * Единое правило адреса Server для Network UI и фоновой синхронизации.
 * null — адрес не задан или некорректен: обращаться по нему нельзя.
 */
fun buildBaseUrl(ip: String?, port: String?): String? {
    val trimmedIp = ip?.trim().orEmpty()
    val trimmedPort = port?.trim().orEmpty()
    if (!isValidIpAddress(trimmedIp)) return null
    // Только цифры: toIntOrNull пропустил бы знак "+80".
    val portNumber = trimmedPort.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    if (portNumber == null || portNumber !in 1..65535) return null
    return "http://$trimmedIp:$portNumber/"
}

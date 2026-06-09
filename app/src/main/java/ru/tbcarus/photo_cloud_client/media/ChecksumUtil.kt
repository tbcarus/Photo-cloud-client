package ru.tbcarus.photo_cloud_client.media

import java.io.InputStream
import java.security.MessageDigest

// Чистый утилитарный объект: работает только с байтовым потоком.
// Не знает об Android, Room или ContentResolver.
object ChecksumUtil {

    private const val BUFFER_SIZE = 8 * 1024

    /**
     * Считает SHA-256 переданного потока и возвращает lowercase hex строку длиной 64 символа.
     * Поток НЕ открывается и НЕ закрывается внутри — управление ресурсом остаётся на вызывающем коде.
     * Бросает [java.io.IOException], если чтение потока прерывается.
     */
    fun sha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

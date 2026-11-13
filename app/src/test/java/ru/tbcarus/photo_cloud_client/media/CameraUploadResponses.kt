package ru.tbcarus.photo_cloud_client.media

import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.sha256

/**
 * Тело ответа Server на upload Camera, как FileItemDto: id и SHA-256 байтов part «file», реально принятых
 * в этом запросе (Server считает checksum по полученному содержимому, а не берёт его у клиента).
 */
fun uploadResponseJson(id: Long, multipartBody: ByteArray): String =
    """{"id":$id,"checksum":"${uploadedChecksum(multipartBody)}"}"""

/** SHA-256 содержимого part «file» multipart-запроса upload. */
fun uploadedChecksum(multipartBody: ByteArray): String = sha256(uploadedFileBytes(multipartBody))

/** Байты part «file»: между заголовками part и закрывающим boundary. */
fun uploadedFileBytes(multipartBody: ByteArray): ByteArray {
    val body = multipartBody.toString(Charsets.ISO_8859_1)
    val start = body.indexOf("\r\n\r\n", body.indexOf("name=\"file\"")) + 4
    return body.substring(start, body.indexOf("\r\n--", start)).toByteArray(Charsets.ISO_8859_1)
}

package ru.tbcarus.photo_cloud_client.library.thumbnail

import ru.tbcarus.photo_cloud_client.api.ApiPaths
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.library.LibrarySession
import java.security.MessageDigest

/**
 * Сервер и пользователь, к которым относятся миниатюры. [id] — хеш адреса и subject: в ключах кэша нет ни адреса,
 * ни email, ни токенов.
 */
data class ThumbnailScope(val baseUrl: String, val id: String) {
    companion object {
        fun of(session: LibrarySession): ThumbnailScope? {
            val user = session.user ?: return null
            if (session.baseUrl.isBlank()) return null
            return ThumbnailScope(session.baseUrl, sha256(session.baseUrl + "\n" + user).take(SCOPE_ID_LENGTH))
        }

        private const val SCOPE_ID_LENGTH = 24
    }
}

/** Миниатюра файла Library: модель запроса Coil. Ключ кэша различает сервер, пользователя, файл и содержимое. */
data class Thumbnail(val scope: ThumbnailScope, val fileId: Long, val checksum: String) {

    val url: String get() = scope.baseUrl + ApiPaths.FILES + "/" + fileId + "/thumbnail"

    val cacheKey: String get() = "thumbnail:v$VARIANT:${scope.id}:$fileId:$checksum"

    companion object {
        // Меняется вместе с серверным вариантом миниатюры (размер, алгоритм): прежние записи кэша не подходят.
        const val VARIANT = 1

        // Тип — по MIME, определённому Server по содержимому, а не по имени файла.
        private val SUPPORTED_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        fun isSupported(mimeType: String?): Boolean = mimeType?.lowercase() in SUPPORTED_TYPES

        /** null — миниатюру не запрашивать: тип не поддерживается, нет сессии, ID или checksum. */
        fun of(file: FileItemDto, scope: ThumbnailScope?): Thumbnail? {
            if (scope == null || !isSupported(file.mimeType)) return null
            val id = file.id?.takeIf { it > 0 } ?: return null
            val checksum = file.checksum?.takeIf { it.isNotBlank() } ?: return null
            return Thumbnail(scope, id, checksum)
        }
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

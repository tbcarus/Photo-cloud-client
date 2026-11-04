package ru.tbcarus.photo_cloud_client.library.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.library.LibrarySession

class ThumbnailTest {

    private val server = "http://10.0.2.2:8080/"
    private val scope = ThumbnailScope.of(LibrarySession(server, "user@test.local"))!!

    private fun file(mimeType: String?, id: Long? = 5, checksum: String? = "abc") =
        FileItemDto(id = id, mimeType = mimeType, checksum = checksum, originalFilename = "photo.heic")

    @Test
    fun onlyJpegPngAndWebpAreRequestedByServerMime() {
        listOf("image/jpeg", "image/png", "image/webp", "IMAGE/JPEG").forEach {
            assertNotNull(it, Thumbnail.of(file(it), scope))
        }
        // Имя файла роли не играет: .heic с MIME JPEG подходит, а HEIC с именем .jpg — нет.
        listOf("image/heic", "image/heif", "image/gif", "image/avif", "video/mp4", "application/pdf", null).forEach {
            assertNull(it, Thumbnail.of(file(it), scope))
        }
    }

    @Test
    fun noThumbnailWithoutSessionIdOrChecksum() {
        assertNull(Thumbnail.of(file("image/jpeg"), null))
        assertNull(ThumbnailScope.of(LibrarySession(server, null)))
        assertNull(ThumbnailScope.of(LibrarySession("", "user@test.local")))
        assertNull(Thumbnail.of(file("image/jpeg", id = null), scope))
        assertNull(Thumbnail.of(file("image/jpeg", checksum = ""), scope))
    }

    @Test
    fun urlPointsToThumbnailEndpointWithoutSecrets() {
        val thumbnail = Thumbnail.of(file("image/jpeg"), scope)!!

        assertEquals("http://10.0.2.2:8080/api/v1/files/5/thumbnail", thumbnail.url)
        assertFalse(thumbnail.url.contains("?"))
    }

    @Test
    fun cacheKeySeparatesServersUsersFilesAndContent() {
        val base = Thumbnail.of(file("image/jpeg"), scope)!!
        val otherUser = Thumbnail.of(file("image/jpeg"), ThumbnailScope.of(LibrarySession(server, "other@test.local")))!!
        val otherServer = Thumbnail.of(file("image/jpeg"), ThumbnailScope.of(LibrarySession("http://10.0.2.2:18081/", "user@test.local")))!!
        val otherFile = Thumbnail.of(file("image/jpeg", id = 6), scope)!!
        val newContent = Thumbnail.of(file("image/jpeg", checksum = "def"), scope)!!

        val keys = listOf(base, otherUser, otherServer, otherFile, newContent).map { it.cacheKey }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(base.cacheKey, Thumbnail.of(file("image/jpeg"), scope)!!.cacheKey)
        assertTrue(base.cacheKey.startsWith("thumbnail:v${Thumbnail.VARIANT}:"))
        // В ключе нет ни адреса сервера, ни email: только их хеш.
        assertFalse(base.cacheKey.contains("10.0.2.2"))
        assertFalse(base.cacheKey.contains("user@test.local"))
        assertNotEquals(base.scope, otherUser.scope)
    }
}

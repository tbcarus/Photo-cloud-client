package ru.tbcarus.photo_cloud_client.media

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

/**
 * Выбор источника байтов фотографии: оригинал при ACCESS_MEDIA_LOCATION, иначе обычный поток MediaStore.
 * Обычный поток FakeMediaStore отдаёт «затёртую» версию, оригинал — отдельные байты с геоданными.
 */
@RunWith(RobolectricTestRunner::class)
// MediaStore.setRequireOriginal есть в android-all с API 29; тесты без sdk идут на минимальном API Robolectric.
@Config(application = Application::class, sdk = [34])
class MediaStreamOpenerTest {

    // Android затирает геоданные на месте: длина и SIZE в MediaStore у обеих версий одинаковы.
    private val original = "jpeg|gps:55.75N,37.61E".toByteArray()
    private val redacted = "jpeg|gps:00.00-,00.00-".toByteArray()

    private lateinit var context: Application
    private lateinit var media: FakeMediaStore
    private lateinit var opener: MediaStreamOpener
    private lateinit var image: FakeMediaStore.Image

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        media = FakeMediaStore.install(context)
        opener = MediaStreamOpener(context)
        image = media.add(
            FakeMediaStore.Image(id = 1, bytes = redacted, dateModifiedSec = 1_700_000_000L, originalBytes = original)
        )
        shadowOf(context).grantPermissions(mediaReadPermission)
    }

    @Test
    fun withMediaLocationReadsOriginalThroughRequireOriginal() {
        grantLocation()

        assertArrayEquals(original, read())
        assertEquals(1, media.originalOpenCount(image.id))
        assertEquals(0, media.openCount(image.id))
    }

    @Test
    fun withoutMediaLocationReadsRegularStream() {
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)

        assertArrayEquals(redacted, read())
        assertEquals(0, media.originalOpenCount(image.id))
        assertEquals(1, media.openCount(image.id))
    }

    @Test
    fun refusedOriginalFallsBackToRegularStream() {
        // Разрешение отозвано между проверкой и открытием: Android отвечает UnsupportedOperationException.
        grantLocation()
        media.originalFailure = { UnsupportedOperationException("Caller must hold ACCESS_MEDIA_LOCATION") }

        assertArrayEquals(redacted, read())
        assertEquals(1, media.originalOpenCount(image.id))
        assertEquals(1, media.openCount(image.id))
    }

    @Test
    fun securityExceptionForOriginalFallsBackToRegularStream() {
        grantLocation()
        media.originalFailure = { SecurityException("original denied") }

        assertArrayEquals(redacted, read())
    }

    @Test(expected = SecurityException::class)
    fun lostPhotoAccessIsNotMaskedByFallback() {
        // Отказ в самом фото повторяется при обычном открытии и доходит до вызывающего кода.
        grantLocation()
        media.originalFailure = { SecurityException("no photo access") }
        image.openFailure = { SecurityException("no photo access") }

        read()
    }

    @Test(expected = FileNotFoundException::class)
    fun missingFileWithMediaLocationIsNotMasked() {
        grantLocation()
        media.remove(image.id)

        read()
    }

    @Test
    fun requestBodyReopensSameSourceOnEveryWrite() {
        // OkHttp может отправить тело повторно (после refresh) — каждый раз читается тот же оригинал целиком.
        grantLocation()
        val body = ContentUriRequestBody(opener, image.uri, "image/jpeg", image.size)

        val first = Buffer().also { body.writeTo(it) }.readByteArray()
        val second = Buffer().also { body.writeTo(it) }.readByteArray()

        assertArrayEquals(original, first)
        assertArrayEquals(original, second)
        assertEquals(2, media.originalOpenCount(image.id))
        assertEquals(original.size.toLong(), body.contentLength())
    }

    private fun grantLocation() = shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)

    private fun read(): ByteArray = requireNotNull(opener.open(image.uri)).use { it.readBytes() }
}

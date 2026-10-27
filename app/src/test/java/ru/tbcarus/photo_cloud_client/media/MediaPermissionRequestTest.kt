package ru.tbcarus.photo_cloud_client.media

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Какие разрешения запрашивает экран фото: геоданные необязательны, отдельно запрашиваются не больше одного раза. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MediaPermissionRequestTest {

    private val location = Manifest.permission.ACCESS_MEDIA_LOCATION
    private val photos = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    @Test
    fun withoutPhotoAccessLocationJoinsTheExistingRequest() {
        assertArrayEquals(photos + location, request(hasMediaRead = false, hasLocation = false, asked = false))
        // Основной запрос при отказе повторяется, как и прежде; геоданные идут с ним, отдельного диалога нет.
        assertArrayEquals(photos + location, request(hasMediaRead = false, hasLocation = false, asked = true))
        assertArrayEquals(photos, request(hasMediaRead = false, hasLocation = true, asked = true))
    }

    @Test
    fun withPhotoAccessLocationIsRequestedOnlyOnce() {
        assertArrayEquals(arrayOf(location), request(hasMediaRead = true, hasLocation = false, asked = false))
        assertArrayEquals(emptyArray<String>(), request(hasMediaRead = true, hasLocation = false, asked = true))
        assertArrayEquals(emptyArray<String>(), request(hasMediaRead = true, hasLocation = true, asked = false))
    }

    @Test
    fun locationRequestFlagPersists() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(context.isMediaLocationRequested())

        context.markMediaLocationRequested()

        assertTrue(context.isMediaLocationRequested())
    }

    private fun request(hasMediaRead: Boolean, hasLocation: Boolean, asked: Boolean) =
        mediaPermissionsToRequest(hasMediaRead, hasLocation, asked)
}

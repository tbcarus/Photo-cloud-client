package ru.tbcarus.photo_cloud_client.media

import android.Manifest
import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CameraUploadSourceTest {
    private lateinit var context: Application
    private lateinit var media: FakeMediaStore
    private lateinit var image: FakeMediaStore.Image
    private lateinit var source: CameraUploadSource
    private lateinit var record: UploadSessionRecord

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        media = FakeMediaStore.install(context)
        image = media.add(FakeMediaStore.Image(1, "original".toByteArray(), 1_700_000_000L))
        shadowOf(context).grantPermissions(mediaReadPermission)
        source = CameraUploadSource(context, MediaStreamOpener(context))
        record = UploadSessionRecord(UUID.randomUUID().toString(), "http://server/", "user", "CAMERA", "1",
            image.uri.toString(), 7, "photo.jpg", image.size, image.lastModifiedMs, false, "a".repeat(64))
    }

    @Test fun sizeModificationAndOriginalModeInvalidateOldSession() {
        assertEquals(CameraSourceState.Current, source.state(record))
        image.bytes = "changed-size".toByteArray()
        assertEquals(CameraSourceState.Changed, source.state(record))
        image.bytes = "original".toByteArray()
        image.dateModifiedSec++
        assertEquals(CameraSourceState.Changed, source.state(record))
        image.dateModifiedSec--
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        assertEquals(CameraSourceState.Changed, source.state(record))
    }

    @Test fun missingIsDistinctFromTemporarilyUnavailable() {
        media.setStorageState(Environment.MEDIA_UNMOUNTED)
        assertEquals(CameraSourceState.Unavailable, source.state(record))
        media.setStorageState(Environment.MEDIA_MOUNTED)
        shadowOf(context).denyPermissions(mediaReadPermission)
        assertEquals(CameraSourceState.Unavailable, source.state(record))
        shadowOf(context).grantPermissions(mediaReadPermission)
        media.remove(image.id)
        assertEquals(CameraSourceState.Missing, source.state(record))
    }
}

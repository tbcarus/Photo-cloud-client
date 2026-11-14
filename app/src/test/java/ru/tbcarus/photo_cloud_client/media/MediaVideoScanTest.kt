package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class MediaVideoScanTest {
    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var store: FakeMediaStore
    private val dao get() = db.mediaFileDao()
    private val repository get() = MediaFileRepository(dao, MediaStoreRepository(context))

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = FakeMediaStore.install(context)
        shadowOf(context).grantPermissions(mediaReadPermission, videoReadPermission)
    }

    @After fun tearDown() = db.close()

    @Test fun cameraVideoAndImagesShareIndexWithoutIdCollisionAndKeepFifo() = runBlocking {
        val photo = store.add(FakeMediaStore.Image(7, "photo".toByteArray(), 1_700_000_000L))
        store.add(FakeMediaStore.Image(8, "picture".toByteArray(), 1_700_000_001L, "Pictures/"))
        store.add(FakeMediaStore.Image(9, "outside".toByteArray(), 1_700_000_002L, "Download/"))
        val video = store.add(video(7))
        store.add(video(10, "Movies/"))
        store.add(video(11, "DCIM/Camera/Subfolder/"))

        val result = repository.scanCameraMedia() as ScanOutcome.Success
        val rows = dao.getAll().first()
        assertTrue(result.result.complete)
        assertEquals(3, result.result.scanned)
        assertEquals(3, rows.size)
        assertEquals(setOf(photo.uri.toString(), video.uri.toString()), rows.filter { it.mediaStoreId == 7L }.map { it.uri }.toSet())
        assertEquals(listOf(photo.uri.toString(), video.uri.toString(), FakeMediaStore.uriOf(8).toString()),
            dao.getByStatusInIdOrder(MediaFileStatus.PENDING, 10).map { it.uri })
        assertEquals(listOf(video.uri.toString()),
            dao.getReadableByStatusInIdOrder(MediaFileStatus.PENDING, 10, false, true).map { it.uri })
    }

    @Test fun changedAndDeletedVideoAreHandledWithinVideoCollection() = runBlocking {
        val first = store.add(video(1))
        val second = store.add(video(2))
        repository.scanCameraMedia()
        dao.updateStatusIfUnchanged(first.uri.toString(), MediaFileStatus.PENDING,
            MediaFileStatus.SYNCED, null, first.size, first.lastModifiedMs)
        first.bytes = "changed".toByteArray()
        first.dateModifiedSec++
        store.removeVideo(second.id)

        val result = repository.scanCameraMedia() as ScanOutcome.Success
        assertEquals(1, result.result.deletedStale)
        assertNull(dao.getByUri(second.uri.toString()))
        assertEquals(first.size, dao.getByUri(first.uri.toString())?.size)
        assertEquals(MediaFileStatus.PENDING, dao.getByUri(first.uri.toString())?.status)
    }

    @Test fun missingPermissionAndIncompleteVideoSnapshotNeverDeleteOtherCollection() = runBlocking {
        val image = store.add(FakeMediaStore.Image(1, "photo".toByteArray(), 1_700_000_000L))
        val video = store.add(video(1))
        repository.scanCameraMedia()
        store.removeVideo(video.id)
        shadowOf(context).denyPermissions(videoReadPermission)
        val imageOnly = repository.scanCameraMedia() as ScanOutcome.Success
        assertFalse(imageOnly.result.complete)
        assertEquals(0, imageOnly.result.deletedStale)
        assertTrue(dao.getByUri(video.uri.toString()) != null)

        shadowOf(context).grantPermissions(videoReadPermission)
        shadowOf(context).denyPermissions(mediaReadPermission)
        store.remove(image.id)
        val videoOnly = repository.scanCameraMedia() as ScanOutcome.Success
        assertFalse(videoOnly.result.complete)
        assertTrue(dao.getByUri(image.uri.toString()) != null)
    }

    @Test fun incompleteVideoScanDoesNotTreatMissingVideoAsDeleted() = runBlocking {
        val video = store.add(video(3))
        repository.scanCameraMedia()
        store.removeVideo(video.id)
        store.brokenRows = 1

        val result = repository.scanCameraMedia() as ScanOutcome.Success

        assertFalse(result.result.complete)
        assertEquals(0, result.result.deletedStale)
        assertTrue(dao.getByUri(video.uri.toString()) != null)
    }

    private fun video(id: Long, path: String = "DCIM/Camera/") =
        FakeMediaStore.Image(id, "video-$id".toByteArray(), 1_700_000_000L + id,
            path, "video/mp4", collection = MediaCollection.VIDEO)
}

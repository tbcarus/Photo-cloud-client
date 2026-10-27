package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.sha256

/**
 * Область сканирования: DCIM/ и (временно, A4.3) Pictures/ с вложенными каталогами — один общий снимок.
 * Тестовый MediaStore применяет фильтр RELATIVE_PATH из запроса MediaStoreRepository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaScanDirectoriesTest {

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private lateinit var repository: MediaFileRepository

    private val dao get() = db.mediaFileDao()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        media = FakeMediaStore.install(context)
        shadowOf(context).grantPermissions(mediaReadPermission)
        repository = MediaFileRepository(dao, MediaStoreRepository(context))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun photosInDcimPicturesAndNestedDirectoriesAreDiscoveredOnce() = runBlocking {
        val paths = listOf(
            "DCIM/", "DCIM/Camera/", "DCIM/Camera/Burst/",
            "Pictures/", "Pictures/Screenshots/", "Pictures/Telegram/Saved/"
        )
        paths.forEachIndexed { i, path -> media.add(image(i + 1L, path)) }

        val result = repository.scanImages() as ScanOutcome.Success
        repository.scanImages()

        // Один запрос на scan — один общий снимок; повторный scan не создаёт дублей.
        assertEquals(2, media.queryCount)
        assertEquals(paths.size, result.result.scanned)
        val rows = dao.getAll().first()
        assertEquals(paths.size, rows.size)
        assertEquals(paths.toSet(), rows.map { it.relativePath }.toSet())
        assertEquals(rows.size, rows.map { it.mediaStoreId }.toSet().size)
    }

    @Test
    fun foreignPathsAreNotDiscovered() = runBlocking {
        val included = media.add(image(1, "DCIM/Camera/"))
        val foreign = listOf(
            "Download/", "Movies/", "Documents/Scans/", "Android/media/DCIM/",
            "MyDCIM/", "DCIMx/", "Pictures2/", "Music/Pictures/"
        )
        foreign.forEachIndexed { i, path -> media.add(image(100L + i, path)) }

        repository.scanImages()

        val rows = dao.getAll().first()
        assertEquals(listOf(included.id), rows.map { it.mediaStoreId })
    }

    @Test
    fun picturesPhotoGoesThroughSameLocalPipeline() = runBlocking {
        val picture = media.add(image(1, "Pictures/Screenshots/"))

        repository.scanImages()
        ChecksumRepository(dao, MediaStreamOpener(context), context).computePendingChecksums()

        val row = dao.single(picture.id)
        assertEquals(MediaFileStatus.CHECKSUM_READY, row.status)
        assertEquals(sha256(picture.bytes), row.checksum)
    }

    @Test
    fun deletionIsDetectedInCommonSnapshotOfBothDirectories() = runBlocking {
        val dcim = media.add(image(1, "DCIM/Camera/"))
        val kept = media.add(image(2, "Pictures/"))
        val deleted = media.add(image(3, "Pictures/Saved/"))
        repository.scanImages()

        media.remove(deleted.id)
        val result = repository.scanImages() as ScanOutcome.Success

        assertEquals(1, result.result.deletedStale)
        assertNull(dao.find(deleted.id))
        assertEquals(setOf(dcim.id, kept.id), dao.getAll().first().map { it.mediaStoreId }.toSet())
    }

    @Test
    fun photoMovedOutOfAllowedDirectoriesIsRemovedOnlyFromRoom() = runBlocking {
        val stays = media.add(image(1, "DCIM/Camera/"))
        media.add(image(2, "Pictures/"))
        repository.scanImages()

        // Тот же MediaStore ID перемещён в Download/ — он вне области scan.
        media.remove(2)
        media.add(image(2, "Download/"))
        repository.scanImages()

        assertEquals(listOf(stays.id), dao.getAll().first().map { it.mediaStoreId })
    }

    @Test
    fun unreliableSnapshotKeepsRowsOfBothDirectories() = runBlocking {
        media.add(image(1, "DCIM/Camera/"))
        val picture = media.add(image(2, "Pictures/"))
        repository.scanImages()

        media.remove(picture.id)
        media.brokenRows = 1
        repository.scanImages()

        assertEquals(2, dao.getAll().first().size)
    }

    private fun image(id: Long, path: String) = FakeMediaStore.Image(
        id = id,
        bytes = "photo-$id".toByteArray(),
        dateModifiedSec = 1_700_000_000L + id,
        relativePath = path
    )
}

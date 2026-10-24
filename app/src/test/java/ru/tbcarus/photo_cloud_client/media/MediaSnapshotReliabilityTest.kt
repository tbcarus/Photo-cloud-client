package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.os.Environment
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.row
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.sha256

/**
 * Удаление строк Room только по достоверному снимку MediaStore. Пауза перед повторной проверкой
 * массового исчезновения проходит в виртуальном времени runTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaSnapshotReliabilityTest {

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
    fun singleDeletedPhotoIsRemovedWithoutRecheck() = runTest {
        val images = syncedLibrary(20)
        media.remove(images[0].id)

        val result = repository.scanImages() as ScanOutcome.Success

        assertEquals(1, result.result.deletedStale)
        assertEquals(19, rows())
        assertEquals(1, media.queryCount)
    }

    @Test
    fun temporaryDisappearanceOfWholeLibraryKeepsRows() = runTest {
        val images = syncedLibrary(20)
        images.forEach { media.remove(it.id) }
        // К повторной проверке библиотека снова видна: первый снимок был сбоем.
        media.beforeQuery = { n -> if (n == 2) images.forEach { media.add(it) } }

        val result = repository.scanImages() as ScanOutcome.Success

        assertEquals(0, result.result.deletedStale)
        assertEquals(20, rows())
        assertEquals(2, media.queryCount)
    }

    @Test
    fun confirmedDeletionOfWholeLibraryIsApplied() = runTest {
        val images = syncedLibrary(20)
        images.forEach { media.remove(it.id) }

        val result = repository.scanImages() as ScanOutcome.Success

        assertEquals(20, result.result.deletedStale)
        assertEquals(0, rows())
        assertEquals(2, media.queryCount)
    }

    @Test
    fun confirmedDeletionOfMostPhotosRemovesOnlyMissingRows() = runTest {
        val images = syncedLibrary(20)
        images.take(12).forEach { media.remove(it.id) }

        repository.scanImages()

        assertEquals(8, rows())
        assertTrue(images.drop(12).all { dao.find(it.id) != null })
    }

    @Test
    fun failedRecheckKeepsRows() = runTest {
        val images = syncedLibrary(20)
        images.forEach { media.remove(it.id) }
        media.beforeQuery = { n -> if (n == 2) media.failQuery = true }

        repository.scanImages()

        assertEquals(20, rows())
    }

    @Test
    fun partiallyReadRecheckKeepsRows() = runTest {
        val images = syncedLibrary(20)
        images.forEach { media.remove(it.id) }
        media.beforeQuery = { n -> if (n == 2) media.brokenRows = 1 }

        repository.scanImages()

        assertEquals(20, rows())
    }

    @Test
    fun unmountedStorageKeepsRows() = runTest {
        val images = syncedLibrary(20)
        media.remove(images[0].id)
        media.setStorageState(Environment.MEDIA_UNMOUNTED)

        val result = repository.scanImages() as ScanOutcome.Success

        assertEquals(0, result.result.deletedStale)
        assertEquals(20, rows())
    }

    @Test
    fun partlyUnreadableSnapshotKeepsMissingRows() = runTest {
        val images = syncedLibrary(20)
        media.remove(images[0].id)
        media.brokenRows = 1

        repository.scanImages()

        assertEquals(20, rows())
    }

    @Test
    fun mediaStoreErrorKeepsRows() = runTest {
        syncedLibrary(20)
        media.failQuery = true

        assertTrue(repository.scanImages() is ScanOutcome.Error)
        assertEquals(20, rows())
    }

    @Test
    fun missingFullAccessKeepsRows() = runTest {
        val images = syncedLibrary(20)
        images.forEach { media.remove(it.id) }
        shadowOf(context).denyPermissions(mediaReadPermission)

        assertEquals(ScanOutcome.PermissionDenied, repository.scanImages())
        assertEquals(20, rows())
        assertEquals(0, media.queryCount)
    }

    /** [count] фотографий в MediaStore и такие же SYNCED-строки в Room. */
    private suspend fun syncedLibrary(count: Int): List<FakeMediaStore.Image> =
        (1L..count).map { id ->
            val image = media.add(FakeMediaStore.Image(id, "photo-$id".toByteArray(), 1_700_000_000L + id))
            dao.insert(row(image, MediaFileStatus.SYNCED, checksum = sha256(image.bytes), serverFileId = 100 + id))
            image
        }

    private suspend fun rows(): Int = dao.getAll().first().size
}

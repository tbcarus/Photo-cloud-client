package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Запись снимка MediaStore в Room порциями по [SyncConfig.SCAN_WRITE_BATCH_SIZE] (A8.1)
 * и выборка очереди хеширования FIFO по mediaStoreId.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaScanBatchTest {

    private companion object {
        // Больше двух порций записи, с неполной последней.
        const val LIBRARY_SIZE = 1_200
    }

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private val dao get() = db.mediaFileDao()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        media = FakeMediaStore.install(context)
        shadowOf(context).grantPermissions(mediaReadPermission)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun libraryLargerThanOneWriteBatchKeepsNewChangedUnchangedAndDeletedRules() = runBlocking {
        val images = (1L..LIBRARY_SIZE).map { media.add(image(it)) }
        val repository = MediaFileRepository(dao, MediaStoreRepository(context))
        // Уже синхронизированные фото во всех порциях и одна строка, которой больше нет на устройстве.
        val synced = listOf(images[0], images[700], images[1_150])
        synced.forEach { dao.insert(row(it, MediaFileStatus.SYNCED, sha256(it.bytes), serverFileId = it.id)) }
        val changed = images[701]
        dao.insert(row(changed, MediaFileStatus.SYNCED, sha256(changed.bytes), serverFileId = 7))
        changed.bytes = "changed on device".toByteArray()
        changed.dateModifiedSec += 60
        val gone = FakeMediaStore.Image(id = 99_999, bytes = "gone".toByteArray(), dateModifiedSec = 1_700_000_000L)
        dao.insert(row(gone, MediaFileStatus.SYNCED, sha256(gone.bytes), serverFileId = 9))

        val result = repository.scanImages() as ScanOutcome.Success

        assertEquals(LIBRARY_SIZE, result.result.scanned)
        assertEquals(1, result.result.deletedStale)
        val rows = dao.getAll().first().associateBy { it.mediaStoreId }
        assertEquals(LIBRARY_SIZE, rows.size)
        assertNull(rows[gone.id])
        // Новые — в очереди хеширования.
        assertEquals(MediaFileStatus.PENDING, rows.getValue(images[1].id).status)
        assertEquals(MediaFileStatus.PENDING, rows.getValue(images[LIBRARY_SIZE - 1].id).status)
        // Неизменённые сохраняют статус и checksum в любой порции.
        synced.forEach { image ->
            val row = rows.getValue(image.id)
            assertEquals(MediaFileStatus.SYNCED, row.status)
            assertEquals(sha256(image.bytes), row.checksum)
        }
        // Изменённое фото возвращается в обработку без старого checksum и serverFileId.
        val reset = rows.getValue(changed.id)
        assertEquals(MediaFileStatus.PENDING, reset.status)
        assertNull(reset.checksum)
        assertNull(reset.serverFileId)
        assertEquals(changed.size, reset.size)
    }

    @Test
    fun scanInterruptedBetweenWriteBatchesDeletesNothingAndNextScanCompletesIndex() = runBlocking {
        (1L..LIBRARY_SIZE).forEach { media.add(image(it)) }
        val gone = FakeMediaStore.Image(id = 99_999, bytes = "gone".toByteArray(), dateModifiedSec = 1_700_000_000L)
        dao.insert(row(gone, MediaFileStatus.SYNCED, sha256(gone.bytes), serverFileId = 9))
        // Сбой на второй порции — как завершение процесса посередине записи снимка.
        val failingDao = object : MediaFileDao by dao {
            var batches = 0
            override suspend fun applyScanBatch(files: List<MediaFile>) {
                if (++batches == 2) error("прервано посередине scan")
                dao.applyScanBatch(files)
            }
        }

        val interrupted = MediaFileRepository(failingDao, MediaStoreRepository(context)).scanImages()

        assertTrue(interrupted is ScanOutcome.Error)
        // Первая порция применена целиком, остальные — нет; отсутствующая строка не удалена.
        assertEquals(SyncConfig.SCAN_WRITE_BATCH_SIZE + 1, dao.getAllMediaStoreIds().size)
        assertNotNull(dao.find(gone.id))

        val completed = MediaFileRepository(dao, MediaStoreRepository(context)).scanImages() as ScanOutcome.Success

        assertEquals(1, completed.result.deletedStale)
        assertEquals(LIBRARY_SIZE, dao.getAllMediaStoreIds().size)
        assertNull(dao.find(gone.id))
        assertTrue(dao.getAll().first().all { it.status == MediaFileStatus.PENDING })
    }

    @Test
    fun checksumQueueIsFifoByMediaStoreIdIgnoringCaptureDate() = runBlocking {
        // Чем больше ID, тем старше снимок: порядок по дате дал бы обратный результат.
        listOf(30L, 10L, 20L).forEach { id ->
            val image = FakeMediaStore.Image(id = id, bytes = "photo-$id".toByteArray(), dateModifiedSec = 2_000_000_000L - id)
            dao.insert(row(image, MediaFileStatus.PENDING))
        }
        dao.insert(row(image(5), MediaFileStatus.SYNCED, checksum = "x"))

        assertEquals(listOf(10L, 20L, 30L), dao.getByStatusInIdOrder(MediaFileStatus.PENDING, 10).map { it.mediaStoreId })
        assertEquals(listOf(10L, 20L), dao.getByStatusInIdOrder(MediaFileStatus.PENDING, 2).map { it.mediaStoreId })
        assertEquals(3, dao.countByStatus(MediaFileStatus.PENDING))
    }

    private fun image(id: Long) =
        FakeMediaStore.Image(id = id, bytes = "photo-$id".toByteArray(), dateModifiedSec = 1_700_000_000L + id)
}

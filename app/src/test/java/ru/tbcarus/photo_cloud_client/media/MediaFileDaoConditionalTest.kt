package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase

/** Условные переходы DAO: поздний результат стадии не перезаписывает строку новой версии файла. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaFileDaoConditionalTest {

    private lateinit var db: AppDatabase
    private val dao get() = db.mediaFileDao()

    private val original = MediaFile(
        mediaStoreId = 1,
        uri = "content://media/external/images/media/1",
        displayName = "IMG_1.jpg",
        relativePath = "DCIM/Camera/",
        mimeType = "image/jpeg",
        size = 100,
        createdAt = 1_000,
        lastModified = 1_000,
        checksum = "a".repeat(64),
        status = MediaFileStatus.UPLOADING
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun uploadResultForUnchangedRowIsApplied() = runBlocking {
        dao.insert(original)

        val applied = dao.markUploadedIfUnchanged(1, 55, original.checksum, original.size, original.lastModified)

        assertEquals(1, applied)
        assertEquals(MediaFileStatus.SYNCED, dao.single(1).status)
        assertEquals(55L, dao.single(1).serverFileId)
    }

    @Test
    fun uploadResultForResetRowIsDiscarded() = runBlocking {
        dao.insert(original)
        dao.applyScan(original, size = 200, lastModified = 2_000)

        val applied = dao.markUploadedIfUnchanged(1, 55, original.checksum, original.size, original.lastModified)

        assertEquals(0, applied)
        val file = dao.single(1)
        assertEquals(MediaFileStatus.PENDING, file.status)
        assertNull(file.serverFileId)
    }

    @Test
    fun transitionRequiresSameChecksumAndMetadata() = runBlocking {
        val ready = original.copy(status = MediaFileStatus.CHECKSUM_READY)
        dao.insert(ready)

        // Другая версия: тот же статус, но иной checksum или метаданные.
        assertEquals(0, dao.updateStatusIfUnchanged(ready.copy(checksum = "b".repeat(64)), MediaFileStatus.CHECKSUM_READY, MediaFileStatus.SYNCED))
        assertEquals(0, dao.updateStatusIfUnchanged(ready.copy(size = 101), MediaFileStatus.CHECKSUM_READY, MediaFileStatus.SYNCED))
        assertEquals(0, dao.updateStatusIfUnchanged(ready.copy(lastModified = 1_001), MediaFileStatus.CHECKSUM_READY, MediaFileStatus.SYNCED))
        // Другой ожидаемый статус.
        assertEquals(0, dao.updateStatusIfUnchanged(ready, MediaFileStatus.PENDING_UPLOAD, MediaFileStatus.SYNCED))
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(1).status)

        assertEquals(1, dao.updateStatusIfUnchanged(ready, MediaFileStatus.CHECKSUM_READY, MediaFileStatus.PENDING_UPLOAD))
        assertEquals(MediaFileStatus.PENDING_UPLOAD, dao.single(1).status)
    }

    @Test
    fun hashResultForRowResetDuringHashingIsDiscarded() = runBlocking {
        val hashing = original.copy(status = MediaFileStatus.HASHING, checksum = null)
        dao.insert(hashing)
        dao.applyScan(hashing, size = 200, lastModified = 2_000)

        assertEquals(0, dao.updateChecksumIfUnchanged(1, "c".repeat(64), hashing.size, hashing.lastModified))
        assertEquals(0, dao.markFailedIfUnchanged(hashing, MediaFileStatus.HASHING, FailureReason.CHECKSUM_IO))
        val file = dao.single(1)
        assertEquals(MediaFileStatus.PENDING, file.status)
        assertNull(file.checksum)
        assertNull(file.failureReason)
    }

    @Test
    fun scanOfChangedFileUpdatesMetadataAndResetsProcessingInOneUpdate() = runBlocking {
        val synced = original.copy(status = MediaFileStatus.SYNCED, serverFileId = 55, retryCount = 2)
        dao.insert(synced)

        dao.applyScan(synced, size = 200, lastModified = 2_000, displayName = "renamed.jpg")

        val file = dao.single(1)
        assertEquals(MediaFileStatus.PENDING, file.status)
        assertNull(file.checksum)
        assertNull(file.serverFileId)
        assertEquals(0, file.retryCount)
        assertEquals(200L, file.size)
        assertEquals(2_000L, file.lastModified)
        assertEquals("renamed.jpg", file.displayName)
    }

    @Test
    fun scanOfUnchangedFileKeepsStatusAndChecksum() = runBlocking {
        val synced = original.copy(status = MediaFileStatus.SYNCED, serverFileId = 55)
        dao.insert(synced)

        dao.applyScan(synced, size = synced.size, lastModified = synced.lastModified, displayName = "renamed.jpg")

        val file = dao.single(1)
        assertEquals(MediaFileStatus.SYNCED, file.status)
        assertEquals(synced.checksum, file.checksum)
        assertEquals(55L, file.serverFileId)
        assertEquals("renamed.jpg", file.displayName)
    }

    @Test
    fun hashStartedBeforeScanCannotLeaveRowInHashing() = runBlocking {
        val pending = original.copy(status = MediaFileStatus.PENDING, checksum = null)
        dao.insert(pending)
        // Hash взял строку, затем scan увидел новую версию файла.
        assertEquals(1, dao.updateStatusIfUnchanged(pending, MediaFileStatus.PENDING, MediaFileStatus.HASHING))
        dao.applyScan(pending, size = 200, lastModified = 2_000)

        assertEquals(0, dao.updateChecksumIfUnchanged(1, "c".repeat(64), pending.size, pending.lastModified))
        val file = dao.single(1)
        assertEquals(MediaFileStatus.PENDING, file.status)
        assertEquals(200L, file.size)
    }

    @Test
    fun hashStartingAfterScanSkipsStaleSnapshot() = runBlocking {
        val pending = original.copy(status = MediaFileStatus.PENDING, checksum = null)
        dao.insert(pending)
        // Hash прочитал снимок строки, но scan успел записать новую версию раньше перехода.
        dao.applyScan(pending, size = 200, lastModified = 2_000)

        assertEquals(0, dao.updateStatusIfUnchanged(pending, MediaFileStatus.PENDING, MediaFileStatus.HASHING))
        val file = dao.single(1)
        assertEquals(MediaFileStatus.PENDING, file.status)
        assertEquals(200L, file.size)
    }

    @Test
    fun countReadyForUploadCoversUploaderStatusesOnly() = runBlocking {
        val statuses = MediaFileStatus.entries
        statuses.forEachIndexed { i, status ->
            dao.insert(original.copy(mediaStoreId = i + 1L, status = status))
        }
        // CHECKSUM_READY без checksum Загрузчику не передаётся.
        dao.insert(original.copy(mediaStoreId = 100, status = MediaFileStatus.CHECKSUM_READY, checksum = null))

        assertEquals(3, dao.countReadyForUpload()) // CHECKSUM_READY, PENDING_UPLOAD, UPLOADING
    }
}

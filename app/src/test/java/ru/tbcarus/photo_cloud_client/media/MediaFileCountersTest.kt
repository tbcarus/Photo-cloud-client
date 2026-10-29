package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase

/** Агрегированные счётчики индекса (A7.1) согласованы со статусами строк Room. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaFileCountersTest {

    private lateinit var db: AppDatabase
    private val dao get() = db.mediaFileDao()

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
    fun emptyIndexGivesZeroCounters() = runBlocking {
        assertEquals(MediaFileCounters(), dao.observeCounters().first())
    }

    @Test
    fun countersGroupStatusesAndMatchRows() = runBlocking {
        var id = 0L
        fun add(status: MediaFileStatus, count: Int, checksum: String? = "a".repeat(64)) =
            repeat(count) { runBlocking { dao.insert(row(++id, status, checksum)) } }
        add(MediaFileStatus.PENDING, 3, checksum = null)
        add(MediaFileStatus.HASHING, 1, checksum = null)
        add(MediaFileStatus.CHECKSUM_READY, 2)
        // Аномальная строка без checksum всё равно не обработана — она в группе отправки.
        add(MediaFileStatus.CHECKSUM_READY, 1, checksum = null)
        add(MediaFileStatus.PENDING_UPLOAD, 4)
        add(MediaFileStatus.UPLOADING, 1)
        add(MediaFileStatus.SYNCED, 5)
        add(MediaFileStatus.FAILED, 2)
        // Активный путь LOCAL_DELETED не пишет; такая строка в счётчики не входит.
        add(MediaFileStatus.LOCAL_DELETED, 1)

        val counters = dao.observeCounters().first()

        assertEquals(MediaFileCounters(total = 19, synced = 5, preparing = 4, toUpload = 8, failed = 2), counters)
        assertEquals(counters.total, counters.synced + counters.preparing + counters.toUpload + counters.failed)
        // Сверка с построчным подсчётом по тем же статусам.
        val rows = dao.getAll().first().filter { it.status != MediaFileStatus.LOCAL_DELETED }
        assertEquals(rows.size, counters.total)
        assertEquals(rows.count { it.status == MediaFileStatus.SYNCED }, counters.synced)
        assertEquals(rows.count { it.status == MediaFileStatus.FAILED }, counters.failed)
    }

    @Test
    fun countersFollowStatusChanges() = runBlocking {
        dao.insert(row(1, MediaFileStatus.PENDING_UPLOAD, "a".repeat(64)))
        assertEquals(1, dao.observeCounters().first().toUpload)

        dao.resetStatus(MediaFileStatus.PENDING_UPLOAD, MediaFileStatus.SYNCED)
        val afterUpload = dao.observeCounters().first()
        assertEquals(0, afterUpload.toUpload)
        assertEquals(1, afterUpload.synced)

        dao.deleteByMediaStoreIds(listOf(1))
        assertEquals(MediaFileCounters(), dao.observeCounters().first())
    }

    private fun row(id: Long, status: MediaFileStatus, checksum: String?) = MediaFile(
        mediaStoreId = id,
        uri = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/",
        mimeType = "image/jpeg",
        size = 100,
        createdAt = id,
        lastModified = id,
        checksum = checksum,
        status = status
    )
}

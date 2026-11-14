package ru.tbcarus.photo_cloud_client.transfer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.media.SyncConfig
import ru.tbcarus.photo_cloud_client.media.UploadSizeClass
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ManualUploadRoomTest {
    @Test fun queueAndSessionReferenceSurviveReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "manual-${UUID.randomUUID()}.db"
        val requestId = UUID.randomUUID().toString()
        val jobs = (1..2).map { index ->
            ManualUploadJob(UUID.randomUUID().toString(), "http://server/", "alice", "content://file/$index",
                "file$index.jpg", 123, "image/jpeg", 42, 7, false, createdAt = index.toLong())
        }
        val db = AppDatabase.build(context, dbName)
        db.manualUploadDao().insert(jobs)
        assertEquals(jobs[0].id, db.manualUploadDao().next("http://server/", "alice")?.id)
        db.manualUploadDao().setStatus(jobs[0].id, ManualUploadJob.RUNNING)
        db.manualUploadDao().setSessionRequestId(jobs[0].id, requestId)
        db.manualUploadDao().setOffset(jobs[0].id, 80)
        db.manualUploadDao().markVerifying(jobs[1].id)
        db.close()

        val reopened = AppDatabase.build(context, dbName)
        val row = reopened.manualUploadDao().get(jobs[0].id)!!
        assertEquals(requestId, row.sessionRequestId)
        assertEquals(80L, row.confirmedOffset)
        assertEquals(ManualUploadJob.RUNNING, row.status)
        assertEquals(2, reopened.manualUploadDao().observeAll().first().size)
        assertEquals(1, reopened.manualUploadDao().markVerifying(jobs[1].id))
        assertNull(reopened.manualUploadDao().next("http://other/", "alice"))
        assertEquals(0, reopened.manualUploadDao().cancelUnstarted(row.id))
        assertEquals(1, reopened.manualUploadDao().requestCancel(row.id))
        assertEquals(ManualUploadJob.CANCEL_PENDING, reopened.manualUploadDao().get(row.id)?.status)
        reopened.close()
        context.deleteDatabase(dbName)
        Unit
    }

    @Test fun sizeBoundariesUseCentralPolicy() {
        assertEquals(UploadSizeClass.UNKNOWN, SyncConfig.uploadSizeClass(0))
        assertEquals(UploadSizeClass.MULTIPART, SyncConfig.uploadSizeClass(SyncConfig.MULTIPART_MAX_BYTES))
        assertEquals(UploadSizeClass.RESUMABLE, SyncConfig.uploadSizeClass(SyncConfig.MULTIPART_MAX_BYTES + 1))
        assertEquals(UploadSizeClass.RESUMABLE, SyncConfig.uploadSizeClass(SyncConfig.RESUMABLE_MAX_BYTES))
        assertEquals(UploadSizeClass.TOO_LARGE, SyncConfig.uploadSizeClass(SyncConfig.RESUMABLE_MAX_BYTES + 1))
        assertTrue(SyncConfig.RESUMABLE_CHUNK_BYTES < SyncConfig.MULTIPART_MAX_BYTES)
    }

    @Test fun queuedFileCancelsWithoutServerAndDoesNotEnterWorker() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "manual-cancel-${UUID.randomUUID()}.db"
        val db = AppDatabase.build(context, dbName)
        val row = ManualUploadJob(UUID.randomUUID().toString(), "http://server/", "alice",
            "content://test/1", "file.jpg", 5, "image/jpeg", null, 7, false)
        try {
            db.manualUploadDao().insert(listOf(row))
            assertEquals(1, db.manualUploadDao().cancelUnstarted(row.id))
            assertEquals(ManualUploadJob.CANCELLED, db.manualUploadDao().get(row.id)?.status)
            assertNull(db.manualUploadDao().next(row.serverUrl, row.accountId))
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}

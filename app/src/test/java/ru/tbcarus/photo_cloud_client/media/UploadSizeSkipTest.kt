package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadSizeSkipTest {
    @Test fun unknownAndOversizeCameraFilesAreSkippedWithExplicitReasons() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.mediaFileDao()
            fun file(id: Long, size: Long) = MediaFile(id, uri = "content://media/$id", displayName = "photo$id.jpg",
                relativePath = "DCIM/Camera/", mimeType = "image/jpeg", size = size, createdAt = 1,
                lastModified = 1, checksum = "a".repeat(64), status = MediaFileStatus.PENDING_UPLOAD)
            dao.insert(file(1, 0))
            dao.insert(file(2, SyncConfig.RESUMABLE_MAX_BYTES + 1))
            val upload = FileUploadRepository(dao, mock(ApiServiceFactory::class.java), mock(MediaStreamOpener::class.java))
            upload.uploadPendingCameraFiles(7)
            assertEquals(FailureReason.SIZE_UNKNOWN, dao.single(1).failureReason)
            assertEquals(FailureReason.FILE_TOO_LARGE, dao.single(2).failureReason)
            assertEquals(MediaFileStatus.FAILED, dao.single(1).status)
            assertEquals(MediaFileStatus.FAILED, dao.single(2).status)
        } finally { db.close() }
    }
}

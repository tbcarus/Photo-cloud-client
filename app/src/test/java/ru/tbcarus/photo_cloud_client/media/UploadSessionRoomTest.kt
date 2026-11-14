package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadSessionRoomTest {
    @Test fun sessionSurvivesDatabaseReopenWithoutMediaFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "upload-session-${UUID.randomUUID()}.db"
        val requestId = UUID.randomUUID().toString()
        val first = AppDatabase.build(context, name)
        first.uploadSessionDao().insert(UploadSessionRecord(
            requestId, "https://one.example/", "user-1", "CAMERA", "42",
            "content://media/42", 7, "image.jpg", 100, 123, false, "a".repeat(64)
        ))
        first.uploadSessionDao().setServerState(requestId, UUID.randomUUID().toString(), 80)
        first.close()

        val reopened = AppDatabase.build(context, name)
        val row = reopened.uploadSessionDao().forSource("https://one.example/", "user-1", "CAMERA", "42").single()
        assertNotNull(row.serverSessionId)
        assertEquals(80L, row.confirmedOffset)
        assertEquals(emptyList<UploadSessionRecord>(), reopened.uploadSessionDao().forSource("https://two.example/", "user-1", "CAMERA", "42"))
        assertEquals(emptyList<UploadSessionRecord>(), reopened.uploadSessionDao().forSource("https://one.example/", "user-2", "CAMERA", "42"))
        reopened.uploadSessionDao().requestCancel(requestId)
        assertEquals(true, reopened.uploadSessionDao().forAccount("https://one.example/", "user-1").single().cancelPending)
        reopened.close()
        context.deleteDatabase(name)
        Unit
    }
}

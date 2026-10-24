package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase

/** Чистая установка: БД создаётся с нуля боевой конфигурацией без миграций и проходит проверку схемы Room. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppDatabaseCreationTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        AppDatabase.getInstance(context).close()
        context.deleteDatabase("photo_cloud.db")
    }

    @Test
    fun freshInstallCreatesCurrentSchema() = runBlocking {
        assertTrue(context.getDatabasePath("photo_cloud.db").let { !it.exists() })
        val db = AppDatabase.getInstance(context)

        val file = MediaFile(
            mediaStoreId = 1, uri = "content://media/external/images/media/1", displayName = "IMG_1.jpg",
            relativePath = "DCIM/Camera/", mimeType = "image/jpeg", size = 10, createdAt = 1, lastModified = 1,
            checksum = null, failureReason = FailureReason.CHECKSUM_IO, retryCount = 1, lastFailureAt = 5
        )
        db.mediaFileDao().insert(file)

        assertEquals(listOf(file), db.mediaFileDao().getAll().first())
        assertEquals(3, db.openHelper.readableDatabase.version)
        val columns = db.openHelper.readableDatabase.query("PRAGMA table_info(media_files)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        assertTrue(columns.containsAll(listOf("displayName", "relativePath", "lastModified", "failureReason", "retryCount", "lastFailureAt")))
    }
}

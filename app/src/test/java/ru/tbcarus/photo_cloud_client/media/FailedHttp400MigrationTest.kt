package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import java.io.File

/** Миграция 3→4 на настоящем файле БД версии 3: прежняя очередь сохраняется, HTTP_400 получают одну новую попытку. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FailedHttp400MigrationTest {

    private companion object {
        const val DB_NAME = "migration-test.db"
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AppDatabase

    @Before
    fun createVersion3() {
        val schema = JSONObject(
            File("schemas/ru.tbcarus.photo_cloud_client.core.db.AppDatabase/3.json").readText()
        ).getJSONObject("database")
        val file = context.getDatabasePath(DB_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val table = schema.getJSONArray("entities").getJSONObject(0)
            old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", "media_files"))
            val indices = table.optJSONArray("indices")
            for (i in 0 until (indices?.length() ?: 0)) {
                old.execSQL(indices!!.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", "media_files"))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))

            old.insertRow(1, "FAILED", "HTTP_400", "aaa", retryCount = 1)
            old.insertRow(2, "FAILED", "HTTP_409", "bbb", retryCount = 1)
            old.insertRow(3, "FAILED", "FILE_NOT_FOUND", "ccc", retryCount = 1)
            old.insertRow(4, "SYNCED", null, "ddd", retryCount = 0)
            old.insertRow(5, "FAILED", "HTTP_400", null, retryCount = 1)
            old.insertRow(6, "PENDING_UPLOAD", null, "eee", retryCount = 0)
            old.version = 3
        }
        db = AppDatabase.build(context, DB_NAME)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun http400WithChecksumReturnsToPrecheckOnceAndOtherRowsStay() = runBlocking {
        val rows = db.mediaFileDao().getAll().first().associateBy { it.mediaStoreId }

        assertEquals(6, rows.size)
        assertEquals(9, db.openHelper.readableDatabase.version)
        val restored = rows.getValue(1)
        assertEquals(MediaFileStatus.CHECKSUM_READY, restored.status)
        assertNull(restored.failureReason)
        assertNull(restored.lastFailureAt)
        assertEquals("aaa", restored.checksum)
        // Счётчик попыток сохраняется: история отказа не стирается.
        assertEquals(1, restored.retryCount)

        assertEquals(FailureReason.HTTP_409, rows.getValue(2).failureReason)
        assertEquals(MediaFileStatus.FAILED, rows.getValue(2).status)
        assertEquals(FailureReason.FILE_NOT_FOUND, rows.getValue(3).failureReason)
        assertEquals(MediaFileStatus.SYNCED, rows.getValue(4).status)
        // Без checksum строку нельзя отправить в pre-check: восстановления нет, но и потери строки нет.
        assertEquals(MediaFileStatus.FAILED, rows.getValue(5).status)
        assertEquals(MediaFileStatus.PENDING_UPLOAD, rows.getValue(6).status)
    }

    private fun SQLiteDatabase.insertRow(id: Long, status: String, reason: String?, checksum: String?, retryCount: Int) {
        insertOrThrow("media_files", null, ContentValues().apply {
            put("mediaStoreId", id)
            put("uri", "content://media/external/images/media/$id")
            put("displayName", "IMG_$id.jpg")
            put("relativePath", "DCIM/Camera/")
            put("mimeType", "image/jpeg")
            put("size", 10L)
            put("createdAt", 1L)
            put("lastModified", 1L)
            put("checksum", checksum)
            put("status", status)
            put("failureReason", reason)
            put("retryCount", retryCount)
            put("lastFailureAt", if (reason == null) null else 5L)
        })
    }
}

package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import java.io.File

/** Миграция 8→9 (Q-02): строки очереди Camera сохраняются, счётчик несовпадений начинается с нуля. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadMismatchMigrationTest {

    private companion object {
        const val DB_NAME = "migration-8-9.db"
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun rowsSurviveAndCounterStartsAtZero() = runBlocking {
        createFromSchema(8) { old ->
            old.insertOrThrow("media_files", null, values(1, "SYNCED", serverFileId = 100L, retryCount = 0))
            old.insertOrThrow("media_files", null, values(2, "FAILED", failureReason = "CHECKSUM_IO", retryCount = 2))
            old.insertOrThrow("media_files", null, values(3, "PENDING_UPLOAD", retryCount = 0))
        }

        val db = AppDatabase.build(context, DB_NAME)
        try {
            assertEquals(10, db.openHelper.readableDatabase.version)
            val rows = db.mediaFileDao().getAll().first().associateBy { it.mediaStoreId }
            assertEquals(MediaFileStatus.SYNCED, rows.getValue(1).status)
            assertEquals(100L, rows.getValue(1).serverFileId)
            assertEquals(MediaFileStatus.FAILED, rows.getValue(2).status)
            assertEquals(FailureReason.CHECKSUM_IO, rows.getValue(2).failureReason)
            assertEquals(2, rows.getValue(2).retryCount)
            assertEquals(MediaFileStatus.PENDING_UPLOAD, rows.getValue(3).status)
            assertEquals("a".repeat(64), rows.getValue(3).checksum)
            rows.values.forEach { assertEquals(0, it.uploadMismatchCount) }
        } finally {
            db.close()
        }
    }

    private fun values(
        id: Long,
        status: String,
        serverFileId: Long? = null,
        failureReason: String? = null,
        retryCount: Int
    ) = ContentValues().apply {
        put("mediaStoreId", id)
        put("uri", "content://media/external/images/media/$id")
        put("displayName", "IMG_$id.jpg")
        put("mimeType", "image/jpeg")
        put("size", 10L)
        put("createdAt", 1L)
        put("lastModified", 1L)
        put("checksum", "a".repeat(64))
        put("status", status)
        serverFileId?.let { put("serverFileId", it) }
        failureReason?.let { put("failureReason", it) }
        put("retryCount", retryCount)
    }

    private fun createFromSchema(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(
            File("schemas/ru.tbcarus.photo_cloud_client.core.db.AppDatabase/$version.json").readText()
        ).getJSONObject("database")
        val path = context.getDatabasePath(DB_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            fill(old)
            old.version = version
        }
    }
}

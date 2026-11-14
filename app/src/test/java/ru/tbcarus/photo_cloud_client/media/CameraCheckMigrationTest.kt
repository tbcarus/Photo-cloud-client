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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import java.io.File

/** Миграция 7→8 (Q-01): очередь Camera сохраняется, появляются пустые таблицы ручной сверки. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CameraCheckMigrationTest {

    private companion object {
        const val DB_NAME = "migration-7-8.db"
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun cameraQueueSurvivesAndCheckTablesAppearEmpty() = runBlocking {
        createFromSchema(7) { old ->
            old.insertOrThrow("media_files", null, ContentValues().apply {
                put("mediaStoreId", 1L)
                put("uri", "content://media/external/images/media/1")
                put("displayName", "IMG_1.jpg")
                put("mimeType", "image/jpeg")
                put("size", 10L)
                put("createdAt", 1L)
                put("lastModified", 1L)
                put("checksum", "a".repeat(64))
                put("status", "SYNCED")
                put("retryCount", 0)
            })
        }

        val db = AppDatabase.build(context, DB_NAME)
        try {
            assertEquals(11, db.openHelper.readableDatabase.version)
            assertEquals(MediaFileStatus.SYNCED, db.mediaFileDao().getAll().first().single().status)
            assertNull(db.cameraCheckDao().get())
            assertEquals(CameraCheckCounts(), db.cameraCheckDao().counts())
            assertEquals(0, db.cameraCheckDao().countPausing())

            // Таблицы рабочие: запись проверки и набор из индекса.
            db.cameraCheckDao().put(
                CameraCheck(runId = "r", state = CameraCheckState.RUNNING, stage = CameraCheckStage.SCAN,
                    serverUrl = "http://10.0.2.2:8080/", accountId = "a", startedAt = 1)
            )
            db.cameraCheckDao().snapshotItems()
            assertEquals(1, db.cameraCheckDao().counts().withChecksum)
            assertEquals(1, db.cameraCheckDao().countPausing())
        } finally {
            db.close()
        }
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

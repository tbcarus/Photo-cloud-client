package ru.tbcarus.photo_cloud_client.backup

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.media.MediaFileStatus
import java.io.File

/**
 * Миграция 4→5 на файле БД версии 4 с очередью Camera, открытом боевой конфигурацией приложения:
 * очередь сохраняется, таблица источников добавляется, запись источника переживает переоткрытие БД.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupSourceMigrationTest {

    private companion object {
        const val DB_NAME = "migration-4-5.db"
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun createVersion4() {
        val schema = JSONObject(
            File("schemas/ru.tbcarus.photo_cloud_client.core.db.AppDatabase/4.json").readText()
        ).getJSONObject("database")
        val file = context.getDatabasePath(DB_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val table = schema.getJSONArray("entities").getJSONObject(0)
            old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", "media_files"))
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.insertRow(1, "SYNCED", "aaa", serverFileId = 11)
            old.insertRow(2, "PENDING_UPLOAD", "bbb", serverFileId = null)
            old.insertRow(3, "PENDING", null, serverFileId = null)
            old.version = 4
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun cameraQueueIsKeptAndSourcesSurviveReopen() = runBlocking {
        val db = AppDatabase.build(context, DB_NAME)

        val rows = db.mediaFileDao().getAll().first().associateBy { it.mediaStoreId }
        assertEquals(11, db.openHelper.readableDatabase.version)
        assertEquals(3, rows.size)
        assertEquals(MediaFileStatus.SYNCED, rows.getValue(1).status)
        assertEquals(11L, rows.getValue(1).serverFileId)
        assertEquals("aaa", rows.getValue(1).checksum)
        assertEquals(MediaFileStatus.PENDING_UPLOAD, rows.getValue(2).status)
        assertEquals(MediaFileStatus.PENDING, rows.getValue(3).status)

        val source = BackupSource(
            sourceUuid = "550e8400-e29b-41d4-a716-446655440000",
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            localName = "Documents",
            serverName = "Documents",
            serverRootFolderId = null,
            serverUrl = "http://10.0.2.2:8080/",
            accountId = "a@test.local",
            registrationState = RegistrationState.PENDING_REGISTRATION,
            registrationError = null,
            accessState = SourceAccess.AVAILABLE,
            createdAt = 1
        )
        val id = db.backupSourceDao().insert(source)
        db.backupSourceDao().markActive(id, 42)
        db.close()

        // Новый экземпляр БД — как после перезапуска приложения.
        val reopened = AppDatabase.build(context, DB_NAME)
        try {
            val restored = reopened.backupSourceDao().get(id)!!
            assertEquals(source.sourceUuid, restored.sourceUuid)
            assertEquals(source.treeUri, restored.treeUri)
            assertEquals(42L, restored.serverRootFolderId)
            assertEquals(RegistrationState.ACTIVE, restored.registrationState)
            assertEquals(3, reopened.mediaFileDao().getAll().first().size)
            assertTrue(
                reopened.openHelper.readableDatabase.query("PRAGMA index_list(backup_sources)").use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
                }.contains("index_backup_sources_sourceUuid")
            )
        } finally {
            reopened.close()
        }
    }

    private fun SQLiteDatabase.insertRow(id: Long, status: String, checksum: String?, serverFileId: Long?) {
        insertOrThrow("media_files", null, ContentValues().apply {
            put("mediaStoreId", id)
            put("serverFileId", serverFileId)
            put("uri", "content://media/external/images/media/$id")
            put("displayName", "IMG_$id.jpg")
            put("relativePath", "DCIM/Camera/")
            put("mimeType", "image/jpeg")
            put("size", 10L)
            put("createdAt", 1L)
            put("lastModified", 1L)
            put("checksum", checksum)
            put("status", status)
            put("retryCount", 0)
        })
    }
}

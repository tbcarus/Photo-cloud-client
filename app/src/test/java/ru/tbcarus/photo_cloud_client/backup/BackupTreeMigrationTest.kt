package ru.tbcarus.photo_cloud_client.backup

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import java.io.File

/**
 * Миграции 5→6 и 6→7 на файлах БД прежних версий с очередью Camera, источником и состояниями файлов, открытых
 * боевой конфигурацией;
 * без пути миграции БД не открывается и данные не удаляются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupTreeMigrationTest {

    private companion object {
        const val DB_NAME = "migration-5-6.db"
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun cameraQueueAndSourcesSurviveAndTreeTablesAppear() = runBlocking {
        createFromSchema(5) { old ->
            old.insertMedia(1, "SYNCED")
            old.insertMedia(2, "PENDING")
            old.insertOrThrow("backup_sources", null, ContentValues().apply {
                put("sourceUuid", "550e8400-e29b-41d4-a716-446655440000")
                put("treeUri", "content://com.android.externalstorage.documents/tree/primary%3ADocs")
                put("localName", "Docs")
                put("serverName", "Docs")
                put("serverRootFolderId", 42L)
                put("serverUrl", "http://10.0.2.2:8080/")
                put("accountId", "a@test.local")
                put("registrationState", "ACTIVE")
                put("accessState", "AVAILABLE")
                put("createdAt", 1L)
            })
        }

        val db = AppDatabase.build(context, DB_NAME)
        try {
            assertEquals(8, db.openHelper.readableDatabase.version)
            assertEquals(2, db.mediaFileDao().getAll().first().size)
            val source = db.backupSourceDao().list("http://10.0.2.2:8080/", "a@test.local").single()
            assertEquals(42L, source.serverRootFolderId)
            assertEquals(RegistrationState.ACTIVE, source.registrationState)
            assertEquals(0, source.scanGeneration)
            assertNull(source.lastScanAt)

            val tree = db.backupTreeDao()
            val rootId = tree.insertFolder(folder(source.id, null, "", serverFolderId = 42))
            val folderId = tree.insertFolder(folder(source.id, rootId, "Projects", serverFolderId = 43))
            tree.insertFile(file(source.id, folderId, "Projects/Drawing.pdf", serverFileId = 44))
            assertEquals(43L, tree.folderByPath(source.id, "Projects")!!.serverFolderId)
            assertEquals(44L, tree.files(source.id).single().serverFileId)
            // Путь уникален в пределах источника.
            try {
                tree.insertFile(file(source.id, folderId, "Projects/Drawing.pdf", serverFileId = null))
                fail("Повтор пути в источнике должен отклоняться")
            } catch (e: SQLiteConstraintException) {
                // ожидаемо
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun archiveColumnsAppearAndBackupStatesSurvive() = runBlocking {
        createFromSchema(6) { old ->
            old.insertMedia(1, "SYNCED")
            old.insertOrThrow("backup_sources", null, ContentValues().apply {
                put("sourceUuid", "550e8400-e29b-41d4-a716-446655440000")
                put("treeUri", "content://com.android.externalstorage.documents/tree/primary%3ADocs")
                put("localName", "Docs")
                put("serverName", "Docs")
                put("serverRootFolderId", 42L)
                put("serverUrl", "http://10.0.2.2:8080/")
                put("accountId", "a@test.local")
                put("registrationState", "ACTIVE")
                put("accessState", "AVAILABLE")
                put("createdAt", 1L)
                put("scanGeneration", 3)
                put("scanErrors", 0)
                put("lastScanAt", 5L)
            })
            old.insertOrThrow("backup_folders", null, ContentValues().apply {
                put("id", 1L); put("sourceId", 1L); put("name", "Docs"); put("relativePath", "")
                put("documentId", "primary:Docs"); put("serverFolderId", 42L); put("seenGeneration", 3); put("listedGeneration", 3)
            })
            // Незавершённые состояния 5.3: изменённый после отправки файл и прерванная отправка.
            old.insertV6File(1, "Synced.txt", "SYNCED", serverFileId = 44, checksum = "a", syncedChecksum = "a")
            old.insertV6File(2, "Changed.txt", "CHANGED", serverFileId = 45, checksum = "b2", syncedChecksum = "b1")
            old.insertV6File(3, "Sending.txt", "UPLOADING", serverFileId = null, checksum = "c", syncedChecksum = null)
        }

        val db = AppDatabase.build(context, DB_NAME)
        try {
            assertEquals(8, db.openHelper.readableDatabase.version)
            assertEquals(1, db.mediaFileDao().getAll().first().size)
            val source = db.backupSourceDao().list("http://10.0.2.2:8080/", "a@test.local").single()
            assertEquals(3, source.scanGeneration)
            assertEquals(42L, db.backupTreeDao().folderByPath(source.id, "")!!.serverFolderId)
            val files = db.backupTreeDao().files(source.id).associateBy { it.name }
            assertEquals(BackupFileStatus.SYNCED, files.getValue("Synced.txt").status)
            assertEquals(44L, files.getValue("Synced.txt").serverFileId)
            val changed = files.getValue("Changed.txt")
            assertEquals(BackupFileStatus.CHANGED, changed.status)
            assertEquals(45L, changed.serverFileId)
            assertEquals("b1", changed.syncedChecksum)
            assertNull(changed.archiveName)
            assertNull(changed.archiveFileId)
            assertEquals(BackupFileStatus.UPLOADING, files.getValue("Sending.txt").status)
            // Изменённый файл 5.3 теперь в очереди архивирования.
            assertEquals(listOf("Changed.txt"), db.backupTreeDao().filesToProcess(source.id, 10).map { it.name })

            // Состояние архивирования записывается и читается.
            db.backupTreeDao().updateFile(changed.copy(status = BackupFileStatus.ARCHIVING, archiveName = "Changed_20261104-120000.txt", archiveFileId = 45, archiveChecksum = "b1"))
            val archiving = db.backupTreeDao().file(changed.id)!!
            assertEquals(BackupFileStatus.ARCHIVING, archiving.status)
            assertEquals("Changed_20261104-120000.txt", archiving.archiveName)
            assertEquals(45L, archiving.archiveFileId)
            assertEquals("b1", archiving.archiveChecksum)
        } finally {
            db.close()
        }
    }

    @Test
    fun missingMigrationPathRefusesToOpenAndKeepsData() {
        createFromSchema(2) { old ->
            old.execSQL(
                "INSERT INTO media_files (mediaStoreId, uri, displayName, mimeType, size, createdAt, lastModified, status) " +
                    "VALUES (1, 'content://media/1', 'IMG_1.jpg', 'image/jpeg', 10, 1, 1, 'SYNCED')"
            )
        }

        try {
            AppDatabase.build(context, DB_NAME).openHelper.writableDatabase
            fail("БД без пути миграции не должна открываться")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Migration"))
        }

        SQLiteDatabase.openDatabase(context.getDatabasePath(DB_NAME).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(2, it.version)
            it.rawQuery("SELECT COUNT(*) FROM media_files", null).use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
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

    private fun SQLiteDatabase.insertMedia(id: Long, status: String) {
        insertOrThrow("media_files", null, ContentValues().apply {
            put("mediaStoreId", id)
            put("uri", "content://media/external/images/media/$id")
            put("displayName", "IMG_$id.jpg")
            put("mimeType", "image/jpeg")
            put("size", 10L)
            put("createdAt", 1L)
            put("lastModified", 1L)
            put("status", status)
            put("retryCount", 0)
        })
    }

    private fun SQLiteDatabase.insertV6File(id: Long, name: String, status: String, serverFileId: Long?, checksum: String, syncedChecksum: String?) {
        insertOrThrow("backup_files", null, ContentValues().apply {
            put("id", id); put("sourceId", 1L); put("folderId", 1L); put("name", name); put("relativePath", name)
            put("documentId", "primary:Docs/$name"); put("mimeType", "text/plain"); put("size", 2L); put("lastModified", 1L)
            put("checksum", checksum); put("serverFileId", serverFileId); put("syncedChecksum", syncedChecksum)
            put("status", status); put("retryCount", 0); put("seenGeneration", 3)
        })
    }

    private fun folder(sourceId: Long, parentId: Long?, path: String, serverFolderId: Long?) = BackupFolder(
        sourceId = sourceId, parentId = parentId, name = path.substringAfterLast('/'), relativePath = path,
        documentId = "primary:Docs/$path", serverFolderId = serverFolderId, problem = null, seenGeneration = 1, listedGeneration = 1
    )

    private fun file(sourceId: Long, folderId: Long, path: String, serverFileId: Long?) = BackupFile(
        sourceId = sourceId, folderId = folderId, name = path.substringAfterLast('/'), relativePath = path,
        documentId = "primary:Docs/$path", mimeType = "application/pdf", size = 1, lastModified = 1, checksum = "a",
        serverFileId = serverFileId, syncedChecksum = "a", status = BackupFileStatus.SYNCED, failureReason = null,
        lastFailureAt = null, seenGeneration = 1, missingSince = null
    )
}

package ru.tbcarus.photo_cloud_client.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.SyncClock
import ru.tbcarus.photo_cloud_client.media.WorkBudget
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Обход SAF, папки на Server и отправка файлов через настоящий HTTP-слой и Room. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FolderBackupEngineTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val account = BackupAccount("http://10.0.2.2:8080/", "a@test.local")
    private val uuid = UUID.randomUUID().toString()

    private lateinit var server: MockWebServer
    private lateinit var fake: FakeLibraryServer
    private lateinit var db: AppDatabase
    private lateinit var tree: FakeTree
    private lateinit var accounts: FakeAccounts
    private lateinit var access: FakeAccess
    private lateinit var engine: FolderBackupEngine
    private lateinit var remote: FolderBackupRemote
    private var sourceId = 0L
    private var serverRoot = 0L

    @Before
    fun setUp() {
        fake = FakeLibraryServer()
        serverRoot = fake.source("Docs", uuid).id
        server = MockWebServer().apply {
            dispatcher = fake
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply { baseUrl = server.url("/").toString() }
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        remote = FolderBackupRemote(ApiServiceFactory(baseUrlProvider, client, client, client))
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        tree = FakeTree(context, "Docs")
        accounts = FakeAccounts(account)
        access = FakeAccess(context)
        sourceId = runBlocking { addSource(tree, serverRoot, uuid) }
        engine = FolderBackupEngine(db.backupSourceDao(), db.backupTreeDao(), remote, tree, access, accounts)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun firstRunMirrorsNestedEmptyAndHiddenFoldersWithBackupSource() = runBlocking {
        tree.file("Notes.txt", "notes")
        tree.dir("Projects")
        tree.dir("Projects/2026")
        tree.file("Projects/2026/Drawing.pdf", "drawing")
        tree.dir("Empty")
        tree.dir(".hidden")
        tree.file(".hidden/.secret", "secret")

        val run = run()

        assertFalse(run.retry)
        assertFalse(run.remaining)
        val drawingFolder = fake.folderAt(serverRoot, "Projects/2026")!!
        assertEquals("drawing", String(fake.fileAt(drawingFolder.id, "Drawing.pdf")!!.bytes))
        assertEquals("notes", String(fake.fileAt(serverRoot, "Notes.txt")!!.bytes))
        assertNotNull(fake.folderAt(serverRoot, "Empty"))
        assertEquals("secret", String(fake.fileAt(fake.folderAt(serverRoot, ".hidden")!!.id, ".secret")!!.bytes))
        assertEquals(3, fake.files.size)
        val files = db.backupTreeDao().files(sourceId)
        assertTrue(files.all { it.status == BackupFileStatus.SYNCED && it.serverFileId != null && it.syncedChecksum == it.checksum })
        assertEquals(setOf("Notes.txt", "Projects/2026/Drawing.pdf", ".hidden/.secret"), files.map { it.relativePath }.toSet())
        assertEquals(drawingFolder.id, db.backupTreeDao().folderByPath(sourceId, "Projects/2026")!!.serverFolderId)
        assertNotNull(db.backupSourceDao().get(sourceId)!!.lastScanAt)
        assertTrue(fake.requests.none { it.contains("/folders/backup") })
    }

    @Test
    fun nextRunUploadsOnlyNewFiles() = runBlocking {
        tree.file("Notes.txt", "notes")
        run()
        tree.dir("New")
        tree.file("New/Plan.txt", "plan")

        run()

        assertEquals(listOf("Notes.txt", "Plan.txt"), fake.uploads)
        assertEquals("plan", String(fake.fileAt(fake.folderAt(serverRoot, "New")!!.id, "Plan.txt")!!.bytes))
        assertEquals(2, db.backupSourceDao().get(sourceId)!!.scanGeneration)
    }

    @Test
    fun lostResponseIsRepeatedOnceAndServerKeepsOneFile() = runBlocking {
        tree.file("Notes.txt", "notes")
        fake.dropNextUpload = true

        run()

        assertEquals(1, fake.files.size)
        assertEquals(listOf("Notes.txt", "Notes.txt"), fake.uploads)
        val row = db.backupTreeDao().files(sourceId).single()
        assertEquals(BackupFileStatus.SYNCED, row.status)
        assertEquals(fake.files.keys.single(), row.serverFileId)
    }

    @Test
    fun sameNameAndChecksumOnServerIsReused() = runBlocking {
        val existing = fake.putFile(serverRoot, "Notes.txt", "notes")
        tree.file("Notes.txt", "notes")

        run()

        assertEquals(1, fake.files.size)
        assertEquals(existing.id, db.backupTreeDao().files(sourceId).single().serverFileId)
    }

    @Test
    fun nameTakenByFolderOrUnknownContentIsConflictWithoutLink() = runBlocking {
        fake.add(serverRoot, "Report", "USER", null)
        val older = fake.putFile(serverRoot, "Notes.txt", "older version")
        tree.file("Report", "report")
        tree.file("Notes.txt", "notes")

        run()
        run()

        val rows = db.backupTreeDao().files(sourceId).associateBy { it.name }
        assertEquals(BackupFileStatus.CONFLICT, rows.getValue("Report").status)
        assertEquals(FolderBackupEngine.FILE_NAME_TAKEN, rows.getValue("Report").failureReason)
        // Совпало только имя: содержимое не читалось из этого файла, поэтому связи и архивирования нет.
        assertEquals(BackupFileStatus.CONFLICT, rows.getValue("Notes.txt").status)
        assertEquals(FolderBackupEngine.FILE_CONTENT_UNKNOWN, rows.getValue("Notes.txt").failureReason)
        assertNull(rows.getValue("Notes.txt").serverFileId)
        assertEquals("Notes.txt", fake.files.getValue(older.id).name)
        assertEquals("older version", String(fake.files.getValue(older.id).bytes))
        assertEquals(1, fake.files.size)
        assertTrue(fake.renames.isEmpty())
    }

    @Test
    fun emptyTooLargeInvalidVirtualAndUnreadableFilesAreNotSent() = runBlocking {
        tree.file("empty.txt", "")
        tree.file("huge.bin", "x").reportedSize = 200L * 1024 * 1024
        tree.file("bad:name.txt", "bad")
        tree.file("cloud.doc", "cloud").virtual = true
        tree.file("locked.txt", "locked").unreadable = true

        run()

        val rows = db.backupTreeDao().files(sourceId).associateBy { it.name }
        assertEquals(FolderBackupEngine.EMPTY, rows.getValue("empty.txt").failureReason)
        assertEquals(FolderBackupEngine.TOO_LARGE, rows.getValue("huge.bin").failureReason)
        assertEquals(BackupFileStatus.SKIPPED, rows.getValue("bad:name.txt").status)
        assertEquals(FolderBackupEngine.VIRTUAL, rows.getValue("cloud.doc").failureReason)
        assertEquals(BackupFileStatus.FAILED, rows.getValue("locked.txt").status)
        assertTrue(fake.uploads.isEmpty())

        // Недоступный файл повторяется при следующем обходе.
        tree.at("locked.txt").unreadable = false
        run()
        assertEquals(BackupFileStatus.SYNCED, db.backupTreeDao().files(sourceId).single { it.name == "locked.txt" }.status)
        assertEquals(listOf("locked.txt"), fake.uploads)
    }

    @Test
    fun fileChangedDuringUploadIsNotSyncedAndLaterArchived() = runBlocking {
        val node = tree.file("Notes.txt", "version 1")
        var opens = 0
        tree.onOpen = {
            opens++
            // Второе открытие — передача: после неё файл уже другой.
            if (opens == 2) {
                it.bytes = "version 2".toByteArray()
                it.lastModified = 2
            }
        }

        run()

        val row = db.backupTreeDao().files(sourceId).single()
        assertEquals(BackupFileStatus.FAILED, row.status)
        assertEquals(FolderBackupEngine.CHANGED_DURING_UPLOAD, row.failureReason)
        assertEquals(fake.files.keys.single(), row.serverFileId)

        tree.onOpen = {}
        run()

        // Принятая версия 1 сохранена как прежняя, версия 2 — под исходным именем.
        val after = db.backupTreeDao().files(sourceId).single()
        assertEquals(BackupFileStatus.SYNCED, after.status)
        assertEquals("version 2", String(fake.fileAt(serverRoot, "Notes.txt")!!.bytes))
        assertEquals(listOf("version 1", "version 2"), fake.files.values.map { String(it.bytes) })
        assertEquals(2, fake.uploads.size)
        assertEquals(2L, node.lastModified)
    }

    @Test
    fun changedAfterBackupIsArchivedAndTouchOnlyStaysSynced() = runBlocking {
        val notes = tree.file("Notes.txt", "notes")
        val plan = tree.file("Plan.txt", "plan")
        run()

        notes.bytes = "notes v2".toByteArray()
        notes.lastModified = 5
        plan.lastModified = 5
        run()

        val rows = db.backupTreeDao().files(sourceId).associateBy { it.name }
        assertEquals(BackupFileStatus.SYNCED, rows.getValue("Notes.txt").status)
        assertEquals(BackupFileStatus.SYNCED, rows.getValue("Plan.txt").status)
        assertEquals(listOf("Notes.txt", "Plan.txt", "Notes.txt"), fake.uploads)
        assertEquals("notes v2", String(fake.fileAt(serverRoot, "Notes.txt")!!.bytes))
        assertEquals(1, fake.renames.size)
        assertEquals(3, fake.files.size)
    }

    @Test
    fun localDeletionKeepsServerCopyAndIncompleteScanMarksNothing() = runBlocking {
        tree.file("Notes.txt", "notes")
        tree.dir("Projects")
        tree.file("Projects/Plan.txt", "plan")
        run()

        tree.remove("Projects/Plan.txt")
        tree.unreadableFolders += tree.at("Projects").id
        tree.remove("Notes.txt")
        run()
        // Папка не прочитана: обход неполный, исчезновение не отмечается.
        assertTrue(db.backupTreeDao().files(sourceId).all { it.missingSince == null })

        tree.unreadableFolders.clear()
        run()
        assertTrue(db.backupTreeDao().files(sourceId).all { it.missingSince != null })
        assertEquals(2, fake.files.size)
    }

    @Test
    fun budgetStopsRunAndContinuationFinishesWithoutDuplicatesOrNewScan() = runBlocking {
        for (d in 1..4) {
            tree.dir("D$d")
            for (f in 1..5) tree.file("D$d/f$f.txt", "content $d $f")
        }
        var now = 0L
        val first = engine.run(WorkBudget(SyncClock { now++ }, 12), fullScan = true)!!

        assertTrue(first.progress)
        assertTrue(first.remaining)
        var continuations = 0
        while (true) {
            val next = engine.run(WorkBudget(SyncClock { now++ }, 12), fullScan = false)!!
            continuations++
            if (!next.remaining) break
            assertTrue(next.progress)
        }

        assertTrue(continuations > 1)
        assertEquals(20, fake.files.size)
        assertEquals(20, fake.uploads.size)
        assertEquals(1, db.backupSourceDao().get(sourceId)!!.scanGeneration)
        assertTrue(db.backupTreeDao().files(sourceId).all { it.status == BackupFileStatus.SYNCED })
    }

    @Test
    fun interruptedUploadReturnsToQueue() = runBlocking {
        tree.file("Notes.txt", "notes")
        run()
        val row = db.backupTreeDao().files(sourceId).single()
        db.backupTreeDao().updateFile(row.copy(status = BackupFileStatus.UPLOADING, serverFileId = null, syncedChecksum = null))

        run()

        assertEquals(BackupFileStatus.SYNCED, db.backupTreeDao().files(sourceId).single().status)
        assertEquals(1, fake.files.size)
    }

    @Test
    fun secondSourceIsServedBeforeFirstLargeSourceFinishes() = runBlocking {
        for (f in 1..25) tree.file("big$f.txt", "big $f")
        val otherUuid = UUID.randomUUID().toString()
        val otherRoot = fake.source("Other", otherUuid).id
        val otherTree = FakeTree(context, "Other")
        otherTree.file("small.txt", "small")
        addSource(otherTree, otherRoot, otherUuid, createdAt = 2)
        val combined = object : ru.tbcarus.photo_cloud_client.transfer.LocalDocuments(context) {
            fun pick(uri: android.net.Uri) = if (uri == otherTree.treeUri) otherTree else tree
            override fun children(treeUri: android.net.Uri, parentId: String) = pick(treeUri).children(treeUri, parentId)
            override fun stat(treeUri: android.net.Uri, documentId: String) = pick(treeUri).stat(treeUri, documentId)
            override fun documentUri(treeUri: android.net.Uri, documentId: String) =
                android.net.Uri.parse((if (treeUri == otherTree.treeUri) "other" else "main") + "|" + documentId)
            override fun open(uri: android.net.Uri): java.io.InputStream {
                val (which, id) = uri.toString().split('|', limit = 2)
                val owner = if (which == "other") otherTree else tree
                return owner.open(owner.documentUri(owner.treeUri, id))
            }
        }
        engine = FolderBackupEngine(db.backupSourceDao(), db.backupTreeDao(), remote, combined, access, accounts)

        run()

        assertEquals(26, fake.files.size)
        assertTrue(fake.uploads.indexOf("small.txt") < 25)
    }

    @Test
    fun remotelyDisabledSourceStopsWithoutRegistration() = runBlocking {
        tree.file("Notes.txt", "notes")
        fake.folders.getValue(serverRoot).backupSource = null

        val run = run()

        assertFalse(run.retry)
        assertEquals(RegistrationState.REMOTE_DISABLED, db.backupSourceDao().get(sourceId)!!.registrationState)
        assertTrue(fake.uploads.isEmpty())
        assertTrue(fake.requests.none { it.contains("/folders/backup") || it.startsWith("POST") })
        // Следующие прогоны отключённый источник не обрабатывают.
        fake.folders.getValue(serverRoot).backupSource = uuid
        run()
        assertTrue(fake.uploads.isEmpty())
    }

    @Test
    fun disableBetweenCheckAndUploadStopsOnFolderManaged() = runBlocking {
        tree.file("a.txt", "a")
        tree.file("b.txt", "b")
        fake.onUpload = { fake.folders.getValue(serverRoot).backupSource = null }

        run()

        assertEquals(RegistrationState.REMOTE_DISABLED, db.backupSourceDao().get(sourceId)!!.registrationState)
        assertEquals(listOf("a.txt"), fake.uploads)
        assertTrue(fake.files.isEmpty())
        assertTrue(db.backupTreeDao().files(sourceId).all { it.status == BackupFileStatus.PENDING })
    }

    @Test
    fun serverErrorAndNetworkLossAskForRetryAndKeepQueue() = runBlocking {
        tree.file("Notes.txt", "notes")
        fake.failUploadsWith = 500

        val failed = run()

        assertTrue(failed.retry)
        assertEquals(BackupFileStatus.PENDING, db.backupTreeDao().files(sourceId).single().status)

        server.shutdown()
        assertTrue(run().retry)
        assertEquals(BackupFileStatus.PENDING, db.backupTreeDao().files(sourceId).single().status)
    }

    @Test
    fun otherAccountSourcesAreNotProcessedAndSwitchStopsRun() = runBlocking {
        tree.file("a.txt", "a")
        tree.file("b.txt", "b")
        accounts.account.value = BackupAccount(account.serverUrl, "b@test.local")
        run()
        assertTrue(fake.requests.isEmpty())

        accounts.account.value = account
        fake.onUpload = { accounts.account.value = BackupAccount("http://other:8080/", account.accountId) }
        run()

        assertEquals(listOf("a.txt"), fake.uploads)
        assertEquals(BackupFileStatus.PENDING, db.backupTreeDao().files(sourceId).single { it.name == "b.txt" }.status)
    }

    @Test
    fun lostAccessSkipsSourceWithoutChanges() = runBlocking {
        tree.file("Notes.txt", "notes")
        access.states[tree.treeUri] = SourceAccess.PERMISSION_LOST

        run()

        assertEquals(SourceAccess.PERMISSION_LOST, db.backupSourceDao().get(sourceId)!!.accessState)
        assertTrue(fake.requests.isEmpty())
        assertEquals(RegistrationState.ACTIVE, db.backupSourceDao().get(sourceId)!!.registrationState)
    }

    @Test
    fun parallelRunIsRejectedInsteadOfProcessingTwice() = runBlocking {
        for (f in 1..5) tree.file("f$f.txt", "content $f")
        val first = async(kotlinx.coroutines.Dispatchers.IO) { engine.run(WorkBudget.UNLIMITED, true) }
        val second = async(kotlinx.coroutines.Dispatchers.IO) { engine.run(WorkBudget.UNLIMITED, true) }
        val results = listOf(first.await(), second.await())

        assertTrue(results.any { it != null })
        assertEquals(5, fake.files.size)
        assertEquals(5, fake.uploads.size)
    }

    private suspend fun run(): FolderBackupRun = engine.run(WorkBudget.UNLIMITED, fullScan = true)!!

    private suspend fun addSource(sourceTree: FakeTree, root: Long, sourceUuid: String, createdAt: Long = 1): Long =
        db.backupSourceDao().insert(
            BackupSource(
                sourceUuid = sourceUuid, treeUri = sourceTree.treeUri.toString(), localName = "Docs", serverName = "Docs",
                serverRootFolderId = root, serverUrl = account.serverUrl, accountId = account.accountId,
                registrationState = RegistrationState.ACTIVE, registrationError = null,
                accessState = SourceAccess.AVAILABLE, createdAt = createdAt
            )
        )
}

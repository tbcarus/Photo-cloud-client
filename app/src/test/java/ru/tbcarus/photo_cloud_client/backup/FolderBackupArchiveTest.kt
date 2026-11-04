package ru.tbcarus.photo_cloud_client.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Архивирование изменённых файлов (этап 5.4): прежняя версия переименовывается с сохранением ID, новая
 * загружается под исходным именем. Потерянные ответы моделируются выполненным на Server изменением,
 * гибель процесса — отменой прогона и новым экземпляром движка над той же Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FolderBackupArchiveTest {

    private companion object {
        const val NAME = "Чертёж.pdf"
        val T1: Long = Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()
        val T2: Long = Instant.parse("2026-11-04T13:30:05Z").toEpochMilli()
        const val ARCHIVE_1 = "Чертёж_20261104-120000.pdf"
        const val ARCHIVE_2 = "Чертёж_20261104-133005.pdf"
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val account = BackupAccount("http://10.0.2.2:8080/", "a@test.local")
    private val uuid = UUID.randomUUID().toString()

    private lateinit var server: MockWebServer
    private lateinit var fake: FakeLibraryServer
    private lateinit var db: AppDatabase
    private lateinit var tree: FakeTree
    private lateinit var accounts: FakeAccounts
    private lateinit var access: FakeAccess
    private lateinit var remote: FolderBackupRemote
    private lateinit var engine: FolderBackupEngine
    private var sourceId = 0L
    private var serverRoot = 0L
    private var now = T1

    @Before
    fun setUp() {
        fake = FakeLibraryServer()
        serverRoot = fake.source("Docs", uuid).id
        server = MockWebServer().apply {
            dispatcher = fake
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply { baseUrl = server.url("/").toString() }
        // Без тихого повтора OkHttp: потерянный ответ должен дойти до движка (повтор PATCH/upload и так безопасен).
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        remote = FolderBackupRemote(ApiServiceFactory(baseUrlProvider, client, client, client))
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        tree = FakeTree(context, "Docs")
        accounts = FakeAccounts(account)
        access = FakeAccess(context)
        sourceId = runBlocking {
            db.backupSourceDao().insert(
                BackupSource(
                    sourceUuid = uuid, treeUri = tree.treeUri.toString(), localName = "Docs", serverName = "Docs",
                    serverRootFolderId = serverRoot, serverUrl = account.serverUrl, accountId = account.accountId,
                    registrationState = RegistrationState.ACTIVE, registrationError = null,
                    accessState = SourceAccess.AVAILABLE, createdAt = 1
                )
            )
        }
        engine = newEngine()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun changedFileKeepsOldIdUnderArchiveNameAndGetsNewVersion() = runBlocking {
        val oldId = synced(NAME, "version 1")

        change(NAME, "version 2")
        run()

        val old = fake.files.getValue(oldId)
        assertEquals(ARCHIVE_1, old.name)
        assertEquals("version 1", String(old.bytes))
        val current = fake.fileAt(serverRoot, NAME)!!
        assertNotEquals(oldId, current.id)
        assertEquals("version 2", String(current.bytes))
        val row = row(NAME)
        assertEquals(BackupFileStatus.SYNCED, row.status)
        assertEquals(current.id, row.serverFileId)
        assertEquals(sha("version 2"), row.syncedChecksum)
        assertNull(row.archiveName)
        assertNull(row.archiveFileId)
        assertEquals(listOf(ARCHIVE_1), fake.renames)
        assertEquals(2, fake.files.size)
    }

    @Test
    fun everySuccessiveVersionIsKept() = runBlocking {
        val first = synced(NAME, "version 1")
        change(NAME, "version 2")
        run()
        val second = row(NAME).serverFileId!!

        now = T2
        change(NAME, "version 3", lastModified = 3)
        run()

        assertEquals(ARCHIVE_1, fake.files.getValue(first).name)
        assertEquals(ARCHIVE_2, fake.files.getValue(second).name)
        assertEquals("version 2", String(fake.files.getValue(second).bytes))
        assertEquals("version 3", String(fake.fileAt(serverRoot, NAME)!!.bytes))
        assertEquals(3, fake.files.size)
        assertEquals(listOf(ARCHIVE_1, ARCHIVE_2), fake.renames)
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
    }

    @Test
    fun sameSizeWithOtherContentIsArchived() = runBlocking {
        val oldId = synced(NAME, "aaaa")

        change(NAME, "bbbb")
        run()

        assertEquals(ARCHIVE_1, fake.files.getValue(oldId).name)
        assertEquals("bbbb", String(fake.fileAt(serverRoot, NAME)!!.bytes))
    }

    @Test
    fun sameChecksumNeedsNoArchiving() = runBlocking {
        synced(NAME, "version 1")
        // Изменилось только время, а строка 5.3 в CHANGED вернулась к прежнему содержимому.
        tree.at(NAME).lastModified = 7
        run()
        db.backupTreeDao().updateFile(row(NAME).copy(status = BackupFileStatus.CHANGED))
        run()

        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
        assertTrue(fake.renames.isEmpty())
        assertEquals(1, fake.uploads.size)
        assertEquals(1, fake.files.size)
    }

    @Test
    fun lostRenameResponseIsConfirmedByServerState() = runBlocking {
        val oldId = synced(NAME, "version 1")
        fake.dropNextRename = true

        change(NAME, "version 2")
        val result = run()

        assertFalse(result.retry)
        assertEquals(listOf(ARCHIVE_1), fake.renames)
        assertEquals(ARCHIVE_1, fake.files.getValue(oldId).name)
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
        assertEquals(2, fake.files.size)
    }

    @Test
    fun lostRenameResponseWithServerUnreachableKeepsChosenNameUntilChecked() = runBlocking {
        val oldId = synced(NAME, "version 1")
        fake.dropNextRename = true
        // Сразу после потерянного ответа и прочитать состояние не удаётся.
        fake.afterRename = { fake.failFileGets = 1 }

        change(NAME, "version 2")
        assertTrue(run().retry)

        val pending = row(NAME)
        assertEquals(BackupFileStatus.ARCHIVING, pending.status)
        assertEquals(ARCHIVE_1, pending.archiveName)
        assertEquals(oldId, pending.archiveFileId)
        assertEquals(sha("version 1"), pending.archiveChecksum)

        fake.afterRename = {}
        now = T2
        engine = newEngine()
        run()

        assertEquals(listOf(ARCHIVE_1), fake.renames)
        assertEquals(ARCHIVE_1, fake.files.getValue(oldId).name)
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
        assertEquals(2, fake.files.size)
    }

    @Test
    fun deathAfterRenameDoesNotRenameAgain() = runBlocking {
        val oldId = synced(NAME, "version 1")
        change(NAME, "version 2")

        // Процесс гибнет, пока PATCH выполняется: на Server файл переименован, ответ не получен.
        killDuring { fake.afterRename = { _ -> it() } }
        assertEquals(BackupFileStatus.ARCHIVING, row(NAME).status)
        assertEquals(ARCHIVE_1, fake.files.getValue(oldId).name)
        assertNull(fake.fileAt(serverRoot, NAME))

        fake.afterRename = {}
        now = T2
        engine = newEngine()
        run()

        assertEquals(listOf(ARCHIVE_1), fake.renames)
        assertEquals("version 2", String(fake.fileAt(serverRoot, NAME)!!.bytes))
        assertEquals(2, fake.files.size)
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
    }

    @Test
    fun failureBetweenRenameAndUploadContinuesWithUpload() = runBlocking {
        val oldId = synced(NAME, "version 1")
        fake.failUploadsWith = 503

        change(NAME, "version 2")
        assertTrue(run().retry)

        val archived = row(NAME)
        assertEquals(BackupFileStatus.ARCHIVED, archived.status)
        assertNull(archived.serverFileId)
        assertEquals(oldId, archived.archiveFileId)
        assertEquals(ARCHIVE_1, archived.archiveName)

        fake.failUploadsWith = null
        engine = newEngine()
        run()

        assertEquals(listOf(ARCHIVE_1), fake.renames)
        assertEquals(ARCHIVE_1, fake.files.getValue(oldId).name)
        assertEquals("version 2", String(fake.fileAt(serverRoot, NAME)!!.bytes))
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
        assertEquals(2, fake.files.size)
    }

    @Test
    fun lostUploadResponseIsResolvedWithoutExtraVersion() = runBlocking {
        synced(NAME, "version 1")
        // Теряются ответы и отправки, и её немедленного повтора: файл на Server уже есть.
        fake.dropUploads = 2

        change(NAME, "version 2")
        assertTrue(run().retry)
        assertEquals(BackupFileStatus.ARCHIVED, row(NAME).status)
        val uploaded = fake.fileAt(serverRoot, NAME)!!

        engine = newEngine()
        run()

        assertEquals(uploaded.id, row(NAME).serverFileId)
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
        assertEquals(2, fake.files.size)
        assertEquals(1, fake.renames.size)
    }

    @Test
    fun deathAfterSuccessfulUploadBeforeRoomCommitLinksUploadedVersion() = runBlocking {
        synced(NAME, "version 1")
        change(NAME, "version 2")

        killDuring { kill -> fake.afterUpload = { kill() } }
        assertEquals(BackupFileStatus.ARCHIVED, row(NAME).status)
        val uploaded = fake.fileAt(serverRoot, NAME)!!

        fake.afterUpload = {}
        engine = newEngine()
        run()

        assertEquals(uploaded.id, row(NAME).serverFileId)
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
        assertEquals(2, fake.files.size)
        assertEquals(1, fake.renames.size)
    }

    @Test
    fun deathAfterUploadThenNewChangeArchivesUploadedVersion() = runBlocking {
        val first = synced(NAME, "version 1")
        change(NAME, "version 2")
        killDuring { kill -> fake.afterUpload = { kill() } }
        val second = fake.fileAt(serverRoot, NAME)!!.id

        // До следующего прогона файл снова изменился: версия 2 на Server опознаётся по прежнему чтению.
        fake.afterUpload = {}
        now = T2
        change(NAME, "version 3", lastModified = 3)
        engine = newEngine()
        run()

        assertEquals(ARCHIVE_1, fake.files.getValue(first).name)
        assertEquals(ARCHIVE_2, fake.files.getValue(second).name)
        assertEquals("version 3", String(fake.fileAt(serverRoot, NAME)!!.bytes))
        assertEquals(3, fake.files.size)
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
    }

    @Test
    fun takenArchiveNameGetsNumericSuffixAndExistingObjectsStay() = runBlocking {
        val oldId = synced(NAME, "version 1")
        val taken = fake.putFile(serverRoot, ARCHIVE_1, "other archive")
        fake.add(serverRoot, "чертёж_20261104-120000_2.pdf", "USER", null)

        change(NAME, "version 2")
        run()

        assertEquals(listOf(ARCHIVE_1, "Чертёж_20261104-120000_2.pdf", "Чертёж_20261104-120000_3.pdf"), fake.renames)
        assertEquals("Чертёж_20261104-120000_3.pdf", fake.files.getValue(oldId).name)
        assertEquals(ARCHIVE_1, fake.files.getValue(taken.id).name)
        assertEquals("other archive", String(fake.files.getValue(taken.id).bytes))
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
    }

    @Test
    fun twoChangesWithinOneSecondGetDistinctArchiveNames() = runBlocking {
        synced(NAME, "version 1")
        change(NAME, "version 2")
        run()
        change(NAME, "version 3", lastModified = 3)
        run()

        assertEquals(listOf(ARCHIVE_1, ARCHIVE_1, "Чертёж_20261104-120000_2.pdf"), fake.renames)
        assertEquals(3, fake.files.size)
    }

    @Test
    fun repeatedRunsCreateNoDuplicates() = runBlocking {
        synced(NAME, "version 1")
        change(NAME, "version 2")
        run()
        val requests = fake.requests.count { !it.startsWith("GET") }

        repeat(3) {
            now += 60_000
            run()
        }

        assertEquals(requests, fake.requests.count { !it.startsWith("GET") })
        assertEquals(2, fake.files.size)
        assertEquals(1, fake.renames.size)
    }

    @Test
    fun missingOrForeignOldVersionIsConflictWithoutChanges() = runBlocking {
        val gone = synced("gone.txt", "gone 1")
        synced("foreign.txt", "foreign 1")
        val plan = synced("plan.txt", "plan")
        fake.files.remove(gone)
        // Сохранённый ID указывает на другой файл той же папки.
        db.backupTreeDao().updateFile(row("foreign.txt").copy(serverFileId = plan, syncedChecksum = sha("plan")))

        change("gone.txt", "gone 2")
        change("foreign.txt", "foreign 2")
        run()

        assertEquals(BackupFileStatus.CONFLICT, row("gone.txt").status)
        assertEquals(FolderBackupEngine.ARCHIVE_SOURCE_MISSING, row("gone.txt").failureReason)
        assertEquals(BackupFileStatus.CONFLICT, row("foreign.txt").status)
        assertEquals(FolderBackupEngine.ARCHIVE_MISMATCH, row("foreign.txt").failureReason)
        assertEquals("plan.txt", fake.files.getValue(plan).name)
        assertTrue(fake.renames.isEmpty())
        assertEquals(3, fake.uploads.size)

        // Без изменения файла конфликт не повторяется.
        run()
        assertTrue(fake.renames.isEmpty())
        assertEquals(3, fake.uploads.size)
    }

    @Test
    fun oldVersionWithOtherChecksumIsConflict() = runBlocking {
        val oldId = synced(NAME, "version 1")
        fake.files.getValue(oldId).bytes = "replaced".toByteArray()

        change(NAME, "version 2")
        run()

        assertEquals(BackupFileStatus.CONFLICT, row(NAME).status)
        assertEquals(FolderBackupEngine.ARCHIVE_MISMATCH, row(NAME).failureReason)
        assertEquals(oldId, row(NAME).serverFileId)
        assertEquals(NAME, fake.files.getValue(oldId).name)
        assertTrue(fake.renames.isEmpty())
        assertEquals(1, fake.files.size)
    }

    @Test
    fun accountOrServerChangeStopsBeforeNextWrite() = runBlocking {
        val oldId = synced(NAME, "version 1")
        change(NAME, "version 2")
        // Аккаунт сменился во время сверки прежней версии: PATCH не отправляется.
        fake.onFileGet = { accounts.account.value = BackupAccount(account.serverUrl, "b@test.local") }
        run()
        assertTrue(fake.renames.isEmpty())
        assertEquals(BackupFileStatus.ARCHIVING, row(NAME).status)

        // Server сменился после подтверждённого переименования: новая версия не отправляется.
        fake.onFileGet = {}
        accounts.account.value = account
        fake.afterRename = { accounts.account.value = BackupAccount("http://other:8080/", account.accountId) }
        run()
        assertEquals(ARCHIVE_1, fake.files.getValue(oldId).name)
        assertEquals(BackupFileStatus.ARCHIVED, row(NAME).status)
        assertEquals(1, fake.uploads.size)

        fake.afterRename = {}
        accounts.account.value = account
        run()
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
        assertEquals(listOf(ARCHIVE_1), fake.renames)
        assertEquals(2, fake.files.size)
    }

    @Test
    fun remoteDisableStopsArchivingAndKeepsExistingArchives() = runBlocking {
        val first = synced(NAME, "version 1")
        change(NAME, "version 2")
        run()
        val second = row(NAME).serverFileId!!

        // Источник отключён на Server между проверкой прогона и PATCH.
        now = T2
        change(NAME, "version 3", lastModified = 3)
        fake.onFileGet = { fake.folders.getValue(serverRoot).backupSource = null }
        run()

        assertEquals(RegistrationState.REMOTE_DISABLED, db.backupSourceDao().get(sourceId)!!.registrationState)
        assertEquals(ARCHIVE_1, fake.files.getValue(first).name)
        assertEquals(NAME, fake.files.getValue(second).name)
        assertEquals(2, fake.files.size)
        assertEquals(2, fake.uploads.size)
        assertTrue(fake.requests.none { it.contains("/folders/backup") })

        fake.onFileGet = {}
        fake.folders.getValue(serverRoot).backupSource = uuid
        run()
        assertEquals(NAME, fake.files.getValue(second).name)
        assertEquals(2, fake.files.size)
    }

    @Test
    fun changeDuringNewVersionTransferIsArchivedByNextIteration() = runBlocking {
        val first = synced(NAME, "version 1")
        change(NAME, "version 2")
        // Передача версии 2 завершилась, а файл уже стал версией 3.
        var armed = true
        fake.onUpload = {
            if (armed) {
                armed = false
                change(NAME, "version 3", lastModified = 3)
            }
        }
        run()

        val row = row(NAME)
        assertEquals(BackupFileStatus.FAILED, row.status)
        assertEquals(FolderBackupEngine.CHANGED_DURING_UPLOAD, row.failureReason)
        val second = row.serverFileId!!
        assertEquals("version 2", String(fake.files.getValue(second).bytes))

        now = T2
        run()

        assertEquals(ARCHIVE_1, fake.files.getValue(first).name)
        assertEquals(ARCHIVE_2, fake.files.getValue(second).name)
        assertEquals("version 3", String(fake.fileAt(serverRoot, NAME)!!.bytes))
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
        assertEquals(3, fake.files.size)
    }

    @Test
    fun conflictOfOneFileDoesNotBlockOthers() = runBlocking {
        val a = synced("a.txt", "a 1")
        val b = synced("b.txt", "b 1")
        fake.files.getValue(a).bytes = "replaced".toByteArray()

        change("a.txt", "a 2")
        change("b.txt", "b 2")
        run()

        assertEquals(BackupFileStatus.CONFLICT, row("a.txt").status)
        assertEquals(BackupFileStatus.SYNCED, row("b.txt").status)
        assertEquals("b_20261104-120000.txt", fake.files.getValue(b).name)
        assertEquals("b 2", String(fake.fileAt(serverRoot, "b.txt")!!.bytes))
    }

    @Test
    fun serverErrorUsesBackoffAndKeepsChosenName() = runBlocking {
        val oldId = synced(NAME, "version 1")
        fake.failRenamesWith = 500

        change(NAME, "version 2")
        assertTrue(run().retry)
        assertEquals(BackupFileStatus.ARCHIVING, row(NAME).status)
        assertEquals(ARCHIVE_1, row(NAME).archiveName)

        // Повтор позже: время другое, имя прежней версии то же.
        fake.failRenamesWith = null
        now = T2
        engine = newEngine()
        assertFalse(run().retry)

        assertEquals(listOf(ARCHIVE_1, ARCHIVE_1), fake.renames)
        assertEquals(ARCHIVE_1, fake.files.getValue(oldId).name)
        assertEquals(BackupFileStatus.SYNCED, row(NAME).status)
    }

    @Test
    fun networkLossDuringArchivingAsksForRetry() = runBlocking {
        synced(NAME, "version 1")
        change(NAME, "version 2")
        fake.disconnectFileGets = 1

        val result = run()

        assertTrue(result.retry)
        assertEquals(BackupFileStatus.CHANGED, row(NAME).status)
        assertNull(row(NAME).archiveName)
    }

    @Test
    fun continuationsFinishArchivingWithoutNewScan() = runBlocking {
        val ids = (1..6).map { synced("f$it.txt", "v1 $it") }
        (1..6).forEach { change("f$it.txt", "v2 $it") }
        engine.run(WorkBudget.UNLIMITED, fullScan = true)!!.let { assertFalse(it.remaining) }
        val generation = db.backupSourceDao().get(sourceId)!!.scanGeneration

        (1..6).forEach { change("f$it.txt", "v3 $it", lastModified = 3) }
        now = T2
        var tick = 0L
        // Небольшой бюджет: прогон прерывается, продолжения дочитывают и архивируют без нового обхода.
        engine.run(WorkBudget(SyncClock { tick++ }, 6), fullScan = true)
        var continuations = 0
        while (true) {
            val next = engine.run(WorkBudget(SyncClock { tick++ }, 6), fullScan = false)!!
            continuations++
            if (!next.remaining) break
            assertTrue("$next ${db.backupTreeDao().files(sourceId).map { it.name + ":" + it.status }}", next.progress)
        }

        assertTrue(continuations > 1)
        assertEquals(generation + 1, db.backupSourceDao().get(sourceId)!!.scanGeneration)
        assertEquals(12, fake.renames.size)
        assertEquals(18, fake.files.size)
        assertTrue(db.backupTreeDao().files(sourceId).all { it.status == BackupFileStatus.SYNCED })
        ids.forEachIndexed { i, id -> assertEquals("f${i + 1}_20261104-120000.txt", fake.files.getValue(id).name) }
    }

    private fun newEngine() =
        FolderBackupEngine(db.backupSourceDao(), db.backupTreeDao(), remote, tree, access, accounts).also { it.wallClock = { now } }

    private suspend fun run(): FolderBackupRun = engine.run(WorkBudget.UNLIMITED, fullScan = true)!!

    /** Файл загружен первой версией; возвращает ID на Server. */
    private suspend fun synced(name: String, content: String): Long {
        tree.file(name, content)
        run()
        return row(name).serverFileId!!.also { assertEquals(BackupFileStatus.SYNCED, row(name).status) }
    }

    private fun change(name: String, content: String, lastModified: Long = 2) {
        tree.at(name).bytes = content.toByteArray()
        tree.at(name).lastModified = lastModified
    }

    private suspend fun row(name: String): BackupFile = db.backupTreeDao().files(sourceId).single { it.name == name }

    /** Прогон отменяется изнутри обработчика фейкового Server, как при гибели процесса; ответ теряется. */
    private suspend fun killDuring(arm: (kill: () -> Unit) -> Unit) = kotlinx.coroutines.coroutineScope {
        val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { engine.run(WorkBudget.UNLIMITED, fullScan = true) }
        arm { job.cancel() }
        job.start()
        job.join()
    }

    private fun sha(content: String) = FakeLibraryServer.sha256(content.toByteArray())
}

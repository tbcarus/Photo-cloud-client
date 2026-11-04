package ru.tbcarus.photo_cloud_client.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import ru.tbcarus.photo_cloud_client.media.WorkBudget
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Отключение источника (этап 5.5): общий метод Settings и Library через настоящий HTTP-слой и Room,
 * и реакция FolderBackupEngine на отключение — своё, с другого устройства и во время прогона.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupSourceDisableTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val account = BackupAccount("http://10.0.2.2:8080/", "a@test.local")
    private val otherAccount = BackupAccount("http://10.0.2.2:8080/", "b@test.local")
    private val uuid = UUID.randomUUID().toString()
    private val otherUuid = UUID.randomUUID().toString()

    private lateinit var server: MockWebServer
    private lateinit var fake: FakeLibraryServer
    private lateinit var db: AppDatabase
    private lateinit var tree: FakeTree
    private lateinit var otherTree: FakeTree
    private lateinit var accounts: FakeAccounts
    private lateinit var access: FakeAccess
    private lateinit var scheduler: FakeScheduler
    private lateinit var sourceRemote: BackupSourceRemote
    private lateinit var repository: BackupSourceRepository
    private lateinit var engine: FolderBackupEngine
    private var sourceId = 0L
    private var otherSourceId = 0L
    private var serverRoot = 0L
    private var otherServerRoot = 0L

    private val dao get() = db.backupSourceDao()

    @Before
    fun setUp() {
        fake = FakeLibraryServer()
        serverRoot = fake.source("Docs", uuid).id
        otherServerRoot = fake.source("Projects", otherUuid).id
        server = MockWebServer().apply {
            dispatcher = fake
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply { baseUrl = server.url("/").toString() }
        // Без автоповтора OkHttp: потерянный ответ DELETE должен дойти до клиента, а не быть молча повторён.
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val factory = ApiServiceFactory(baseUrlProvider, client, client, client)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        tree = FakeTree(context, "Docs")
        otherTree = FakeTree(context, "Projects")
        accounts = FakeAccounts(account)
        access = FakeAccess(context)
        scheduler = FakeScheduler(context)
        sourceRemote = BackupSourceRemote(factory)
        repository = BackupSourceRepository(dao, sourceRemote, access, accounts, scheduler)
        val documents = object : ru.tbcarus.photo_cloud_client.transfer.LocalDocuments(context) {
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
        engine = FolderBackupEngine(dao, db.backupTreeDao(), FolderBackupRemote(factory), documents, access, accounts)
        runBlocking {
            sourceId = addSource(tree, "Docs", serverRoot, uuid, createdAt = 1)
            otherSourceId = addSource(otherTree, "Projects", otherServerRoot, otherUuid, createdAt = 2)
        }
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    // --- Settings: общий метод с записью Room ---

    @Test
    fun activeSourceIsDisabledAndAllDataStays() = runBlocking {
        tree.file("a.txt", "a")
        tree.dir("Sub")
        tree.file("Sub/b.txt", "b")
        run()
        val before = dao.get(sourceId)!!
        val filesBefore = db.backupTreeDao().files(sourceId)
        val serverFiles = fake.files.size

        val result = repository.disable(account, serverRoot, uuid)

        assertEquals(SourceActionResult.Done, result)
        assertEquals(listOf("/api/v1/folders/$serverRoot/backup"), fake.disables)
        assertNull(fake.folders.getValue(serverRoot).backupSource)
        val after = dao.get(sourceId)!!
        assertEquals(RegistrationState.REMOTE_DISABLED, after.registrationState)
        assertNull(after.registrationError)
        assertEquals(before.copy(registrationState = RegistrationState.REMOTE_DISABLED), after)
        // Очередь и связи с Server не очищаются, SAF-разрешение не трогается, серверные данные на месте.
        assertEquals(filesBefore, db.backupTreeDao().files(sourceId))
        assertTrue(db.backupTreeDao().folderByPath(sourceId, "Sub") != null)
        assertEquals(serverFiles, fake.files.size)
        assertTrue(fake.folderAt(serverRoot, "Sub") != null)
        assertTrue(access.persisted.isEmpty())
        // Второй источник остаётся активным.
        assertEquals(RegistrationState.ACTIVE, dao.get(otherSourceId)!!.registrationState)
        assertEquals(otherUuid, fake.folders.getValue(otherServerRoot).backupSource)
    }

    @Test
    fun networkLossKeepsSourceActiveAndRetryUsesSameId() = runBlocking {
        fake.disconnectNextDisable = true

        val failed = repository.disable(account, serverRoot, uuid)

        assertTrue(failed is SourceActionResult.Failed)
        assertTrue((failed as SourceActionResult.Failed).message.endsWith(BackupSourceRepository.DISABLE_UNKNOWN_SUFFIX))
        assertEquals(RegistrationState.ACTIVE, dao.get(sourceId)!!.registrationState)
        assertEquals(uuid, fake.folders.getValue(serverRoot).backupSource)

        assertEquals(SourceActionResult.Done, repository.disable(account, serverRoot, uuid))
        assertEquals(List(2) { "/api/v1/folders/$serverRoot/backup" }, fake.disables)
        assertEquals(RegistrationState.REMOTE_DISABLED, dao.get(sourceId)!!.registrationState)
        assertTrue(fake.requests.none { it == "POST /api/v1/folders/backup" })
    }

    @Test
    fun serverErrorIsNotDisable() = runBlocking {
        fake.failDisablesWith = 503

        val result = repository.disable(account, serverRoot, uuid)

        assertTrue(result is SourceActionResult.Failed)
        assertEquals(RegistrationState.ACTIVE, dao.get(sourceId)!!.registrationState)
        assertEquals(uuid, fake.folders.getValue(serverRoot).backupSource)
    }

    @Test
    fun lostResponseAfterDisableIsFinishedByRepeatedDeleteOfSameId() = runBlocking {
        fake.dropNextDisable = true

        val lost = repository.disable(account, serverRoot, uuid)

        // Server уже отключил, но клиент этого не знает: локально источник не отключается.
        assertTrue(lost is SourceActionResult.Failed)
        assertNull(fake.folders.getValue(serverRoot).backupSource)
        assertEquals(RegistrationState.ACTIVE, dao.get(sourceId)!!.registrationState)

        // Повтор того же DELETE идемпотентен: 204 завершает операцию.
        assertEquals(SourceActionResult.Done, repository.disable(account, serverRoot, uuid))
        assertEquals(List(2) { "/api/v1/folders/$serverRoot/backup" }, fake.disables)
        assertEquals(RegistrationState.REMOTE_DISABLED, dao.get(sourceId)!!.registrationState)
        assertEquals(2, fake.folders.values.count { it.parentId == fake.autoUploads.id })
    }

    @Test
    fun unauthorizedNotFoundAndNotSourceRootAreNotDisable() = runBlocking {
        fake.failDisablesWith = 401
        assertEquals(SourceActionResult.Failed(BackupSourceRepository.AUTH_REQUIRED), repository.disable(account, serverRoot, uuid))
        fake.failDisablesWith = null

        assertEquals(SourceActionResult.Failed(BackupSourceRepository.DISABLE_NOT_FOUND), repository.disable(account, 999, uuid))
        val notRoot = repository.disable(account, fake.autoUploads.id, null)
        assertTrue(notRoot is SourceActionResult.Failed)

        assertEquals(RegistrationState.ACTIVE, dao.get(sourceId)!!.registrationState)
        assertEquals(uuid, fake.folders.getValue(serverRoot).backupSource)
    }

    @Test
    fun accountChangedBeforeRequestSendsNoDelete() = runBlocking {
        accounts.account.value = otherAccount

        val result = repository.disable(account, serverRoot, uuid)

        assertEquals(SourceActionResult.Failed(BackupSourceRepository.DISABLE_ACCOUNT_CHANGED), result)
        assertTrue(fake.disables.isEmpty())
        assertEquals(RegistrationState.ACTIVE, dao.get(sourceId)!!.registrationState)
    }

    @Test
    fun accountChangedDuringRequestDoesNotTouchRoom() = runBlocking {
        fake.onDisable = { accounts.account.value = otherAccount }

        val result = repository.disable(account, serverRoot, uuid)

        assertEquals(SourceActionResult.Failed(BackupSourceRepository.DISABLE_CONTEXT_CHANGED), result)
        assertEquals(RegistrationState.ACTIVE, dao.get(sourceId)!!.registrationState)
    }

    // --- Library: без локальной записи и записи чужого контекста ---

    @Test
    fun libraryDisableWorksWithoutLocalRecordAndTouchesOnlyMatchingRecord() = runBlocking {
        val foreign = fake.source("Phone B", UUID.randomUUID().toString())
        // Запись другого аккаунта с тем же ID корня не меняется, даже без UUID в запросе.
        val otherAccountRecord = addSource(tree, "Docs", serverRoot, UUID.randomUUID().toString(), createdAt = 3, owner = otherAccount)

        assertEquals(SourceActionResult.Done, repository.disable(account, foreign.id, foreign.backupSource))
        assertNull(foreign.backupSource)
        assertEquals(RegistrationState.ACTIVE, dao.get(sourceId)!!.registrationState)

        assertEquals(SourceActionResult.Done, repository.disable(account, serverRoot, null))
        assertEquals(RegistrationState.REMOTE_DISABLED, dao.get(sourceId)!!.registrationState)
        assertEquals(RegistrationState.ACTIVE, dao.get(otherAccountRecord)!!.registrationState)
    }

    // --- Worker ---

    @Test
    fun disabledBeforeUploadStopsQueueWithoutRequests() = runBlocking {
        tree.file("a.txt", "v1")
        run()
        assertEquals(SourceActionResult.Done, repository.disable(account, serverRoot, uuid))
        tree.at("a.txt").bytes = "v2".toByteArray()
        tree.at("a.txt").lastModified = 2
        tree.file("new.txt", "new")
        otherTree.file("p.txt", "p")
        fake.requests.clear()
        fake.uploads.clear()

        run()

        // Отключённый источник не получает ни upload, ни rename, ни регистрации; второй продолжает работу.
        assertEquals(listOf("p.txt"), fake.uploads)
        assertTrue(fake.renames.isEmpty())
        assertTrue(fake.requests.none { it == "POST /api/v1/folders/backup" })
        assertNull(fake.fileAt(serverRoot, "new.txt"))
        assertEquals("v1", String(fake.fileAt(serverRoot, "a.txt")!!.bytes))
        assertEquals(RegistrationState.REMOTE_DISABLED, dao.get(sourceId)!!.registrationState)
    }

    @Test
    fun disableDuringRunKeepsFinishedUploadAndStops() = runBlocking {
        tree.file("a.txt", "a")
        tree.file("b.txt", "b")
        tree.file("c.txt", "c")
        // Отключение (Server и Room, как после 204) приходит, пока Server принимает первый файл.
        fake.afterUpload = { file ->
            if (file.name == "a.txt") {
                fake.folders.getValue(serverRoot).backupSource = null
                runBlocking { dao.markDisabled(account.serverUrl, account.accountId, serverRoot, uuid) }
            }
        }

        val result = run()

        assertFalse(result.retry)
        assertEquals(listOf("a.txt"), fake.files.values.filter { it.folderId == serverRoot }.map { it.name })
        // Следующая запись отклонена Server, дальше очередь не идёт.
        assertTrue(fake.uploads.size <= 2)
        val source = dao.get(sourceId)!!
        assertEquals(RegistrationState.REMOTE_DISABLED, source.registrationState)
        // Состояние не возвращается в ACTIVE и не перезаписывается причиной удалённого отключения.
        assertNull(source.registrationError)
        assertEquals(3, db.backupTreeDao().files(sourceId).size)
        assertTrue(fake.requests.none { it == "POST /api/v1/folders/backup" })

        fake.uploads.clear()
        run()
        assertTrue(fake.uploads.isEmpty())
    }

    @Test
    fun disableFromOtherDeviceIsDetectedWithoutReRegistration() = runBlocking {
        tree.file("a.txt", "a")
        run()
        // Устройство B: своя Room без записи источника, тот же аккаунт и Server.
        val deviceB = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repositoryB = BackupSourceRepository(deviceB.backupSourceDao(), sourceRemote, access, accounts, scheduler)
            assertEquals(SourceActionResult.Done, repositoryB.disable(account, serverRoot, uuid))
        } finally {
            deviceB.close()
        }
        assertEquals(RegistrationState.ACTIVE, dao.get(sourceId)!!.registrationState)
        tree.file("later.txt", "later")
        val queue = db.backupTreeDao().files(sourceId)
        fake.uploads.clear()

        run()

        val source = dao.get(sourceId)!!
        assertEquals(RegistrationState.REMOTE_DISABLED, source.registrationState)
        assertEquals(FolderBackupEngine.SOURCE_DISABLED, source.registrationError)
        assertEquals(queue, db.backupTreeDao().files(sourceId))
        assertTrue(fake.uploads.none { it == "later.txt" })
        assertTrue(fake.requests.none { it == "POST /api/v1/folders/backup" })
        assertEquals(SourceActionResult.Failed(BackupSourceRepository.REMOTE_DISABLED), repository.retry(sourceId))
        assertTrue(fake.requests.none { it == "POST /api/v1/folders/backup" })
    }

    @Test
    fun lostConnectionIsNotRemoteDisable() = runBlocking {
        tree.file("a.txt", "a")
        server.shutdown()

        val result = run()

        assertTrue(result.retry)
        assertEquals(RegistrationState.ACTIVE, dao.get(sourceId)!!.registrationState)
        assertEquals(RegistrationState.ACTIVE, dao.get(otherSourceId)!!.registrationState)
    }

    private suspend fun run(): FolderBackupRun = engine.run(WorkBudget.UNLIMITED, fullScan = true)!!

    private suspend fun addSource(
        sourceTree: FakeTree, name: String, root: Long, sourceUuid: String, createdAt: Long, owner: BackupAccount = account
    ): Long = dao.insert(
        BackupSource(
            sourceUuid = sourceUuid, treeUri = sourceTree.treeUri.toString(), localName = name, serverName = name,
            serverRootFolderId = root, serverUrl = owner.serverUrl, accountId = owner.accountId,
            registrationState = RegistrationState.ACTIVE, registrationError = null,
            accessState = SourceAccess.AVAILABLE, createdAt = createdAt
        )
    )
}

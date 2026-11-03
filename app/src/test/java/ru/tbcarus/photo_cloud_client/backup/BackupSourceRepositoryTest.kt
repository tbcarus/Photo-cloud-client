package ru.tbcarus.photo_cloud_client.backup

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Подключение источника через настоящий HTTP-слой и Room: UUID до запроса, повтор, конфликт и аккаунты. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupSourceRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
    private val otherTree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AProjects")
    private val accountA = BackupAccount("http://10.0.2.2:8080/", "a@test.local")
    private val accountB = BackupAccount("http://10.0.2.2:8080/", "b@test.local")
    private val otherServer = BackupAccount("http://192.168.1.5:8080/", "a@test.local")

    private lateinit var server: MockWebServer
    private lateinit var fakeServer: FakeBackupServer
    private lateinit var db: AppDatabase
    private lateinit var dao: BackupSourceDao
    private lateinit var accounts: FakeAccounts
    private lateinit var access: FakeAccess
    private lateinit var repository: BackupSourceRepository

    @Before
    fun setUp() {
        fakeServer = FakeBackupServer()
        server = MockWebServer().apply {
            dispatcher = fakeServer
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply {
            baseUrl = server.url("/").toString()
        }
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.backupSourceDao()
        accounts = FakeAccounts(accountA)
        access = FakeAccess(context)
        repository = BackupSourceRepository(
            dao,
            BackupSourceRemote(ApiServiceFactory(baseUrlProvider, client, client, client)),
            access,
            accounts
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun connectSavesSourceBeforeRequestAndActivatesIt() = runBlocking {
        val result = repository.connect(tree, "Documents", "Documents")

        assertEquals(SourceActionResult.Done, result)
        val source = single(accountA)
        assertEquals(RegistrationState.ACTIVE, source.registrationState)
        assertEquals(100L, source.serverRootFolderId)
        assertEquals(tree.toString(), source.treeUri)
        assertEquals(accountA.serverUrl, source.serverUrl)
        assertEquals(accountA.accountId, source.accountId)
        assertEquals(listOf(tree), access.persisted)
        val request = fakeServer.requests.single()
        assertEquals("Documents", request.getString("name"))
        assertEquals(source.sourceUuid, request.getString("sourceUuid"))
        assertNull(source.registrationError)
    }

    @Test
    fun conflictKeepsNameWithoutSuffixAndAllowsRenameWithSameUuid() = runBlocking {
        fakeServer.takenNames += "documents"

        val result = repository.connect(tree, "Documents", "Documents")

        assertTrue(result is SourceActionResult.Failed)
        val failed = single(accountA)
        assertEquals(RegistrationState.REGISTRATION_FAILED, failed.registrationState)
        assertEquals("Documents", failed.serverName)
        assertTrue(failed.registrationError!!.contains("«Documents» уже занято"))
        assertEquals(listOf("Documents"), fakeServer.requests.map { it.getString("name") })

        assertEquals(SourceActionResult.Done, repository.retry(failed.id, "Documents телефон"))

        val active = single(accountA)
        assertEquals(RegistrationState.ACTIVE, active.registrationState)
        assertEquals("Documents телефон", active.serverName)
        assertEquals(setOf(failed.sourceUuid), fakeServer.requests.map { it.getString("sourceUuid") }.toSet())
    }

    @Test
    fun lostResponseIsResolvedByRepeatWithSameUuidAndName() = runBlocking {
        fakeServer.dropResponse = true

        val result = repository.connect(tree, "Documents", "Documents")

        assertTrue(result is SourceActionResult.Failed)
        val pending = single(accountA)
        assertEquals(RegistrationState.PENDING_REGISTRATION, pending.registrationState)
        assertNull(pending.serverRootFolderId)
        assertTrue(pending.registrationError!!.contains("Результат неизвестен"))
        assertEquals(1, fakeServer.folderCount())

        // Пока исход неизвестен, имя не меняется: Server мог уже связать UUID с прежним именем.
        assertEquals(SourceActionResult.Failed(BackupSourceRepository.NAME_LOCKED), repository.retry(pending.id, "Другое"))
        assertEquals(1, fakeServer.requests.size)

        assertEquals(SourceActionResult.Done, repository.retry(pending.id))

        val active = single(accountA)
        assertEquals(RegistrationState.ACTIVE, active.registrationState)
        assertEquals(100L, active.serverRootFolderId)
        assertEquals(1, fakeServer.folderCount())
        assertEquals(listOf("Documents", "Documents"), fakeServer.requests.map { it.getString("name") })
        assertEquals(setOf(pending.sourceUuid), fakeServer.requests.map { it.getString("sourceUuid") }.toSet())
    }

    @Test
    fun networkErrorKeepsPendingRecord() = runBlocking {
        server.shutdown()

        val result = repository.connect(tree, "Documents", "Documents")

        assertTrue(result is SourceActionResult.Failed)
        val pending = single(accountA)
        assertEquals(RegistrationState.PENDING_REGISTRATION, pending.registrationState)
        assertEquals("Documents", pending.serverName)
        assertTrue(pending.registrationError!!.startsWith("Нет связи с сервером"))
    }

    @Test
    fun activeSourceIsNeverRegisteredAgain() = runBlocking {
        repository.connect(tree, "Documents", "Documents")
        val active = single(accountA)

        assertEquals(SourceActionResult.Done, repository.retry(active.id))
        assertEquals(SourceActionResult.Done, repository.retry(active.id, "Другое"))

        assertEquals(1, fakeServer.requests.size)
        assertEquals("Documents", single(accountA).serverName)
    }

    @Test
    fun unauthorizedAndServerErrorsKeepPending() = runBlocking {
        fakeServer.forcedStatus = 401
        repository.connect(tree, "Documents", "Documents")
        assertEquals(BackupSourceRepository.AUTH_REQUIRED, single(accountA).registrationError)

        fakeServer.forcedStatus = 500
        repository.retry(single(accountA).id)

        val pending = single(accountA)
        assertEquals(RegistrationState.PENDING_REGISTRATION, pending.registrationState)
        assertTrue(pending.registrationError!!.contains("Результат неизвестен"))
    }

    @Test
    fun responseForAnotherSourceIsNotAccepted() = runBlocking {
        fakeServer.responseUuid = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

        repository.connect(tree, "Documents", "Documents")

        val pending = single(accountA)
        assertEquals(RegistrationState.PENDING_REGISTRATION, pending.registrationState)
        assertNull(pending.serverRootFolderId)
        assertEquals(BackupSourceRepository.MISMATCHED_RESPONSE, pending.registrationError)
    }

    @Test
    fun sourcesBelongToTheirAccountAndServer() = runBlocking {
        fakeServer.dropResponse = true
        repository.connect(tree, "Documents", "Documents")
        val pending = single(accountA)

        accounts.account.value = accountB
        assertEquals(SourceActionResult.Failed(BackupSourceRepository.OTHER_ACCOUNT), repository.retry(pending.id))
        assertTrue(repository.observe(accountB).first().isEmpty())
        assertTrue(repository.observe(otherServer).first().isEmpty())
        // Та же папка в другом аккаунте — отдельный источник со своим UUID.
        assertEquals(SourceActionResult.Done, repository.connect(tree, "Documents", "Documents B"))
        assertEquals(1, repository.observe(accountB).first().size)

        accounts.account.value = null
        assertEquals(SourceActionResult.NotConnected(BackupSourceRepository.NO_ACCOUNT), repository.connect(otherTree, "P", "P"))

        accounts.account.value = accountA
        val restored = single(accountA)
        assertEquals(pending.sourceUuid, restored.sourceUuid)
        assertEquals(RegistrationState.PENDING_REGISTRATION, restored.registrationState)
        assertEquals(2, fakeServer.requests.size)
    }

    @Test
    fun connectWithoutSavedPermissionDuplicateTreeOrInvalidNameCreatesNothing() = runBlocking {
        access.persistAllowed = false
        assertEquals(
            SourceActionResult.NotConnected(BackupSourceRepository.ACCESS_NOT_SAVED),
            repository.connect(tree, "Documents", "Documents")
        )
        access.persistAllowed = true
        assertTrue(repository.connect(tree, "Documents", "a/b") is SourceActionResult.NotConnected)
        assertTrue(repository.connect(tree, "Documents", "trailing.") is SourceActionResult.NotConnected)
        assertTrue(repository.observe(accountA).first().isEmpty())
        assertTrue(fakeServer.requests.isEmpty())

        repository.connect(tree, "Documents", "Documents")
        assertEquals(
            SourceActionResult.NotConnected(BackupSourceRepository.ALREADY_CONNECTED),
            repository.connect(tree, "Documents", "Другое")
        )
        assertEquals(1, fakeServer.requests.size)
    }

    @Test
    fun lostOrUnavailableAccessIsStoredWithoutTouchingRegistration() = runBlocking {
        repository.connect(tree, "Documents", "Documents")
        fakeServer.takenNames += "Projects"
        repository.connect(otherTree, "Projects", "Projects")

        access.states[tree] = SourceAccess.PERMISSION_LOST
        access.states[otherTree] = SourceAccess.UNAVAILABLE
        repository.refreshAccess(accountA)

        val sources = repository.observe(accountA).first().associateBy { it.treeUri }
        assertEquals(SourceAccess.PERMISSION_LOST, sources.getValue(tree.toString()).accessState)
        assertEquals(RegistrationState.ACTIVE, sources.getValue(tree.toString()).registrationState)
        assertEquals(100L, sources.getValue(tree.toString()).serverRootFolderId)
        assertEquals(SourceAccess.UNAVAILABLE, sources.getValue(otherTree.toString()).accessState)

        access.states.clear()
        repository.refreshAccess(accountA)
        assertTrue(repository.observe(accountA).first().all { it.accessState == SourceAccess.AVAILABLE })
        assertEquals(2, fakeServer.requests.size)
    }

    @Test
    fun onlyRejectedSourceCanBeCancelled() = runBlocking {
        repository.connect(tree, "Documents", "Documents")
        fakeServer.takenNames += "Projects"
        repository.connect(otherTree, "Projects", "Projects")
        val (active, failed) = repository.observe(accountA).first()

        repository.cancelFailed(active.id)
        repository.cancelFailed(failed.id)

        assertEquals(listOf(active.id), repository.observe(accountA).first().map { it.id })
    }

    private suspend fun single(account: BackupAccount): BackupSource = repository.observe(account).first().single()
}

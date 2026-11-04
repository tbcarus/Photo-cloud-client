package ru.tbcarus.photo_cloud_client.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import ru.tbcarus.photo_cloud_client.backup.BackupAccount
import ru.tbcarus.photo_cloud_client.backup.BackupSourceRemote
import ru.tbcarus.photo_cloud_client.backup.BackupSourceRepository
import ru.tbcarus.photo_cloud_client.backup.FakeAccess
import ru.tbcarus.photo_cloud_client.backup.FakeAccounts
import ru.tbcarus.photo_cloud_client.backup.FakeBackupServer
import ru.tbcarus.photo_cloud_client.backup.RegistrationState
import ru.tbcarus.photo_cloud_client.backup.SourceAccess
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.transfer.LocalDocuments
import java.net.InetAddress

/** Раздел Settings: выбор папки, отмена выбора, подключение и показ источников только текущего аккаунта. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FolderBackupViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
    private val accountA = BackupAccount("http://10.0.2.2:8080/", "a@test.local")

    private lateinit var server: MockWebServer
    private lateinit var fakeServer: FakeBackupServer
    private lateinit var db: AppDatabase
    private lateinit var accounts: FakeAccounts
    private lateinit var access: FakeAccess
    private lateinit var viewModel: FolderBackupViewModel

    private val documents = object : LocalDocuments(context) {
        var name: String? = "Documents"
        override fun treeName(treeUri: Uri): String? = name
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fakeServer = FakeBackupServer()
        server = MockWebServer().apply {
            dispatcher = fakeServer
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply {
            baseUrl = server.url("/").toString()
        }
        val client = OkHttpClient()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        accounts = FakeAccounts(accountA)
        access = FakeAccess(context)
        val repository = BackupSourceRepository(
            db.backupSourceDao(),
            BackupSourceRemote(ApiServiceFactory(baseUrlProvider, client, client, client)),
            access,
            accounts,
            ru.tbcarus.photo_cloud_client.backup.FakeScheduler(context)
        )
        viewModel = FolderBackupViewModel(repository, accounts, documents, db.backupTreeDao())
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun cancelledPickerChangesNothing() {
        viewModel.onFolderPicked(null)

        assertNull(viewModel.uiState.value.picked)
        assertTrue(access.persisted.isEmpty())
        assertTrue(fakeServer.requests.isEmpty())
    }

    @Test
    fun pickedFolderProposesItsNameAndCancelDoesNotConnect() = runBlocking {
        viewModel.onFolderPicked(tree)
        val picked = await { viewModel.uiState.value.picked }

        assertEquals("Documents", picked.localName)
        assertEquals("Documents", picked.serverName)

        viewModel.cancelPick()
        assertNull(viewModel.uiState.value.picked)
        assertTrue(db.backupSourceDao().list(accountA.serverUrl, accountA.accountId).isEmpty())
        assertTrue(access.persisted.isEmpty())
    }

    @Test
    fun providerWithoutNameFallsBackToTreeId() = runBlocking {
        documents.name = null

        viewModel.onFolderPicked(tree)

        assertEquals("Documents", await { viewModel.uiState.value.picked }.serverName)
    }

    @Test
    fun connectShowsActiveSourceAndConflictKeepsChosenName() = runBlocking {
        viewModel.onFolderPicked(tree)
        await { viewModel.uiState.value.picked }
        viewModel.onPickedNameChanged("Документы")
        viewModel.connect()

        val active = await {
            viewModel.uiState.value.sources.singleOrNull()
                ?.takeIf { it.registrationState == RegistrationState.ACTIVE && !viewModel.uiState.value.busy }
        }
        assertEquals(RegistrationState.ACTIVE, active.registrationState)
        assertEquals("Документы", active.serverName)
        assertNull(viewModel.uiState.value.picked)

        fakeServer.takenNames += "Projects"
        viewModel.onFolderPicked(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AProjects"))
        await { viewModel.uiState.value.picked?.takeIf { it.localName == "Documents" } }
        viewModel.onPickedNameChanged("Projects")
        viewModel.connect()

        await { viewModel.uiState.value.message?.takeIf { !viewModel.uiState.value.busy } }
        val failed = viewModel.uiState.value.sources.single { it.registrationState == RegistrationState.REGISTRATION_FAILED }
        assertEquals("Projects", failed.serverName)
        assertNull(viewModel.uiState.value.picked)
    }

    @Test
    fun invalidNameKeepsPickedFolderForCorrection() = runBlocking {
        viewModel.onFolderPicked(tree)
        await { viewModel.uiState.value.picked }
        viewModel.onPickedNameChanged("a/b")
        viewModel.connect()

        await { viewModel.uiState.value.message?.takeIf { !viewModel.uiState.value.busy } }
        assertEquals("a/b", viewModel.uiState.value.picked?.serverName)
        assertTrue(db.backupSourceDao().list(accountA.serverUrl, accountA.accountId).isEmpty())
    }

    @Test
    fun sourcesOfAnotherAccountAreHiddenAndRestoredOnReturn() = runBlocking {
        viewModel.onFolderPicked(tree)
        await { viewModel.uiState.value.picked }
        viewModel.connect()
        await { viewModel.uiState.value.sources.singleOrNull()?.takeIf { it.registrationState == RegistrationState.ACTIVE } }

        accounts.account.value = null
        await { viewModel.uiState.value.takeIf { it.account == null && it.sources.isEmpty() } }
        accounts.account.value = BackupAccount(accountA.serverUrl, "b@test.local")
        await { viewModel.uiState.value.takeIf { it.account?.accountId == "b@test.local" && it.sources.isEmpty() } }

        access.states[tree] = SourceAccess.PERMISSION_LOST
        accounts.account.value = accountA
        val restored = await {
            viewModel.uiState.value.sources.singleOrNull()?.takeIf { it.accessState == SourceAccess.PERMISSION_LOST }
        }
        assertEquals(RegistrationState.ACTIVE, restored.registrationState)
        assertEquals(1, fakeServer.requests.size)
    }

    // Состояние меняется асинхронно (Room и HTTP на своих потоках): ждём нужного значения.
    private suspend fun <T : Any> await(read: () -> T?): T = withTimeout(5_000) {
        while (true) {
            read()?.let { return@withTimeout it }
            kotlinx.coroutines.delay(10)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}

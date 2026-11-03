package ru.tbcarus.photo_cloud_client.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.mockito.Mockito.mock
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import java.util.concurrent.CopyOnWriteArrayList

/** Текущий аккаунт задаётся тестом: адрес и вход не читаются из настроек. */
class FakeAccounts(initial: BackupAccount?) :
    BackupAccountProvider(BaseUrlProvider(mock(ServerPreferences::class.java)), InMemoryTokenStorage()) {

    val account = MutableStateFlow(initial)

    override suspend fun current(): BackupAccount? = account.value

    override fun observe(): Flow<BackupAccount?> = account
}

/** Разрешения SAF без провайдера: какие деревья удалось сохранить и что вернёт проверка доступа. */
class FakeAccess(context: Context) : SourceAccessChecker(context) {
    var persistAllowed = true
    val persisted = mutableListOf<Uri>()
    val states = mutableMapOf<Uri, SourceAccess>()

    override fun persist(treeUri: Uri): Boolean {
        if (!persistAllowed) return false
        persisted += treeUri
        return true
    }

    override fun check(treeUri: Uri): SourceAccess = states[treeUri] ?: SourceAccess.AVAILABLE
}

/**
 * Правила POST /folders/backup Server: тот же UUID и имя — прежняя папка, тот же UUID с другим именем
 * или занятое имя (без учёта регистра) — 409. dropResponse: папка создаётся, но соединение рвётся до ответа.
 */
class FakeBackupServer : Dispatcher() {
    private val folders = mutableMapOf<String, Pair<String, Long>>()
    val takenNames = mutableSetOf<String>()
    val requests = CopyOnWriteArrayList<JSONObject>()
    var dropResponse = false
    var forcedStatus: Int? = null
    var responseUuid: String? = null
    private var nextId = 100L

    fun folderCount() = folders.size

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val body = JSONObject(request.body.readUtf8())
        requests += body
        forcedStatus?.let { return MockResponse().setResponseCode(it).setBody("""{"code":"X","message":"forced"}""") }
        val name = body.getString("name")
        val uuid = body.getString("sourceUuid")
        val existing = folders[uuid]
        val id = when {
            existing != null && existing.first == name -> existing.second
            existing != null || takenNames.any { it.equals(name, ignoreCase = true) } ->
                return MockResponse().setResponseCode(409).setBody("""{"code":"CONFLICT","message":"Folder with this name already exists"}""")
            else -> nextId++.also { folders[uuid] = name to it; takenNames += name }
        }
        if (dropResponse) {
            dropResponse = false
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        val json = JSONObject()
            .put("id", id).put("parentId", 7).put("name", name).put("folderType", "USER")
            .put("backupSource", responseUuid ?: uuid)
        return MockResponse().setHeader("Content-Type", "application/json").setBody(json.toString())
    }
}

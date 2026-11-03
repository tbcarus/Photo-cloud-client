package ru.tbcarus.photo_cloud_client.ui.screens.settings

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.backup.BackupAccount
import ru.tbcarus.photo_cloud_client.backup.BackupAccountProvider
import ru.tbcarus.photo_cloud_client.backup.BackupSource
import ru.tbcarus.photo_cloud_client.backup.BackupSourceRepository
import ru.tbcarus.photo_cloud_client.backup.SourceActionResult
import ru.tbcarus.photo_cloud_client.transfer.LocalDocuments
import javax.inject.Inject

/** Папка, выбранная в SAF, до подтверждения подключения: разрешение ещё не сохранено. */
data class PickedFolder(val uri: Uri, val localName: String, val serverName: String)

data class FolderBackupUiState(
    /** null — нет входа или адреса Server: источники не показываются и не подключаются. */
    val account: BackupAccount? = null,
    val sources: List<BackupSource> = emptyList(),
    val picked: PickedFolder? = null,
    val busy: Boolean = false,
    val message: String? = null
)

/** Раздел Settings «Автоматическая загрузка папок»: подключение источников без загрузки файлов. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FolderBackupViewModel @Inject constructor(
    private val repository: BackupSourceRepository,
    private val accounts: BackupAccountProvider,
    private val documents: LocalDocuments
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderBackupUiState())
    val uiState: StateFlow<FolderBackupUiState> = _uiState.asStateFlow()

    init {
        // Адрес восстанавливается из сохранённых настроек: Settings может открыться раньше Network.
        viewModelScope.launch { accounts.current() }
        viewModelScope.launch {
            accounts.observe()
                .flatMapLatest { account ->
                    if (account == null) flowOf(null to emptyList()) else repository.observe(account).map { account to it }
                }
                .collect { (account, sources) ->
                    val changed = account != _uiState.value.account
                    _uiState.update {
                        if (changed) FolderBackupUiState(account = account, sources = sources) else it.copy(sources = sources)
                    }
                    if (changed && account != null) repository.refreshAccess(account)
                }
        }
    }

    /** Возврат на экран: доступ к деревьям мог быть отозван в настройках Android. */
    fun refreshAccess() {
        val account = _uiState.value.account ?: return
        viewModelScope.launch { repository.refreshAccess(account) }
    }

    /** uri = null — пользователь закрыл выбор папки: ничего не меняется. */
    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { documents.treeName(uri) } ?: fallbackName(uri)
            _uiState.update { it.copy(picked = PickedFolder(uri, name, name), message = null) }
        }
    }

    fun onPickedNameChanged(name: String) {
        _uiState.update { state -> state.copy(picked = state.picked?.copy(serverName = name)) }
    }

    fun cancelPick() {
        _uiState.update { it.copy(picked = null, message = null) }
    }

    fun connect() {
        val picked = _uiState.value.picked ?: return
        perform { repository.connect(picked.uri, picked.localName, picked.serverName) }
    }

    fun retry(id: Long, serverName: String?) = perform { repository.retry(id, serverName) }

    fun cancelFailed(id: Long) = perform {
        repository.cancelFailed(id)
        SourceActionResult.Done
    }

    private fun perform(action: suspend () -> SourceActionResult) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = action()
            _uiState.update {
                when (result) {
                    SourceActionResult.Done -> it.copy(busy = false, picked = null)
                    // Запись сохранена и видна в списке с причиной: выбор закрывается.
                    is SourceActionResult.Failed -> it.copy(busy = false, picked = null, message = result.message)
                    // Записи нет: выбранная папка и имя остаются для исправления.
                    is SourceActionResult.NotConnected -> it.copy(busy = false, message = result.message)
                }
            }
        }
    }

    // Провайдер не сообщил имя: последняя часть ID дерева, например primary:Documents → Documents.
    private fun fallbackName(uri: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?.substringAfterLast(':')?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "Папка"
}

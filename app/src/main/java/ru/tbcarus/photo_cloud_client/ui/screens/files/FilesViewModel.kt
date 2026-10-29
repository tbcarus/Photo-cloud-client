package ru.tbcarus.photo_cloud_client.ui.screens.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.media.BackupStateMonitor
import ru.tbcarus.photo_cloud_client.media.MediaFileRepository
import ru.tbcarus.photo_cloud_client.media.PeriodicSyncCoordinator
import javax.inject.Inject

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val mediaFileRepository: MediaFileRepository,
    backupStateMonitor: BackupStateMonitor,
    private val periodicSyncCoordinator: PeriodicSyncCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    // Полный доступ к фото: Android об изменении не уведомляет, экран сообщает его при каждом возврате.
    // До первой проверки состояние backup не строится, чтобы не показать неверную причину.
    private val hasMediaAccess = MutableStateFlow<Boolean?>(null)

    init {
        // Подписываемся на Room; список обновляется автоматически при изменениях индекса
        viewModelScope.launch {
            mediaFileRepository.observeAll().collect { files ->
                _uiState.update { it.copy(files = files) }
            }
        }

        // Единственный источник состояния резервного копирования для карточки Files.
        viewModelScope.launch {
            backupStateMonitor.observe(hasMediaAccess.filterNotNull()).collect { state ->
                _uiState.update { it.copy(backupState = state) }
            }
        }
    }

    /** Фактический полный доступ к фото, перечитанный экраном при открытии и возврате в приложение. */
    fun onMediaAccessChecked(granted: Boolean) {
        hasMediaAccess.value = granted
    }

    /** true, если автосинк настроен (есть токены и baseUrl) — можно запрашивать media permission. */
    fun isAutoSyncConfigured(): Boolean = periodicSyncCoordinator.isAutoSyncConfigured()

    /**
     * Вызывать при входе на экран, когда media permission уже выдано.
     * Поднимает автосинк (observer + periodic задачи) без scan — просто гарантирует, что он запущен.
     */
    fun ensureAutoSyncStarted() {
        periodicSyncCoordinator.reconcile()
    }
}

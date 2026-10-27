package ru.tbcarus.photo_cloud_client.ui.screens.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.media.MediaFileRepository
import ru.tbcarus.photo_cloud_client.media.PeriodicSyncCoordinator
import ru.tbcarus.photo_cloud_client.media.SyncScheduler
import javax.inject.Inject

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val mediaFileRepository: MediaFileRepository,
    private val syncScheduler: SyncScheduler,
    private val periodicSyncCoordinator: PeriodicSyncCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    init {
        // Подписываемся на Room; список обновляется автоматически при изменениях индекса
        viewModelScope.launch {
            mediaFileRepository.observeAll().collect { files ->
                _uiState.update { it.copy(files = files) }
            }
        }

        // Отражаем состояние разовых задач Поставщика и Загрузчика в UI (немодальный индикатор).
        viewModelScope.launch {
            syncScheduler.observeIsSyncing().collect { syncing ->
                _uiState.update { it.copy(isSyncing = syncing) }
            }
        }

        // Итог последнего прогона sync — для card под кнопками.
        viewModelScope.launch {
            syncScheduler.observeLastSyncStatus().collect { status ->
                _uiState.update { it.copy(lastSyncStatus = status) }
            }
        }
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

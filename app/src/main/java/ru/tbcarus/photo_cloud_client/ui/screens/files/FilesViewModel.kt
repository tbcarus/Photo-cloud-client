package ru.tbcarus.photo_cloud_client.ui.screens.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.media.BackupStateMonitor
import ru.tbcarus.photo_cloud_client.media.FailedRetry
import ru.tbcarus.photo_cloud_client.media.FailedRetryResult
import ru.tbcarus.photo_cloud_client.media.MediaFileRepository
import ru.tbcarus.photo_cloud_client.media.PeriodicSyncCoordinator
import javax.inject.Inject

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val mediaFileRepository: MediaFileRepository,
    backupStateMonitor: BackupStateMonitor,
    private val periodicSyncCoordinator: PeriodicSyncCoordinator,
    private val failedRetry: FailedRetry
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

    /**
     * «Повторить ошибки»: все FAILED возвращаются в очередь Поставщика. Пока повтор выполняется, повторное
     * нажатие игнорируется; сам [FailedRetry] сериализует вызовы и второй раз записи не найдёт.
     */
    fun retryFailed() {
        if (_uiState.value.retryingFailed) return
        _uiState.update { it.copy(retryingFailed = true, retryMessage = null) }
        viewModelScope.launch {
            val message = try {
                when (val result = failedRetry.retryAll()) {
                    FailedRetryResult.NoPermission -> RetryMessage(
                        "Нет полного доступа к фото: файлы с ошибками не прочитать. Выдайте доступ на экране Settings.",
                        needsSettings = true
                    )
                    FailedRetryResult.NothingToRetry -> RetryMessage("Файлов с ошибками нет.")
                    is FailedRetryResult.Queued -> RetryMessage(
                        if (result.scheduled) "Файлы с ошибками возвращены в очередь: ${result.count}."
                        else "Файлы с ошибками возвращены в очередь: ${result.count}. " +
                            "Обработка начнётся при следующем запуске синхронизации."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RetryMessage("Не удалось повторить: ${e.message ?: "ошибка базы данных"}")
            } finally {
                _uiState.update { it.copy(retryingFailed = false) }
            }
            _uiState.update { it.copy(retryMessage = message) }
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

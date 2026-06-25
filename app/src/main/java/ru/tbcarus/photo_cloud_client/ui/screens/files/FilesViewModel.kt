package ru.tbcarus.photo_cloud_client.ui.screens.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tbcarus.photo_cloud_client.media.ChecksumPrecheckOutcome
import ru.tbcarus.photo_cloud_client.media.ChecksumPrecheckRepository
import ru.tbcarus.photo_cloud_client.media.FileUploadRepository
import ru.tbcarus.photo_cloud_client.media.MediaFileRepository
import ru.tbcarus.photo_cloud_client.media.ScanOutcome
import ru.tbcarus.photo_cloud_client.media.UploadOutcome
import javax.inject.Inject

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val mediaFileRepository: MediaFileRepository,
    private val checksumPrecheckRepository: ChecksumPrecheckRepository,
    private val fileUploadRepository: FileUploadRepository
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
    }

    fun scanPhotos() {
        if (_uiState.value.isScanning) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning     = true,
                    permissionDenied = false,
                    errorMessage   = null,
                    lastScanResult = null
                )
            }
            try {
                when (val outcome = mediaFileRepository.scanImages()) {
                    is ScanOutcome.Success -> _uiState.update {
                        it.copy(
                            lastScanResult   = outcome.result,
                            permissionDenied = false,
                            errorMessage     = null
                        )
                    }
                    is ScanOutcome.PermissionDenied -> {
                        // TODO: runtime-запрос разрешений будет добавлен отдельным UI-этапом.
                        _uiState.update {
                            it.copy(
                                permissionDenied = true,
                                lastScanResult   = null,
                                errorMessage     = null
                            )
                        }
                    }
                    is ScanOutcome.Error -> _uiState.update {
                        it.copy(
                            errorMessage     = outcome.message,
                            lastScanResult   = null,
                            permissionDenied = false
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun runPrecheck() {
        if (_uiState.value.isPrechecking) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPrechecking      = true,
                    errorMessage       = null,
                    lastPrecheckResult = null
                )
            }
            try {
                when (val outcome = checksumPrecheckRepository.runCameraPrecheck()) {
                    is ChecksumPrecheckOutcome.Success -> _uiState.update {
                        it.copy(
                            lastPrecheckResult = outcome.result,
                            errorMessage       = null
                        )
                    }
                    is ChecksumPrecheckOutcome.Error -> _uiState.update {
                        it.copy(
                            errorMessage       = outcome.message,
                            lastPrecheckResult = null
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(isPrechecking = false) }
            }
        }
    }

    fun uploadPending() {
        if (_uiState.value.isUploading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isUploading      = true,
                    errorMessage     = null,
                    lastUploadResult = null
                )
            }
            try {
                when (val outcome = fileUploadRepository.uploadPendingCameraFiles()) {
                    is UploadOutcome.Success -> _uiState.update {
                        it.copy(
                            lastUploadResult = outcome.result,
                            errorMessage     = null
                        )
                    }
                    is UploadOutcome.Error -> _uiState.update {
                        it.copy(
                            errorMessage     = outcome.message,
                            lastUploadResult = outcome.result
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(isUploading = false) }
            }
        }
    }

    fun clearMessages() {
        _uiState.update {
            it.copy(
                lastScanResult   = null,
                errorMessage     = null,
                permissionDenied = false
            )
        }
    }
}

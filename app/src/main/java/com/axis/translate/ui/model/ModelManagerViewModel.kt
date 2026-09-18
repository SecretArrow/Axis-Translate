package com.axis.translate.ui.model

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.model.InstalledModelInfo
import com.axis.translate.domain.model.ModelManifestEntry
import com.axis.translate.domain.model.ModelProgress
import com.axis.translate.domain.model.ModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for the AI model manager screen. */
data class ModelManagerUiState(
    val entries: List<ModelManifestEntry> = emptyList(),
    val installed: InstalledModelInfo? = null,
    val progress: ModelProgress? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val verifyResult: Boolean? = null,
    val reloading: Boolean = false,
)

/**
 * Model manager state holder (SPEC #35–#38): manifest listing, download /
 * install progress, SHA-256 verification, local import, removal, and engine
 * reload after a model change.
 */
class ModelManagerViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ModelManagerViewModel(container) }
            }
    }

    private val _ui = MutableStateFlow(ModelManagerUiState())
    val ui: StateFlow<ModelManagerUiState> = _ui.asStateFlow()

    init {
        // Manifest entries from assets.
        viewModelScope.launch {
            _ui.update { it.copy(entries = container.modelRepository.listEntries()) }
        }
        // Installed model drives the summary card.
        viewModelScope.launch {
            container.modelRepository.installed.collect { installed ->
                _ui.update {
                    it.copy(installed = installed, busy = false, verifyResult = null)
                }
            }
        }
        // Live install progress.
        viewModelScope.launch {
            container.modelRepository.progress.collect { progress ->
                _ui.update {
                    it.copy(
                        progress = progress,
                        busy = progress.status == ModelStatus.DOWNLOADING ||
                            progress.status == ModelStatus.VERIFYING ||
                            progress.status == ModelStatus.INSTALLING,
                    )
                }
            }
        }
    }

    /** Download + verify + install the given manifest entry. */
    fun install(entry: ModelManifestEntry) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            container.modelRepository.downloadAndInstall(entry)
                .onFailure { e ->
                    _ui.update {
                        it.copy(
                            error = e.message
                                ?: "Model download failed. Check your connection and try again.",
                            busy = false,
                        )
                    }
                }
        }
    }

    /** Delete the installed model package. */
    fun remove() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            container.modelRepository.removeModel()
            _ui.update { it.copy(busy = false) }
        }
    }

    /** Import a model package picked via SAF (content URI). */
    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            container.modelRepository.importModel(uri)
                .onFailure { e ->
                    _ui.update { it.copy(error = e.message ?: "Import failed") }
                }
            _ui.update { it.copy(busy = false) }
        }
    }

    /** Re-run the SHA-256 check over the installed model file. */
    fun verify() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, verifyResult = null) }
            val ok = container.modelRepository.verifyInstalled()
            _ui.update {
                it.copy(
                    verifyResult = ok,
                    busy = false,
                    error = if (!ok) {
                        "Model verification failed. The file may be incomplete or corrupted."
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** Reload the engine so it picks up the current model file. */
    fun reload() {
        viewModelScope.launch {
            _ui.update { it.copy(reloading = true) }
            container.translationManager.reset()
            _ui.update { it.copy(reloading = false) }
        }
    }

    fun dismissError() {
        _ui.update { it.copy(error = null) }
    }
}

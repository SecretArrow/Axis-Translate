package com.axis.translate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axis.translate.data.settings.AppSettings
import com.axis.translate.data.settings.ThemeMode
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.EngineRuntimeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for the Settings screen. */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val runtimeInfo: EngineRuntimeInfo? = null
)

/**
 * Settings screen state holder (SPEC #49): exposes the persisted
 * [AppSettings] stream plus live engine runtime info for the developer
 * section, and writes every toggle back through [AppContainer].
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _ui.update {
                    it.copy(
                        settings = settings,
                        runtimeInfo = container.translationManager.runtimeInfo()
                    )
                }
            }
        }
    }

    /** Re-read engine info, e.g. after returning from the model manager. */
    fun refreshRuntime() {
        _ui.update { it.copy(runtimeInfo = container.translationManager.runtimeInfo()) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { container.settingsRepository.setThemeMode(mode) }
    }

    fun setStyle(style: String) {
        viewModelScope.launch { container.settingsRepository.setTranslationStyle(style) }
    }

    fun setAutoDetect(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAutoDetectLanguage(enabled) }
    }

    fun setGlossary(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setGlossaryEnabled(enabled) }
    }

    fun setThreads(threads: Int) {
        viewModelScope.launch { container.settingsRepository.setInferenceThreads(threads) }
    }

    fun setContextLength(length: Int) {
        viewModelScope.launch { container.settingsRepository.setContextLength(length) }
    }

    fun setSavePhotos(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setSaveTranslatedPhotos(enabled) }
    }

    fun setLiveCamera(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setLiveCameraTranslation(enabled) }
    }

    fun setDeveloper(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setDeveloperMode(enabled) }
    }

    fun setFloating(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setFloatingTranslationEnabled(enabled) }
    }

    fun clearHistory() {
        viewModelScope.launch { container.historyRepository.clear() }
    }

    fun clearFavorites() {
        viewModelScope.launch { container.favoritesRepository.clear() }
    }
}

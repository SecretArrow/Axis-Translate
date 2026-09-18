package com.axis.translate.ui.glossary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.model.GlossaryTerm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for the glossary screen. */
data class GlossaryUiState(
    val terms: List<GlossaryTerm> = emptyList(),
    val enabled: Boolean = true,
    val loading: Boolean = true,
)

/**
 * Streams glossary terms together with the persisted glossary-enabled setting.
 * All mutations are pushed straight to the repositories; Room/DataStore re-emit.
 */
class GlossaryViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { GlossaryViewModel(container) }
        }
    }

    private val _state = MutableStateFlow(GlossaryUiState())
    val state: StateFlow<GlossaryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                container.glossaryRepository.observe(),
                container.settingsRepository.settings,
            ) { terms, settings -> terms to settings.glossaryEnabled }
                .collect { (terms, enabled) ->
                    _state.update {
                        it.copy(terms = terms, enabled = enabled, loading = false)
                    }
                }
        }
    }

    fun add(term: GlossaryTerm) {
        viewModelScope.launch { container.glossaryRepository.add(term) }
    }

    fun update(term: GlossaryTerm) {
        viewModelScope.launch { container.glossaryRepository.update(term) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { container.glossaryRepository.delete(id) }
    }

    fun toggleEnabled() {
        viewModelScope.launch {
            container.settingsRepository.setGlossaryEnabled(!state.value.enabled)
        }
    }
}

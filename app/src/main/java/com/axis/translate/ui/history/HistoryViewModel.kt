package com.axis.translate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.model.HistoryItem
import com.axis.translate.domain.model.InputType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for the history screen. */
data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val query: String = "",
    val filter: InputType? = null,
    val loading: Boolean = true
)

/**
 * Streams history from the repository with a debounced full-text search and a
 * client-side input-type filter. Mutations are pushed straight to the repository;
 * Room re-emits the updated list automatically.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { HistoryViewModel(container) }
        }
    }

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private val filterFlow = MutableStateFlow<InputType?>(null)

    init {
        viewModelScope.launch {
            combine(
                queryFlow.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS },
                filterFlow
            ) { searchText, type -> searchText to type }
                .flatMapLatest { (searchText, type) ->
                    val base = if (searchText.isBlank()) {
                        container.historyRepository.observe()
                    } else {
                        container.historyRepository.search(searchText.trim())
                    }
                    base.map { list -> list.filter { type == null || it.inputType == type } }
                }
                .collect { items ->
                    _state.update { it.copy(items = items, loading = false) }
                }
        }
    }

    fun setQuery(text: String) {
        queryFlow.value = text
        _state.update { it.copy(query = text) }
    }

    fun setFilter(type: InputType?) {
        filterFlow.value = type
        _state.update { it.copy(filter = type) }
    }

    fun delete(item: HistoryItem) {
        viewModelScope.launch { container.historyRepository.delete(item.id) }
    }

    fun clearAll() {
        viewModelScope.launch { container.historyRepository.clear() }
    }

    fun toggleFavorite(item: HistoryItem) {
        viewModelScope.launch {
            container.historyRepository.setFavorite(item.id, !item.isFavorite)
        }
    }
}

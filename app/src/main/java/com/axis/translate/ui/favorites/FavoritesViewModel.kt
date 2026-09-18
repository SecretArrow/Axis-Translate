package com.axis.translate.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.model.FavoriteItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for the favorites screen. */
data class FavoritesUiState(
    val items: List<FavoriteItem> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
)

/**
 * Streams starred translations with debounced search. Note editing and note
 * removal are pushed straight to the repository; Room re-emits automatically.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class FavoritesViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { FavoritesViewModel(container) }
        }
    }

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryFlow
                .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
                .flatMapLatest { searchText ->
                    if (searchText.isBlank()) {
                        container.favoritesRepository.observe()
                    } else {
                        flow { emit(container.favoritesRepository.search(searchText.trim())) }
                    }
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

    fun update(item: FavoriteItem) {
        viewModelScope.launch { container.favoritesRepository.update(item) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { container.favoritesRepository.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch { container.favoritesRepository.clear() }
    }
}

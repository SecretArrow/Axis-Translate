package com.axis.translate.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.model.ConversationTurn
import com.axis.translate.domain.model.HistoryItem
import com.axis.translate.domain.model.InputType
import com.axis.translate.domain.model.Language
import com.axis.translate.domain.model.TranslationException
import com.axis.translate.domain.model.TranslationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for the conversation screen. */
data class ConversationUiState(
    val turns: List<ConversationTurn> = emptyList(),
    val langA: Language = Language.byCode("en")!!,
    val langB: Language = Language.byCode("id")!!,
    val aToB: Boolean = true,
    val input: String = "",
    val translating: Boolean = false,
    val error: String? = null
)

/**
 * Conversation state holder: two-person back-and-forth translation with a
 * flippable direction, per-turn delete/retry, and history persistence.
 */
class ConversationViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null

    init {
        // Persisted language pair seeds the two conversation sides.
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _uiState.update { state ->
                    state.copy(
                        langA = Language.byCode(settings.sourceLanguageCode) ?: state.langA,
                        langB = Language.byCode(settings.targetLanguageCode) ?: state.langB
                    )
                }
            }
        }
    }

    fun setInput(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    /** Flips the direction of the next outgoing message (A→B becomes B→A). */
    fun toggleDirection() {
        _uiState.update { it.copy(aToB = !it.aToB) }
    }

    /** Swaps the two conversation languages. */
    fun swapLanguages() {
        _uiState.update { it.copy(langA = it.langB, langB = it.langA) }
    }

    /** Sends the current input as a new turn in the active direction. */
    fun send() {
        val state = _uiState.value
        val text = state.input.trim()
        if (text.isBlank()) return
        if (state.translating) return
        val (source, target) = if (state.aToB) state.langA to state.langB else state.langB to state.langA
        translateJob = viewModelScope.launch {
            _uiState.update { it.copy(translating = true, error = null) }
            try {
                val result = container.translationManager.translate(
                    TranslationRequest(
                        text = text,
                        source = source,
                        target = target,
                        inputType = InputType.CONVERSATION
                    )
                )
                val turn = ConversationTurn(
                    source = source,
                    target = target,
                    original = text,
                    translated = result.translatedText
                )
                _uiState.update {
                    it.copy(turns = it.turns + turn, input = "", translating = false)
                }
                container.historyRepository.add(
                    HistoryItem(
                        sourceCode = source.code,
                        targetCode = target.code,
                        sourceText = text,
                        translatedText = result.translatedText,
                        inputType = InputType.CONVERSATION,
                        durationMs = result.durationMs
                    )
                )
            } catch (cancelled: TranslationException.Cancelled) {
                _uiState.update { it.copy(translating = false) }
            } catch (cancellation: CancellationException) {
                _uiState.update { it.copy(translating = false) }
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        translating = false,
                        error = error.message ?: "Translation failed."
                    )
                }
            }
        }
    }

    fun deleteTurn(id: String) {
        _uiState.update { state ->
            state.copy(turns = state.turns.filterNot { it.id == id })
        }
    }

    fun clearAll() {
        _uiState.update { it.copy(turns = emptyList()) }
    }

    /** Re-translates the most recent turn with its original language pair. */
    fun retryLast() {
        val state = _uiState.value
        val last = state.turns.lastOrNull() ?: return
        if (state.translating) return
        translateJob = viewModelScope.launch {
            _uiState.update { it.copy(translating = true, error = null) }
            try {
                val result = container.translationManager.translate(
                    TranslationRequest(
                        text = last.original,
                        source = last.source,
                        target = last.target,
                        inputType = InputType.CONVERSATION
                    )
                )
                _uiState.update { current ->
                    current.copy(
                        translating = false,
                        turns = current.turns.map { turn ->
                            if (turn.id == last.id) {
                                turn.copy(translated = result.translatedText)
                            } else {
                                turn
                            }
                        }
                    )
                }
            } catch (cancelled: TranslationException.Cancelled) {
                _uiState.update { it.copy(translating = false) }
            } catch (cancellation: CancellationException) {
                _uiState.update { it.copy(translating = false) }
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        translating = false,
                        error = error.message ?: "Translation failed."
                    )
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun cancelTranslation() {
        container.translationManager.stop()
        translateJob?.cancel()
        _uiState.update { it.copy(translating = false) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConversationViewModel(container) }
        }
    }
}

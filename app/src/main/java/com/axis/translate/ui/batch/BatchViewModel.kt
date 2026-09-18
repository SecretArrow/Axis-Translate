package com.axis.translate.ui.batch

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.BatchQueue
import com.axis.translate.domain.model.BatchTask
import com.axis.translate.domain.model.Language
import com.axis.translate.service.BatchTranslationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for the Batch screen. */
data class BatchUiState(
    val items: List<BatchQueue.TaskState> = emptyList(),
    val running: Boolean = false,
    val paused: Boolean = false,
    val source: Language = Language.byCode("en")!!,
    val target: Language = Language.byCode("id")!!,
)

/**
 * Batch screen state holder: feeds the shared [BatchQueue] with tasks and
 * delegates execution to the foreground [BatchTranslationService], keeping
 * queue state visible even while the service runs in the background.
 */
class BatchViewModel(private val container: AppContainer) : ViewModel() {

    private val queue = BatchQueue.shared()

    private val _uiState = MutableStateFlow(BatchUiState())
    val uiState: StateFlow<BatchUiState> = _uiState.asStateFlow()

    init {
        // One combined snapshot of queue items + runner status.
        viewModelScope.launch {
            combine(queue.items, queue.isRunning, queue.isPaused) { items, running, paused ->
                Triple(items, running, paused)
            }.collect { (items, running, paused) ->
                _uiState.update {
                    it.copy(items = items, running = running, paused = paused)
                }
            }
        }
        // Persisted language pair is applied to newly added tasks.
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _uiState.update { state ->
                    state.copy(
                        source = when {
                            settings.sourceLanguageCode == Language.AUTO_CODE -> Language.AUTO
                            else -> Language.byCode(settings.sourceLanguageCode)
                                ?: Language.byCode("en")!!
                        },
                        target = Language.byCode(settings.targetLanguageCode)
                            ?: Language.byCode("id")!!,
                    )
                }
            }
        }
    }

    /** Adds a free-text task labeled by its queue position. */
    fun addTextTask(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val state = _uiState.value
        val label = "Text #${state.items.size + 1}"
        queue.add(
            BatchTask(
                label = label,
                sourceText = trimmed,
                source = state.source,
                target = state.target,
            ),
        )
    }

    /** Hands the queue over to the foreground batch service. */
    fun start(context: Context) {
        BatchTranslationService.start(context)
    }

    fun pause() = queue.pause()

    fun resume() = queue.resume()

    fun cancelAll() = queue.cancel()

    fun clear() = queue.clear()

    fun retry(id: String) = queue.retry(id)

    fun retryFailed() = queue.retryAllFailed()

    fun remove(id: String) = queue.remove(id)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { BatchViewModel(container) }
            }
    }
}

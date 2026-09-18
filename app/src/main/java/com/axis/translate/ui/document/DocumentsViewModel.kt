package com.axis.translate.ui.document

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.documents.DocumentContent
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

/** Immutable UI state for the Documents screen. */
data class DocumentsUiState(
    val document: DocumentContent? = null,
    val translated: String? = null,
    val translating: Boolean = false,
    val error: String? = null,
    val exportUri: Uri? = null,
    val source: Language = Language.AUTO,
    val target: Language = Language.byCode("id")!!
)

/**
 * Documents screen state holder: picks a TXT/MD/HTML document via SAF,
 * translates it through the local engine and exports a translated copy.
 */
class DocumentsViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null

    init {
        // Persisted language pair drives the document translation direction.
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _uiState.update { state ->
                    state.copy(
                        source = when {
                            settings.sourceLanguageCode == Language.AUTO_CODE -> Language.AUTO
                            else -> Language.byCode(settings.sourceLanguageCode) ?: state.source
                        },
                        target = Language.byCode(settings.targetLanguageCode) ?: state.target
                    )
                }
            }
        }
    }

    /** Extracts text from a picked or shared document URI. */
    fun pick(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(translating = true, error = null)
            }
            container.documentProcessor.extract(context, uri)
                .onSuccess { doc ->
                    _uiState.update {
                        it.copy(document = doc, translating = false, translated = null, exportUri = null)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            translating = false,
                            error = error.message ?: "Could not read this document."
                        )
                    }
                }
        }
    }

    /** Translates the loaded document with the persisted language pair. */
    fun translate() {
        val state = _uiState.value
        val doc = state.document ?: return
        if (state.translating) return
        if (doc.text.isBlank()) {
            _uiState.update { it.copy(error = "This document contains no readable text.") }
            return
        }
        translateJob = viewModelScope.launch {
            _uiState.update {
                it.copy(translating = true, error = null, translated = null, exportUri = null)
            }
            try {
                val request = TranslationRequest(
                    text = doc.text,
                    source = state.source,
                    target = state.target,
                    inputType = InputType.DOCUMENT,
                    maxOutputTokens = 1024
                )
                val result = container.translationManager.translate(request)
                _uiState.update {
                    it.copy(translated = result.translatedText, translating = false)
                }
                container.historyRepository.add(
                    HistoryItem(
                        sourceCode = state.source.code,
                        targetCode = state.target.code,
                        sourceText = doc.title,
                        translatedText = result.translatedText.take(2000),
                        inputType = InputType.DOCUMENT,
                        durationMs = result.durationMs
                    )
                )
            } catch (cancelled: TranslationException.Cancelled) {
                _uiState.update { it.copy(translating = false) }
            } catch (error: TranslationException) {
                _uiState.update {
                    it.copy(
                        translating = false,
                        error = error.message
                            ?: "The local AI engine could not complete the translation."
                    )
                }
            } catch (cancellation: CancellationException) {
                _uiState.update { it.copy(translating = false) }
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        translating = false,
                        error = error.message
                            ?: "The local AI engine could not complete the translation."
                    )
                }
            }
        }
    }

    /** Writes the translated copy and publishes the shareable URI. */
    fun export(context: Context) {
        val state = _uiState.value
        val doc = state.document ?: return
        val text = state.translated ?: return
        viewModelScope.launch {
            container.documentProcessor
                .exportTranslated(context, doc, text, state.target.code)
                .onSuccess { uri ->
                    _uiState.update { it.copy(exportUri = uri) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(error = error.message ?: "Export failed.")
                    }
                }
        }
    }

    /** Drops the loaded document and all derived state. */
    fun reset() {
        translateJob?.cancel()
        _uiState.update {
            it.copy(
                document = null,
                translated = null,
                translating = false,
                error = null,
                exportUri = null
            )
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
            initializer { DocumentsViewModel(container) }
        }
    }
}

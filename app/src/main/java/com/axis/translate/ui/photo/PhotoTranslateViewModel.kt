package com.axis.translate.ui.photo

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.OcrResult
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

/** How the image viewer presents original vs translated content. */
enum class CompareMode { ORIGINAL, TRANSLATED, SPLIT }

/** Immutable UI state for the photo translation screen. */
data class PhotoUiState(
    val bitmap: Bitmap? = null,
    val ocr: OcrResult? = null,
    val selectedRegion: Int? = null,
    val translatedRegions: List<String> = emptyList(),
    val translatedFull: String? = null,
    val comparing: CompareMode = CompareMode.ORIGINAL,
    val translating: Boolean = false,
    val error: String? = null,
    val source: Language = Language.byCode("en")!!,
    val target: Language = Language.byCode("id")!!
)

/**
 * Photo translation state holder: shared-image intake (via PendingInput),
 * offline OCR, tap-a-region and translate-all flows, and viewer compare modes.
 */
class PhotoTranslateViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(PhotoUiState())
    val uiState: StateFlow<PhotoUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null

    init {
        // Persisted language pair drives the translation direction.
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _uiState.update { state ->
                    state.copy(
                        source = Language.byCode(settings.sourceLanguageCode) ?: state.source,
                        target = Language.byCode(settings.targetLanguageCode) ?: state.target
                    )
                }
            }
        }
    }

    /** Runs offline OCR over a newly shared/captured image. */
    fun onImage(bitmap: Bitmap) {
        _uiState.update { it.copy(translating = true, error = null) }
        viewModelScope.launch {
            container.ocrEngine.recognize(bitmap, 0)
                .onSuccess { ocr ->
                    _uiState.update {
                        it.copy(
                            bitmap = bitmap,
                            ocr = if (ocr.isEmpty) null else ocr,
                            selectedRegion = null,
                            translatedRegions = emptyList(),
                            translatedFull = null,
                            comparing = CompareMode.ORIGINAL,
                            translating = false,
                            error = if (ocr.isEmpty) "No text detected in this image." else null
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            bitmap = bitmap,
                            translating = false,
                            error = "Offline OCR is not available."
                        )
                    }
                }
        }
    }

    /** Translates the concatenated text of every OCR region at once. */
    fun translateAll() {
        val state = _uiState.value
        val ocr = state.ocr ?: return
        if (state.translating) return
        translateJob = viewModelScope.launch {
            _uiState.update { it.copy(translating = true, error = null) }
            try {
                val text = ocr.regions.joinToString("\n\n") { it.text }
                val result = container.translationManager.translate(
                    TranslationRequest(
                        text = text,
                        source = state.source,
                        target = state.target,
                        inputType = InputType.PHOTO
                    )
                )
                _uiState.update {
                    it.copy(
                        translatedFull = result.translatedText,
                        translatedRegions = result.translatedText.split("\n\n"),
                        translating = false
                    )
                }
                container.historyRepository.add(
                    HistoryItem(
                        sourceCode = state.source.code,
                        targetCode = state.target.code,
                        sourceText = text,
                        translatedText = result.translatedText,
                        inputType = InputType.PHOTO,
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
                        error = error.message
                            ?: "The local AI engine could not complete the translation."
                    )
                }
            }
        }
    }

    /** Translates a single tapped OCR region (tap-to-translate). */
    fun translateRegion(index: Int) {
        val state = _uiState.value
        val region = state.ocr?.regions?.getOrNull(index) ?: return
        if (state.translating) return
        translateJob = viewModelScope.launch {
            _uiState.update { it.copy(translating = true, error = null, selectedRegion = index) }
            try {
                val result = container.translationManager.translate(
                    TranslationRequest(
                        text = region.text,
                        source = state.source,
                        target = state.target,
                        inputType = InputType.OCR
                    )
                )
                _uiState.update { current ->
                    val regions = current.translatedRegions.toMutableList()
                    if (regions.size <= index) {
                        repeat(index + 1 - regions.size) { regions.add("") }
                    }
                    regions[index] = result.translatedText
                    current.copy(
                        translatedRegions = regions,
                        selectedRegion = index,
                        translating = false
                    )
                }
                container.historyRepository.add(
                    HistoryItem(
                        sourceCode = state.source.code,
                        targetCode = state.target.code,
                        sourceText = region.text,
                        translatedText = result.translatedText,
                        inputType = InputType.OCR,
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
                        error = error.message
                            ?: "The local AI engine could not complete the translation."
                    )
                }
            }
        }
    }

    fun setCompare(mode: CompareMode) {
        _uiState.update { it.copy(comparing = mode) }
    }

    /** Clears the current image and every derived result. */
    fun reset() {
        translateJob?.cancel()
        _uiState.update {
            it.copy(
                bitmap = null,
                ocr = null,
                selectedRegion = null,
                translatedRegions = emptyList(),
                translatedFull = null,
                comparing = CompareMode.ORIGINAL,
                translating = false,
                error = null
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
            initializer { PhotoTranslateViewModel(container) }
        }
    }
}

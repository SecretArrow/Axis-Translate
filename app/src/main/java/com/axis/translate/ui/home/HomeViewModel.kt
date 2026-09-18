package com.axis.translate.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.model.FavoriteItem
import com.axis.translate.domain.model.GlossaryTerm
import com.axis.translate.domain.model.HistoryItem
import com.axis.translate.domain.model.InputType
import com.axis.translate.domain.model.Language
import com.axis.translate.domain.model.TranslationException
import com.axis.translate.domain.model.TranslationRequest
import com.axis.translate.domain.model.TranslationResult
import com.axis.translate.domain.model.TranslationState
import com.axis.translate.domain.model.TranslationStyle
import com.axis.translate.ui.navigation.PendingInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for the Home screen. */
data class HomeUiState(
    val source: Language = Language.AUTO,
    val target: Language = Language.byCode("en") ?: Language.FALLBACK_CATALOG.first(),
    val languages: List<Language> = Language.FALLBACK_CATALOG,
    val input: String = "",
    val result: TranslationResult? = null,
    val translating: Boolean = false,
    val progress: Float? = null,
    val partial: String? = null,
    val error: String? = null,
    val hasModel: Boolean = true,
    val recentPairs: List<Pair<String, String>> = emptyList(),
    val favoriteLanguages: List<String> = emptyList(),
    val style: TranslationStyle = TranslationStyle.STANDARD,
    val glossaryEnabled: Boolean = true,
    val showModelBanner: Boolean = false,
    val detectedLabel: String? = null,
)

/**
 * Home screen state holder: persisted language pair, translation execution
 * with streamed progress, history/favorite persistence, and share-target
 * text intake via [PendingInput].
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null

    init {
        // Persisted settings + installed model drive the language pair and banner.
        viewModelScope.launch {
            combine(
                container.settingsRepository.settings,
                container.modelRepository.installed,
            ) { settings, installed ->
                settings to installed
            }.collect { (settings, installed) ->
                val catalog = container.modelRepository.languageCatalog()
                    .ifEmpty { Language.FALLBACK_CATALOG }
                _uiState.update { state ->
                    state.copy(
                        source = catalog.firstOrNull { it.code == settings.sourceLanguageCode }
                            ?: Language.byCode(settings.sourceLanguageCode)
                            ?: Language.AUTO,
                        target = catalog.firstOrNull { it.code == settings.targetLanguageCode }
                            ?: Language.byCode(settings.targetLanguageCode)
                            ?: state.target,
                        languages = catalog,
                        hasModel = installed != null,
                        showModelBanner = installed == null,
                        recentPairs = settings.recentPairs,
                        favoriteLanguages = settings.favoriteLanguageCodes,
                        style = runCatching { TranslationStyle.valueOf(settings.translationStyle) }
                            .getOrDefault(TranslationStyle.STANDARD),
                        glossaryEnabled = settings.glossaryEnabled,
                    )
                }
            }
        }

        // Streamed progress / partial output from the translation manager.
        viewModelScope.launch {
            container.translationManager.state.collect { state ->
                if (state is TranslationState.Translating) {
                    _uiState.update {
                        it.copy(progress = state.progress, partial = state.partial)
                    }
                } else {
                    _uiState.update { it.copy(progress = null, partial = null) }
                }
            }
        }

        // Text shared into the app lands directly in the input field.
        viewModelScope.launch {
            PendingInput.sharedText.collect { text ->
                if (!text.isNullOrBlank()) {
                    _uiState.update { it.copy(input = text, result = null, error = null) }
                    // Only null out the slot we consumed; image/document payloads
                    // may be destined for other screens.
                    PendingInput.sharedText.value = null
                }
            }
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun paste(text: String) = onInputChange(text)

    fun setSource(language: Language) {
        val current = _uiState.value
        // Picking the current target as source flips the pair (translate-app UX).
        val newTarget = when {
            !language.isAuto && language.code == current.target.code ->
                if (current.source.isAuto) Language.byCode("en") ?: current.target else current.source
            else -> current.target
        }
        _uiState.update {
            it.copy(source = language, target = newTarget, result = null, detectedLabel = null)
        }
        viewModelScope.launch {
            container.settingsRepository.setSourceLanguage(language.code)
            if (newTarget.code != current.target.code) {
                container.settingsRepository.setTargetLanguage(newTarget.code)
            }
        }
    }

    fun setTarget(language: Language) {
        val current = _uiState.value
        // Picking the current source as target flips the pair.
        val newSource = when {
            !current.source.isAuto && language.code == current.source.code -> current.target
            else -> current.source
        }
        _uiState.update {
            it.copy(source = newSource, target = language, result = null, detectedLabel = null)
        }
        viewModelScope.launch {
            container.settingsRepository.setTargetLanguage(language.code)
            if (newSource.code != current.source.code) {
                container.settingsRepository.setSourceLanguage(newSource.code)
            }
        }
    }

    fun swap() {
        val current = _uiState.value
        val newSource: Language
        val newTarget: Language
        if (current.source.isAuto) {
            // An unknown source cannot be inverted: reuse the detected language
            // when we have one, otherwise flip the target into the source slot.
            val resolved = current.result?.detectedLanguage ?: current.target
            newSource = resolved
            newTarget = if (resolved.code == current.target.code) {
                Language.byCode("en") ?: current.target
            } else {
                current.target
            }
        } else {
            newSource = current.target
            newTarget = current.source
        }
        if (newSource.code == newTarget.code) return
        _uiState.update {
            it.copy(source = newSource, target = newTarget, result = null, detectedLabel = null)
        }
        viewModelScope.launch {
            container.settingsRepository.setSourceLanguage(newSource.code)
            container.settingsRepository.setTargetLanguage(newTarget.code)
        }
    }

    fun translate() {
        val current = _uiState.value
        val text = current.input.trim()
        if (text.isBlank()) {
            _uiState.update { it.copy(error = "Please enter some text to translate.") }
            return
        }
        if (current.translating) return

        translateJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    translating = true,
                    error = null,
                    result = null,
                    detectedLabel = null,
                    progress = null,
                    partial = null,
                )
            }
            try {
                val glossary: List<GlossaryTerm> =
                    if (current.glossaryEnabled) {
                        container.glossaryRepository.observeEnabled().first()
                    } else {
                        emptyList()
                    }
                val request = TranslationRequest(
                    text = text,
                    source = current.source,
                    target = current.target,
                    inputType = InputType.TEXT,
                    glossary = glossary,
                    style = current.style,
                )
                val result = container.translationManager.translate(request)
                _uiState.update {
                    it.copy(
                        translating = false,
                        result = result,
                        progress = null,
                        partial = null,
                        detectedLabel = if (current.source.isAuto) {
                            result.detectedLanguage?.displayName
                        } else {
                            null
                        },
                    )
                }
                container.historyRepository.add(
                    HistoryItem(
                        sourceCode = current.source.code,
                        targetCode = current.target.code,
                        sourceText = text,
                        translatedText = result.translatedText,
                        inputType = InputType.TEXT,
                        detectedLanguageCode = result.detectedLanguage?.code,
                        durationMs = result.durationMs,
                    ),
                )
                container.settingsRepository.pushRecentPair(
                    current.source.code,
                    current.target.code,
                )
            } catch (cancelled: TranslationException.Cancelled) {
                _uiState.update { it.copy(translating = false, progress = null, partial = null) }
            } catch (error: TranslationException) {
                _uiState.update {
                    it.copy(
                        translating = false,
                        error = error.message ?: "Translation failed.",
                    )
                }
            } catch (cancellation: CancellationException) {
                _uiState.update { it.copy(translating = false, progress = null, partial = null) }
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        translating = false,
                        error = error.message ?: "Translation failed.",
                    )
                }
            }
        }
    }

    fun cancel() {
        container.translationManager.stop()
        translateJob?.cancel()
        _uiState.update { it.copy(translating = false, progress = null, partial = null) }
    }

    fun clearResult() {
        _uiState.update { it.copy(result = null, detectedLabel = null, partial = null, progress = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setStyle(style: TranslationStyle) {
        _uiState.update { it.copy(style = style) }
        viewModelScope.launch {
            container.settingsRepository.setTranslationStyle(style.name)
        }
    }

    fun saveFavorite() {
        val state = _uiState.value
        val result = state.result ?: return
        viewModelScope.launch {
            container.favoritesRepository.add(
                FavoriteItem(
                    sourceCode = state.source.code,
                    targetCode = state.target.code,
                    sourceText = state.input.trim(),
                    translatedText = result.translatedText,
                ),
            )
        }
    }

    fun applyRecentPair(pair: Pair<String, String>) {
        val languages = _uiState.value.languages
        val source = when {
            pair.first == Language.AUTO_CODE -> Language.AUTO
            else -> languages.firstOrNull { it.code == pair.first }
                ?: Language.byCode(pair.first)
                ?: return
        }
        val target = languages.firstOrNull { it.code == pair.second }
            ?: Language.byCode(pair.second)
            ?: return
        _uiState.update { it.copy(source = source, target = target, result = null, detectedLabel = null) }
        viewModelScope.launch {
            container.settingsRepository.setSourceLanguage(source.code)
            container.settingsRepository.setTargetLanguage(target.code)
        }
    }

    fun toggleFavoriteLanguage(language: Language) {
        if (language.isAuto) return
        viewModelScope.launch {
            container.settingsRepository.toggleFavoriteLanguage(language.code)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { HomeViewModel(container) }
            }
    }
}

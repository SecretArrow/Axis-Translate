package com.axis.translate.ui.camera

import android.content.Context
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
import com.axis.translate.domain.model.TranslationResult
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the camera flow currently is. */
sealed class CameraPhase {
    /** Live camera preview (or the permission gate). */
    data object Previewing : CameraPhase()

    /** OCR finished; the user can edit the recognized text before translating. */
    data class Reviewing(
        val ocr: OcrResult,
        val editedText: String,
        val fromCamera: Boolean,
        val bitmap: Bitmap?
    ) : CameraPhase()

    /** Translation is in flight. */
    data object Translating : CameraPhase()

    /** Translation finished successfully. */
    data class Done(val sourceText: String, val result: TranslationResult) : CameraPhase()

    /** Translation failed terminally. */
    data class Failed(val message: String) : CameraPhase()
}

/** Immutable UI state for the camera screen. */
data class CameraUiState(
    val phase: CameraPhase = CameraPhase.Previewing,
    val liveEnabled: Boolean = true,
    val flashOn: Boolean = false,
    val source: Language = Language.byCode("en") ?: Language.AUTO,
    val target: Language = Language.byCode("id") ?: Language.byCode("en")!!,
    val translating: Boolean = false,
    val recognizing: Boolean = false,
    val error: String? = null
)

/**
 * Camera flow state holder: still capture / picked image → offline OCR →
 * editable review → translation with history persistence and optional
 * translated-photo saving.
 */
class CameraViewModel(
    private val container: AppContainer,
    private val appContext: Context? = null
) : ViewModel() {

    private val _ui = MutableStateFlow(CameraUiState())
    val ui: StateFlow<CameraUiState> = _ui.asStateFlow()

    /** Snapshot of the reviewing phase, kept so a cancelled translation can return to it. */
    private var lastReviewing: CameraPhase.Reviewing? = null
    private var translateJob: Job? = null

    init {
        // Persisted settings drive the language pair and live mode default.
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _ui.update { state ->
                    state.copy(
                        liveEnabled = settings.liveCameraTranslation,
                        source = Language.byCode(settings.sourceLanguageCode) ?: Language.AUTO,
                        target = Language.byCode(settings.targetLanguageCode) ?: Language.byCode("en")!!
                    )
                }
            }
        }
    }

    /** A still was captured by CameraX into [file]: decode + EXIF-rotate, then OCR. */
    fun onImageCaptured(file: File) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val decoded = ImageUtils.decodeDownsampled(file) ?: return@withContext null
                ImageUtils.rotateBitmap(decoded, ImageUtils.readExifRotation(file))
            }
            if (bitmap == null) {
                _ui.update { it.copy(error = "Could not read image") }
            } else {
                recognize(bitmap, fromCamera = true)
            }
        }
    }

    /** A ready-to-OCR bitmap arrived (gallery pick, share intake, live frame). */
    fun onImageReady(bitmap: Bitmap?, fromCamera: Boolean) {
        if (bitmap == null) {
            _ui.update { it.copy(error = "Could not read image") }
            return
        }
        recognize(bitmap, fromCamera)
    }

    fun updateEditedOcr(text: String) {
        val phase = _ui.value.phase
        if (phase is CameraPhase.Reviewing) {
            val updated = phase.copy(editedText = text)
            lastReviewing = updated
            _ui.update { it.copy(phase = updated) }
        }
    }

    fun retryOcr() {
        val phase = _ui.value.phase
        if (phase is CameraPhase.Reviewing && phase.bitmap != null) {
            recognize(phase.bitmap!!, phase.fromCamera)
        } else {
            _ui.update { it.copy(phase = CameraPhase.Previewing) }
        }
    }

    fun translateOcr() {
        val state = _ui.value
        val review = state.phase as? CameraPhase.Reviewing ?: return
        val text = review.editedText.trim()
        if (text.isBlank() || state.translating) return
        lastReviewing = review
        translateJob = viewModelScope.launch {
            _ui.update { it.copy(translating = true, phase = CameraPhase.Translating) }
            try {
                val inputType = if (review.fromCamera) InputType.PHOTO else InputType.OCR
                val result = container.translationManager.translate(
                    TranslationRequest(
                        text = text,
                        source = state.source,
                        target = state.target,
                        inputType = inputType
                    )
                )
                val photoPath = savePhotoIfEnabled(review)
                container.historyRepository.add(
                    HistoryItem(
                        sourceCode = state.source.code,
                        targetCode = state.target.code,
                        sourceText = text,
                        translatedText = result.translatedText,
                        inputType = inputType,
                        detectedLanguageCode = result.detectedLanguage?.code,
                        photoPath = photoPath,
                        durationMs = result.durationMs
                    )
                )
                _ui.update { it.copy(translating = false, phase = CameraPhase.Done(text, result)) }
            } catch (cancelled: TranslationException.Cancelled) {
                backToReviewing()
            } catch (cancellation: CancellationException) {
                backToReviewing()
                throw cancellation
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        translating = false,
                        phase = CameraPhase.Failed(e.message ?: "The local AI engine could not complete the translation.")
                    )
                }
            }
        }
    }

    fun cancelTranslation() {
        container.translationManager.stop()
    }

    /** Back to a fresh preview, keeping the persisted language pair and live flag. */
    fun reset() {
        _ui.update {
            CameraUiState(liveEnabled = it.liveEnabled, source = it.source, target = it.target)
        }
        lastReviewing = null
        translateJob = null
    }

    fun dismissError() {
        _ui.update { it.copy(error = null) }
    }

    fun setFlash(on: Boolean) {
        _ui.update { it.copy(flashOn = on) }
    }

    fun setLive(enabled: Boolean) {
        _ui.update { it.copy(liveEnabled = enabled) }
        viewModelScope.launch {
            container.settingsRepository.setLiveCameraTranslation(enabled)
        }
    }

    /** Capture or camera binding failed (also dismisses any stale error first). */
    fun onCameraError() {
        _ui.update { it.copy(error = "Camera could not be opened.") }
    }

    private fun recognize(bitmap: Bitmap, fromCamera: Boolean) {
        _ui.update { it.copy(recognizing = true, error = null) }
        viewModelScope.launch {
            container.ocrEngine.recognize(bitmap, 0)
                .onSuccess { ocr ->
                    if (ocr.isEmpty) {
                        _ui.update {
                            it.copy(recognizing = false, error = "No text detected in this image.")
                        }
                    } else {
                        val reviewing = CameraPhase.Reviewing(ocr, ocr.fullText, fromCamera, bitmap)
                        lastReviewing = reviewing
                        _ui.update { it.copy(recognizing = false, phase = reviewing) }
                    }
                }
                .onFailure {
                    _ui.update { it.copy(recognizing = false, error = "Offline OCR is not available.") }
                }
        }
    }

    /** Restores the reviewing snapshot after a cancelled translation (falls back to preview). */
    private fun backToReviewing() {
        val review = lastReviewing
        _ui.update { state ->
            if (review != null) {
                state.copy(translating = false, phase = review)
            } else {
                state.copy(translating = false, phase = CameraPhase.Previewing)
            }
        }
    }

    /** Copies the reviewed photo into filesDir/photos/<timestamp>.jpg when the user opted in. */
    private suspend fun savePhotoIfEnabled(review: CameraPhase.Reviewing): String? {
        val context = appContext ?: return null
        val bitmap = review.bitmap ?: return null
        if (!container.settingsRepository.current().saveTranslatedPhotos) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
                val photoFile = File(photosDir, "${System.currentTimeMillis()}.jpg")
                FileOutputStream(photoFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                photoFile.absolutePath
            }.getOrNull()
        }
    }

    companion object {
        /**
         * Standard factory; pulls the Application from CreationExtras so the
         * VM can save translated photos into filesDir without a hard
         * constructor dependency.
         */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CameraViewModel(
                    container,
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                )
            }
        }
    }
}

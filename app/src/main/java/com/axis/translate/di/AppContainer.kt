package com.axis.translate.di

import android.content.Context
import com.axis.translate.data.repo.GlossaryRepository
import com.axis.translate.data.repo.HistoryRepository
import com.axis.translate.data.repo.FavoritesRepository
import com.axis.translate.data.repo.ModelRepository
import com.axis.translate.data.settings.SettingsRepository
import com.axis.translate.domain.EngineConfig
import com.axis.translate.domain.LanguageDetector
import com.axis.translate.domain.OcrEngine
import com.axis.translate.domain.PromptBuilder
import com.axis.translate.domain.TextChunker
import com.axis.translate.domain.TranslationEngine
import com.axis.translate.domain.TranslationManager
import com.axis.translate.domain.documents.DocumentProcessor
import com.axis.translate.domain.voice.SpeechRecognitionHelper
import com.axis.translate.domain.voice.TextSpeaker

/**
 * Manual dependency container — deliberately framework-free for build speed
 * and testability. Swappable from instrumented tests via [TestOverrides].
 */
interface AppContainer {
    val settingsRepository: SettingsRepository
    val historyRepository: HistoryRepository
    val favoritesRepository: FavoritesRepository
    val glossaryRepository: GlossaryRepository
    val modelRepository: ModelRepository
    val languageDetector: LanguageDetector
    val textChunker: TextChunker
    val promptBuilder: PromptBuilder
    val ocrEngine: OcrEngine
    val textSpeaker: TextSpeaker
    val speechRecognizer: SpeechRecognitionHelper
    val documentProcessor: DocumentProcessor
    val translationManager: TranslationManager
}

/**
 * Test seams: instrumented tests can swap the engine before the activity
 * launches (e.g. [com.axis.translate.inference.FakeEngine] for e2e flows).
 */
object TestOverrides {
    @Volatile
    var engineFactoryOverride: (() -> TranslationEngine)? = null

    @Volatile
    var modelPathOverride: String? = null

    @Volatile
    var engineConfigOverride: EngineConfig? = null

    fun reset() {
        engineFactoryOverride = null
        modelPathOverride = null
        engineConfigOverride = null
    }
}

/**
 * Builds the production container. Wiring is lazy — repositories are created
 * on first access so cold start stays fast.
 */
class DefaultAppContainer(private val appContext: Context) : AppContainer {

    private val database by lazy {
        // Implemented in data layer: com.axis.translate.data.db.AxisDatabase
        com.axis.translate.data.db.AxisDatabase.get(appContext)
    }

    private val settingsRepo by lazy {
        com.axis.translate.data.settings.DataStoreSettingsRepository(appContext)
    }

    override val settingsRepository: SettingsRepository get() = settingsRepo

    override val historyRepository: HistoryRepository by lazy {
        com.axis.translate.data.repo.HistoryRepositoryImpl(database.historyDao())
    }

    override val favoritesRepository: FavoritesRepository by lazy {
        com.axis.translate.data.repo.FavoritesRepositoryImpl(database.favoriteDao())
    }

    override val glossaryRepository: GlossaryRepository by lazy {
        com.axis.translate.data.repo.GlossaryRepositoryImpl(database.glossaryDao())
    }

    override val modelRepository: ModelRepository by lazy {
        com.axis.translate.data.model.DefaultModelRepository(
            context = appContext,
            settingsRepository = settingsRepo,
        )
    }

    override val languageDetector: LanguageDetector by lazy {
        com.axis.translate.domain.HeuristicLanguageDetector()
    }

    override val ocrEngine: OcrEngine by lazy {
        com.axis.translate.data.ocr.MlKitOcrEngine(appContext)
    }

    override val textSpeaker: TextSpeaker by lazy {
        com.axis.translate.domain.voice.AndroidTextSpeaker(appContext)
    }

    override val speechRecognizer: SpeechRecognitionHelper by lazy {
        com.axis.translate.domain.voice.AndroidSpeechRecognizer()
    }

    override val documentProcessor: DocumentProcessor by lazy {
        com.axis.translate.domain.documents.HtmlDocumentProcessor()
    }

    override val textChunker: TextChunker by lazy {
        com.axis.translate.domain.SentenceTextChunker()
    }

    override val promptBuilder: PromptBuilder by lazy {
        com.axis.translate.domain.InstructionPromptBuilder()
    }

    override val translationManager: TranslationManager by lazy {
        TranslationManager(
            engineFactory = {
                TestOverrides.engineFactoryOverride?.invoke()
                    ?: com.axis.translate.inference.LlamaEngine()
            },
            modelPathProvider = {
                TestOverrides.modelPathOverride ?: modelRepository.installedModelPath()
            },
            engineConfigProvider = {
                TestOverrides.engineConfigOverride ?: EngineConfig(
                    threads = settingsRepository.current().inferenceThreads,
                    contextLength = settingsRepository.current().contextLength,
                )
            },
            textChunker = textChunker,
            promptBuilder = promptBuilder,
            languageDetector = languageDetector,
        )
    }
}

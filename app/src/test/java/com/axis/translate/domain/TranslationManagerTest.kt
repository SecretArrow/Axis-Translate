package com.axis.translate.domain

import com.axis.translate.domain.model.Language
import com.axis.translate.domain.model.TranslationException
import com.axis.translate.domain.model.TranslationRequest
import com.axis.translate.domain.model.TranslationState
import com.axis.translate.inference.FakeEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for [TranslationManager] wired with the domain defaults:
 * [SentenceTextChunker], [InstructionPromptBuilder], [HeuristicLanguageDetector]
 * and the [FakeEngine] test double.
 */
class TranslationManagerTest {

    private val english = Language.byCode("en")!!
    private val indonesian = Language.byCode("id")!!

    private fun newManager(
        modelPath: () -> String? = { "/models/x.gguf" },
        engineFactory: () -> TranslationEngine = { FakeEngine { "[fake]" } },
    ): TranslationManager = TranslationManager(
        engineFactory = engineFactory,
        modelPathProvider = modelPath,
        engineConfigProvider = { EngineConfig() },
        textChunker = SentenceTextChunker(),
        promptBuilder = InstructionPromptBuilder(),
        languageDetector = HeuristicLanguageDetector(),
    )

    @Test
    fun `translate succeeds and ends in Ready state`() = runTest {
        val manager = newManager()

        val result = manager.translate(
            TranslationRequest("Hello world", source = english, target = indonesian),
        )

        assertEquals("[fake]", result.translatedText)
        assertEquals(TranslationState.Ready, manager.state.value)
        assertEquals(english, result.detectedLanguage)
    }

    @Test
    fun `missing model throws ModelNotInstalled`() = runTest {
        val manager = newManager(modelPath = { null })

        try {
            manager.translate(
                TranslationRequest("Hello world", source = english, target = indonesian),
            )
            fail("Expected TranslationException.ModelNotInstalled")
        } catch (expected: TranslationException.ModelNotInstalled) {
            assertEquals(TranslationState.Error("AI model is not installed."), manager.state.value)
        }
    }

    @Test
    fun `blank text is rejected before any engine work`() = runTest {
        val manager = newManager()

        // TranslationRequest guards blank text at construction time, so a
        // whitespace-only payload can never reach translate(); the manager's
        // normalize() + TranslationException.EmptyInput branch is a
        // defense-in-depth check for payloads that normalize down to blank.
        try {
            TranslationRequest("   ", source = english, target = indonesian)
            fail("Expected the request to reject blank text")
        } catch (expected: IllegalArgumentException) {
            assertTrue(TranslationManager.normalize("   ").isBlank())
        }
    }

    @Test
    fun `multi paragraph input is chunked, per chunk inferred, and reassembled`() = runTest {
        var calls = 0
        val manager = newManager(
            engineFactory = { FakeEngine { calls += 1; "[fake $calls]" } },
        )

        val paragraph: (Int) -> String = { seed ->
            (1..10).joinToString(" ") {
                "This is sentence number $it of paragraph $seed with several words."
            }
        }
        // ~1.8k chars across three paragraphs: guaranteed to exceed the
        // default 900-char chunk size and produce multiple chunks.
        val text = paragraph(1) + "\n\n" + paragraph(2) + "\n\n" + paragraph(3)

        val result = manager.translate(
            TranslationRequest(text, source = english, target = indonesian),
        )

        assertTrue("engine should be invoked at least twice, was $calls", calls >= 2)
        assertTrue(
            "reassembled output should contain paragraph breaks",
            result.translatedText.contains("\n\n"),
        )
        assertTrue(result.translatedText.contains("[fake 1]"))
        assertEquals(TranslationState.Ready, manager.state.value)
    }
}

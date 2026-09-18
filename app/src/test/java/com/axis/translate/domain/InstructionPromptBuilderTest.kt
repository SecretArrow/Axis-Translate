package com.axis.translate.domain

import com.axis.translate.domain.model.GlossaryTerm
import com.axis.translate.domain.model.Language
import com.axis.translate.domain.model.TranslationStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [InstructionPromptBuilder]. */
class InstructionPromptBuilderTest {

    private val builder = InstructionPromptBuilder()
    private val english = Language.byCode("en")!!
    private val indonesian = Language.byCode("id")!!

    @Test
    fun `prompt names source and target languages`() {
        val prompt = builder.buildTranslationPrompt(
            source = english,
            target = indonesian,
            text = "Hello world",
        )

        assertTrue(prompt.contains("English"))
        assertTrue(prompt.contains("Indonesian"))
        assertTrue(
            prompt.contains(
                "Translate the text from English to Indonesian.",
            ),
        )
        assertTrue(prompt.contains("Hello world"))
        assertTrue(prompt.contains("Output ONLY the translated text, no quotes, no explanations."))
    }

    @Test
    fun `glossary terms appear when provided and are absent when empty`() {
        val glossary = listOf(
            GlossaryTerm(
                source = "cat",
                target = "kucing",
                sourceCode = "en",
                targetCode = "id",
            ),
            GlossaryTerm(
                source = "dog",
                target = "anjing",
                sourceCode = "en",
                targetCode = "id",
            ),
        )

        val withGlossary = builder.buildTranslationPrompt(
            source = english,
            target = indonesian,
            text = "The cat and the dog",
            glossary = glossary,
        )
        assertTrue(withGlossary.contains("Glossary (apply strictly):"))
        assertTrue(withGlossary.contains("\"cat\" -> \"kucing\""))
        assertTrue(withGlossary.contains("\"dog\" -> \"anjing\""))

        val withoutGlossary = builder.buildTranslationPrompt(
            source = english,
            target = indonesian,
            text = "The cat and the dog",
        )
        assertFalse(withoutGlossary.contains("Glossary"))
        assertFalse(withoutGlossary.contains("kucing"))
    }

    @Test
    fun `formal style adds the style line and standard does not`() {
        val formal = builder.buildTranslationPrompt(
            source = english,
            target = indonesian,
            text = "Hello",
            style = TranslationStyle.FORMAL,
        )
        assertTrue(formal.contains("Use a formal, professional register."))

        val standard = builder.buildTranslationPrompt(
            source = english,
            target = indonesian,
            text = "Hello",
            style = TranslationStyle.STANDARD,
        )
        assertFalse(standard.contains("formal"))
        assertFalse(standard.contains("register"))

        val natural = builder.buildTranslationPrompt(
            source = english,
            target = indonesian,
            text = "Hello",
            style = TranslationStyle.NATURAL,
        )
        assertTrue(natural.contains("Prefer natural, idiomatic phrasing."))

        val casual = builder.buildTranslationPrompt(
            source = english,
            target = indonesian,
            text = "Hello",
            style = TranslationStyle.CASUAL,
        )
        assertTrue(casual.contains("Use a casual, conversational register."))
    }

    @Test
    fun `auto source uses the detected language name`() {
        val prompt = builder.buildTranslationPrompt(
            source = Language.AUTO,
            target = indonesian,
            text = "こんにちは",
            detectedLanguage = Language("ja", "Japanese"),
        )
        assertTrue(prompt.contains("Japanese"))
        assertTrue(prompt.contains("Translate the text from Japanese to Indonesian."))
        assertFalse(prompt.contains("Auto Detect"))
    }

    @Test
    fun `auto source without detection falls back to the generic wording`() {
        val prompt = builder.buildTranslationPrompt(
            source = Language.AUTO,
            target = english,
            text = "Hello",
            detectedLanguage = null,
        )
        assertTrue(prompt.contains("the auto-detected language"))
    }

    @Test
    fun `payload is delimited after the Text marker`() {
        val prompt = builder.buildTranslationPrompt(
            source = english,
            target = indonesian,
            text = "Hello\nsecond line",
        )
        val expected = buildString {
            append("You are a professional translation engine. ")
            append("Translate the text from English to Indonesian.\n")
            append("Output ONLY the translated text, no quotes, no explanations.\n")
            append("Text:\n")
            append("\"\"\"\n")
            append("Hello\nsecond line")
            append("\n")
            append("\"\"\"")
        }
        assertEquals(expected, prompt)
    }
}

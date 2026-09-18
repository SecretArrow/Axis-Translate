package com.axis.translate.domain

import com.axis.translate.domain.model.GlossaryTerm
import com.axis.translate.domain.model.Language
import com.axis.translate.domain.model.TranslationStyle

/**
 * [PromptBuilder] producing a plain-text instruction prompt (SPEC #30):
 * role line, optional glossary line, optional style line, output contract,
 * and the payload wrapped in triple quotes so the model returns only the
 * translation.
 */
class InstructionPromptBuilder : PromptBuilder {

    override fun buildTranslationPrompt(
        source: Language,
        target: Language,
        text: String,
        glossary: List<GlossaryTerm>,
        style: TranslationStyle,
        detectedLanguage: Language?
    ): String {
        val sourceName = if (source.isAuto) {
            detectedLanguage?.displayName ?: "the auto-detected language"
        } else {
            source.displayName
        }

        val lines = mutableListOf<String>()
        lines += "You are a professional translation engine. " +
            "Translate the text from $sourceName to ${target.displayName}."

        val activeGlossary = glossary.filter(GlossaryTerm::enabled)
        if (activeGlossary.isNotEmpty()) {
            val terms = activeGlossary.joinToString("; ") {
                "\"${it.source}\" -> \"${it.target}\""
            }
            lines += "Glossary (apply strictly): $terms"
        }

        when (style) {
            TranslationStyle.NATURAL -> lines += "Prefer natural, idiomatic phrasing."
            TranslationStyle.FORMAL -> lines += "Use a formal, professional register."
            TranslationStyle.CASUAL -> lines += "Use a casual, conversational register."
            TranslationStyle.STANDARD -> Unit
        }

        lines += "Output ONLY the translated text, no quotes, no explanations."
        lines += "Text:"

        return buildString {
            append(lines.joinToString("\n"))
            append('\n')
            append(PAYLOAD_DELIMITER)
            append('\n')
            append(text)
            append('\n')
            append(PAYLOAD_DELIMITER)
        }
    }

    private companion object {
        const val PAYLOAD_DELIMITER = "\"\"\""
    }
}

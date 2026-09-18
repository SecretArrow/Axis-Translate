package com.axis.translate.domain

/**
 * [TextChunker] implementation following SPEC #16:
 * paragraph -> sentence -> punctuation -> token boundary.
 *
 * Invariant: [chunk] output joined with "\n\n" reproduces the (trimmed) input
 * whenever no sentence has to be split across chunks — this is exactly how
 * [TranslationManager.reassemble] joins chunk outputs. When a sentence or a
 * paragraph must be split across chunks the separator at the split point is
 * replaced by the chunk join, so no words are ever lost or duplicated.
 */
class SentenceTextChunker : TextChunker {

    override fun chunk(text: String, maxChars: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (maxChars <= 0 || trimmed.length <= maxChars) return listOf(trimmed)

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString())
                current.setLength(0)
            }
        }

        /**
         * Emits the whitespace-split parts of a single oversized sentence.
         * Each part is at most [maxChars] long unless it is a single
         * unbreakable word (which becomes its own chunk by design).
         */
        fun emitWordParts(piece: String) {
            for (part in splitAtWhitespace(piece, maxChars)) {
                var p = part
                if (current.isEmpty()) {
                    p = p.trimStart()
                    if (p.isEmpty()) continue
                } else if (current.length + p.length > maxChars) {
                    flush()
                    p = p.trimStart()
                    if (p.isEmpty()) continue
                }
                current.append(p)
            }
        }

        // Split into paragraphs on blank lines. Paragraphs are joined inside a
        // chunk with "\n\n", mirroring the chunk join used on reassembly.
        val paragraphs = trimmed.split(PARAGRAPH_BREAK)

        for (paragraph in paragraphs) {
            val sentences = splitSentences(paragraph)
            for ((index, rawSentence) in sentences.withIndex()) {
                val startsParagraph = index == 0
                if (current.isEmpty()) {
                    val piece = rawSentence.trimStart()
                    if (piece.isEmpty()) continue
                    if (piece.length <= maxChars) {
                        current.append(piece)
                    } else {
                        emitWordParts(piece)
                    }
                } else {
                    val separator = if (startsParagraph) "\n\n" else ""
                    if (current.length + separator.length + rawSentence.length <= maxChars) {
                        current.append(separator).append(rawSentence)
                    } else {
                        flush()
                        val piece = rawSentence.trimStart()
                        if (piece.isEmpty()) continue
                        if (piece.length <= maxChars) {
                            current.append(piece)
                        } else {
                            emitWordParts(piece)
                        }
                    }
                }
            }
        }
        flush()
        return chunks
    }

    /**
     * Splits a paragraph into sentence pieces whose direct concatenation is
     * the paragraph itself: all whitespace following a terminator stays
     * attached to the front of the next sentence, so intra-chunk joins are
     * byte-identical to the source (internal single newlines included).
     *
     * - Latin terminators [.!?…] only end a sentence when followed by
     *   whitespace or end of paragraph (avoids "3.14", "e.g." mid-word cuts).
     * - CJK terminators 。！？ always end a sentence (CJK uses no spaces).
     */
    internal fun splitSentences(paragraph: String): List<String> {
        if (paragraph.isEmpty()) return emptyList()
        val pieces = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < paragraph.length) {
            val c = paragraph[i]
            sb.append(c)
            when {
                CJK_TERMINATORS.indexOf(c) >= 0 -> {
                    pieces.add(sb.toString())
                    sb.setLength(0)
                }
                LATIN_TERMINATORS.indexOf(c) >= 0 -> {
                    val next = i + 1
                    if (next >= paragraph.length || paragraph[next].isWhitespace()) {
                        pieces.add(sb.toString())
                        sb.setLength(0)
                    }
                }
            }
            i++
        }
        if (sb.isNotEmpty()) pieces.add(sb.toString())
        return pieces
    }

    /**
     * Splits an oversized sentence at whitespace boundaries, never mid-word.
     * The whitespace at each split point stays attached to the following
     * part (single newlines inside the sentence are preserved as-is).
     * A word longer than [maxChars] becomes its own part.
     */
    internal fun splitAtWhitespace(sentence: String, maxChars: Int): List<String> {
        val parts = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxChars) {
            var cut = -1
            val upper = minOf(maxChars, rest.length)
            for (i in upper downTo 1) {
                if (rest[i].isWhitespace()) {
                    cut = i
                    break
                }
            }
            if (cut == -1) {
                // No whitespace within the limit: the first word is unbreakable.
                var wordEnd = rest.length
                for (i in 1 until rest.length) {
                    if (rest[i].isWhitespace()) {
                        wordEnd = i
                        break
                    }
                }
                parts.add(rest.substring(0, wordEnd))
                rest = rest.substring(wordEnd)
            } else {
                parts.add(rest.substring(0, cut))
                rest = rest.substring(cut)
            }
        }
        if (rest.isNotEmpty()) parts.add(rest)
        return parts
    }

    private companion object {
        /** One or more blank lines separate paragraphs. */
        val PARAGRAPH_BREAK = Regex("\n\n+")
        const val LATIN_TERMINATORS = ".!?…"
        const val CJK_TERMINATORS = "。！？"
    }
}

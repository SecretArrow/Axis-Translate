package com.axis.translate.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SentenceTextChunker].
 *
 * Core invariant under test: chunks joined with "\n\n" reproduce the
 * (trimmed) input — the exact join [TranslationManager.reassemble] performs.
 */
class SentenceTextChunkerTest {

    private val chunker = SentenceTextChunker()

    @Test
    fun `single sentence is returned as one chunk and round trips`() {
        val text = "Hello world, this is a single sentence."
        val chunks = chunker.chunk(text)

        assertEquals(listOf(text), chunks)
        assertEquals(text, chunks.joinToString("\n\n"))
    }

    @Test
    fun `multi paragraph text round trips when chunked`() {
        val text = "First paragraph here.\n\n" +
            "Second paragraph with more words.\n\n" +
            "Third and final paragraph of the test."
        // 97 chars total, so maxChars=60 forces actual chunking.
        val chunks = chunker.chunk(text, maxChars = 60)

        assertEquals(2, chunks.size)
        chunks.forEach { assertTrue("chunk too long: ${it.length}", it.length <= 60) }
        assertEquals(text, chunks.joinToString("\n\n"))
    }

    @Test
    fun `long text is split into bounded chunks without losing words`() {
        val text = (1..30).joinToString(" ") {
            "Sentence number $it has some words in it."
        }
        assertTrue(text.length >= 500)

        val chunks = chunker.chunk(text, maxChars = 100)
        assertTrue(chunks.size >= 2)

        val originalWords = text.split(whitespace).filter { it.isNotBlank() }
        val joined = chunks.joinToString("\n\n")
        val joinedWords = joined.split(whitespace).filter { it.isNotBlank() }

        // No words lost, none duplicated (multiset compare).
        assertEquals(originalWords.sorted(), joinedWords.sorted())

        // Every chunk is within the limit (no unbreakable words in this text).
        chunks.forEach { chunk ->
            assertTrue("chunk exceeds maxChars: ${chunk.length}", chunk.length <= 100)
        }
    }

    @Test
    fun `chunks never contain the middle of a word`() {
        val text = "supercalifragilisticexpialidocious".let { word ->
            List(10) { word }.joinToString(" ")
        }
        val chunks = chunker.chunk(text, maxChars = 40)

        val originalWords = text.split(whitespace).filter { it.isNotBlank() }
        val vocabulary = originalWords.toHashSet()

        // Splits only ever happen at whitespace: every token inside every chunk
        // is a complete word of the original vocabulary.
        chunks.forEach { chunk ->
            chunk.split(whitespace).filter { it.isNotBlank() }.forEach { token ->
                assertTrue("fragmented word: $token", token in vocabulary)
            }
        }
        assertEquals(
            originalWords.sorted(),
            chunks.joinToString("\n\n").split(whitespace).filter { it.isNotBlank() }.sorted(),
        )
    }

    @Test
    fun `a single unbreakable word longer than maxChars becomes its own chunk`() {
        val word = "a".repeat(120)
        val chunks = chunker.chunk("$word tail", maxChars = 50)

        assertEquals(2, chunks.size)
        assertEquals(word, chunks[0]) // allowed to exceed maxChars: unbreakable
        assertEquals("tail", chunks[1])

        val single = chunker.chunk(word, maxChars = 50)
        assertEquals(listOf(word), single)
        assertEquals(word, single.joinToString("\n\n"))
    }

    @Test
    fun `internal single newlines inside a paragraph are preserved`() {
        val text = "First line here.\nSecond line there.\nThird one now."
        val chunks = chunker.chunk(text, maxChars = 40)

        // The first two lines fit together in one chunk; the internal "\n"
        // separating them must survive.
        assertEquals(
            "First line here.\nSecond line there.",
            chunks.first(),
        )
        assertEquals(listOf("First line here.\nSecond line there.", "Third one now."), chunks)

        // Nothing lost overall.
        val originalWords = text.split(whitespace).filter { it.isNotBlank() }
        assertEquals(
            originalWords.sorted(),
            chunks.joinToString("\n\n").split(whitespace).filter { it.isNotBlank() }.sorted(),
        )
    }

    @Test
    fun `blank input produces an empty result whose join is blank`() {
        val empty = chunker.chunk("")
        assertTrue(empty.isEmpty() || empty.all { it.isBlank() })
        assertEquals("", empty.joinToString("\n\n"))

        val blank = chunker.chunk("   \n  \n   ")
        assertTrue(blank.isEmpty() || blank.all { it.isBlank() })
        assertEquals("", blank.joinToString("\n\n"))
    }

    @Test
    fun `cjk sentence terminators split long paragraphs`() {
        val sentence = "これは一つの文です。"
        val text = List(20) { sentence }.joinToString("")
        assertTrue(text.length > 100)

        val chunks = chunker.chunk(text, maxChars = 30)
        assertTrue(chunks.size >= 2)
        chunks.forEach { assertTrue(it.length <= 30) }
        // No characters lost: the only inserted characters are the "\n\n"
        // chunk joins (the source itself contains no newlines).
        assertEquals(text, chunks.joinToString("\n\n").replace("\n\n", ""))
    }

    private companion object {
        val whitespace = Regex("\\s+")
    }
}

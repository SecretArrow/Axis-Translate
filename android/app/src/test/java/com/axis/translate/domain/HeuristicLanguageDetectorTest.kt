package com.axis.translate.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [HeuristicLanguageDetector]. */
class HeuristicLanguageDetectorTest {

    private val detector = HeuristicLanguageDetector()

    private fun assertDetects(text: String, expectedCode: String) {
        val detection = detector.detect(text)
        assertNotNull("expected detection for: $text", detection)
        assertEquals(expectedCode, detection!!.language.code)
    }

    @Test
    fun `english is detected by stopwords`() {
        assertDetects(
            "The quick brown fox jumps over the lazy dog near the river",
            "en"
        )
    }

    @Test
    fun `indonesian is detected by stopwords`() {
        assertDetects(
            "Selamat pagi, bagaimana kabar kamu hari ini yang baik",
            "id"
        )
    }

    @Test
    fun `japanese is detected by kana script`() {
        val detection = detector.detect("こんにちは世界、おはようございます今日はいい天気ですね")
        assertNotNull(detection)
        assertEquals("ja", detection!!.language.code)
        assertTrue(detection.confidence >= 0.6f)
    }

    @Test
    fun `korean is detected by hangul script`() {
        val detection = detector.detect("안녕하세요 오늘 날씨가 좋네요 감사합니다")
        assertNotNull(detection)
        assertEquals("ko", detection!!.language.code)
        assertTrue(detection.confidence >= 0.6f)
    }

    @Test
    fun `russian is detected by cyrillic script`() {
        assertDetects("Привет, как твои дела сегодня мой друг", "ru")
    }

    @Test
    fun `arabic is detected by arabic script`() {
        assertDetects("مرحبا كيف حالك اليوم صديقي العزيز", "ar")
    }

    @Test
    fun `hindi is detected by devanagari script`() {
        assertDetects("नमस्ते आज आपका दिन कैसा है धन्यवाद", "hi")
    }

    @Test
    fun `thai is detected by thai script`() {
        assertDetects("สวัสดีครับ วันนี้อากาศดีมากครับ", "th")
    }

    @Test
    fun `numbers only and blank input return null`() {
        assertNull(detector.detect("12345 678 90"))
        assertNull(detector.detect(""))
    }

    @Test
    fun `chinese is detected by cjk ideographs`() {
        assertDetects("你好世界，今天天气很好，谢谢", "zh")
    }

    @Test
    fun `french stopwords and diacritics win over other latin languages`() {
        val detection = detector.detect(
            "Le chat est sur la table et il ne veut pas sortir de la maison"
        )
        assertNotNull(detection)
        assertEquals("fr", detection!!.language.code)
    }

    @Test
    fun `spanish stopwords and enye are detected`() {
        val detection = detector.detect(
            "El niño está en la casa con su madre y no quiere salir mañana"
        )
        assertNotNull(detection)
        assertEquals("es", detection!!.language.code)
    }

    @Test
    fun `confidence is always within documented bounds`() {
        val samples = listOf(
            "The quick brown fox jumps over the lazy dog near the river",
            "Selamat pagi, bagaimana kabar kamu hari ini yang baik",
            "こんにちは世界、おはようございます今日はいい天気ですね"
        )
        samples.forEach { sample ->
            detector.detect(sample)?.let {
                assertTrue(it.confidence in 0.35f..0.98f)
            }
        }
    }
}

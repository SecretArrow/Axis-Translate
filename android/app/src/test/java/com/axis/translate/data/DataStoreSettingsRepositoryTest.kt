package com.axis.translate.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.axis.translate.data.settings.DataStoreSettingsRepository
import com.axis.translate.data.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [DataStoreSettingsRepository].
 *
 * Deliberately a single sequential test: the `preferencesDataStore` delegate is
 * a process-wide singleton bound to one file, so splitting scenarios across
 * multiple test methods would leak state (or a stale file path) between them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStoreSettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: DataStoreSettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = DataStoreSettingsRepository(context)
    }

    @Test
    fun `settings defaults, persistence, recent pairs and favorite languages`() = runTest {
        // --- defaults surfaced by current() ---
        val defaults = repository.current()
        assertEquals(ThemeMode.SYSTEM, defaults.themeMode)
        assertTrue(defaults.dynamicColor)
        assertEquals("auto", defaults.sourceLanguageCode)
        assertEquals("en", defaults.targetLanguageCode)
        assertTrue(defaults.recentPairs.isEmpty())
        assertTrue(defaults.favoriteLanguageCodes.isEmpty())
        assertTrue(defaults.saveTranslatedPhotos)
        assertTrue(defaults.glossaryEnabled)
        assertEquals("STANDARD", defaults.translationStyle)
        assertEquals(4, defaults.inferenceThreads)
        assertEquals(2048, defaults.contextLength)
        assertTrue(defaults.autoDetectLanguage)
        assertFalse(defaults.floatingTranslationEnabled)
        assertTrue(defaults.liveCameraTranslation)
        assertFalse(defaults.developerMode)

        // --- theme mode persists, also through a fresh repository instance ---
        repository.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repository.current().themeMode)
        assertEquals(ThemeMode.DARK, DataStoreSettingsRepository(context).current().themeMode)

        // --- dynamic color persists, also through the dedicated flow ---
        repository.setDynamicColor(false)
        assertFalse(repository.current().dynamicColor)
        assertFalse(DataStoreSettingsRepository(context).dynamicColor.first())
        repository.setDynamicColor(true)
        assertTrue(repository.current().dynamicColor)

        // --- language selection persists ---
        repository.setSourceLanguage("id")
        repository.setTargetLanguage("ja")
        assertEquals("id", repository.current().sourceLanguageCode)
        assertEquals("ja", repository.current().targetLanguageCode)

        // --- remaining setters persist ---
        repository.setSaveTranslatedPhotos(false)
        repository.setGlossaryEnabled(false)
        repository.setTranslationStyle("FORMAL")
        repository.setInferenceThreads(2)
        repository.setContextLength(4096)
        repository.setAutoDetectLanguage(false)
        repository.setFloatingTranslationEnabled(true)
        repository.setLiveCameraTranslation(false)
        repository.setDeveloperMode(true)
        val updated = repository.current()
        assertFalse(updated.saveTranslatedPhotos)
        assertFalse(updated.glossaryEnabled)
        assertEquals("FORMAL", updated.translationStyle)
        assertEquals(2, updated.inferenceThreads)
        assertEquals(4096, updated.contextLength)
        assertFalse(updated.autoDetectLanguage)
        assertTrue(updated.floatingTranslationEnabled)
        assertFalse(updated.liveCameraTranslation)
        assertTrue(updated.developerMode)

        // --- pushRecentPair prepends, dedupes exact pairs and caps at 10 ---
        repeat(11) { index -> repository.pushRecentPair("src$index", "tgt$index") }
        var recentPairs = repository.current().recentPairs
        assertEquals(10, recentPairs.size)
        assertEquals("src10" to "tgt10", recentPairs.first())
        assertFalse(recentPairs.contains("src0" to "tgt0"))

        repository.pushRecentPair("src10", "tgt10")
        recentPairs = repository.current().recentPairs
        assertEquals(10, recentPairs.size)
        assertEquals(1, recentPairs.count { pair -> pair == ("src10" to "tgt10") })
        assertEquals("src10" to "tgt10", recentPairs.first())

        // --- toggleFavoriteLanguage adds and removes, capped at 12 ---
        repository.toggleFavoriteLanguage("ja")
        repository.toggleFavoriteLanguage("ko")
        assertTrue(repository.current().favoriteLanguageCodes.contains("ja"))
        assertTrue(repository.current().favoriteLanguageCodes.contains("ko"))

        repository.toggleFavoriteLanguage("ja")
        assertFalse(repository.current().favoriteLanguageCodes.contains("ja"))
        assertTrue(repository.current().favoriteLanguageCodes.contains("ko"))

        (1..12).forEach { index -> repository.toggleFavoriteLanguage("c$index") }
        assertEquals(12, repository.current().favoriteLanguageCodes.size)

        repository.toggleFavoriteLanguage("overflow")
        assertEquals(12, repository.current().favoriteLanguageCodes.size)
    }
}

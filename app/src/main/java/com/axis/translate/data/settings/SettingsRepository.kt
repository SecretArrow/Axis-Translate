package com.axis.translate.data.settings

import kotlinx.coroutines.flow.Flow

/** App settings contract, persisted with DataStore (SPEC #49, #27, #30). */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val sourceLanguageCode: String = "auto",
    val targetLanguageCode: String = "en",
    val recentPairs: List<Pair<String, String>> = emptyList(),
    val favoriteLanguageCodes: List<String> = emptyList(),
    val saveTranslatedPhotos: Boolean = true,
    val glossaryEnabled: Boolean = true,
    val translationStyle: String = "STANDARD",
    val inferenceThreads: Int = 4,
    val contextLength: Int = 2048,
    val autoDetectLanguage: Boolean = true,
    val floatingTranslationEnabled: Boolean = false,
    val liveCameraTranslation: Boolean = true,
    val developerMode: Boolean = false
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setSourceLanguage(code: String)
    suspend fun setTargetLanguage(code: String)
    suspend fun pushRecentPair(source: String, target: String)
    suspend fun toggleFavoriteLanguage(code: String)
    suspend fun setSaveTranslatedPhotos(enabled: Boolean)
    suspend fun setGlossaryEnabled(enabled: Boolean)
    suspend fun setTranslationStyle(style: String)
    suspend fun setInferenceThreads(threads: Int)
    suspend fun setContextLength(length: Int)
    suspend fun setAutoDetectLanguage(enabled: Boolean)
    suspend fun setFloatingTranslationEnabled(enabled: Boolean)
    suspend fun setLiveCameraTranslation(enabled: Boolean)
    suspend fun setDeveloperMode(enabled: Boolean)
}

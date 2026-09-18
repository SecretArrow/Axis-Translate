package com.axis.translate.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Process-wide DataStore backing all app settings. The delegate guarantees a
 * single instance per file even when accessed through different contexts.
 */
private val Context.axisDataStore by preferencesDataStore(name = "axis_settings")

/** Storage key for every [AppSettings] field. */
private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val SOURCE_LANGUAGE = stringPreferencesKey("source_language")
    val TARGET_LANGUAGE = stringPreferencesKey("target_language")
    val RECENT_PAIRS = stringSetPreferencesKey("recent_pairs")
    val FAVORITE_LANGUAGES = stringSetPreferencesKey("favorite_languages")
    val SAVE_TRANSLATED_PHOTOS = booleanPreferencesKey("save_translated_photos")
    val GLOSSARY_ENABLED = booleanPreferencesKey("glossary_enabled")
    val TRANSLATION_STYLE = stringPreferencesKey("translation_style")
    val INFERENCE_THREADS = intPreferencesKey("inference_threads")
    val CONTEXT_LENGTH = intPreferencesKey("context_length")
    val AUTO_DETECT_LANGUAGE = booleanPreferencesKey("auto_detect_language")
    val FLOATING_TRANSLATION_ENABLED = booleanPreferencesKey("floating_translation_enabled")
    val LIVE_CAMERA_TRANSLATION = booleanPreferencesKey("live_camera_translation")
    val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
}

/** Field defaults mirroring [AppSettings]. */
private object Defaults {
    const val SOURCE_LANGUAGE = "auto"
    const val TARGET_LANGUAGE = "en"
    const val TRANSLATION_STYLE = "STANDARD"
    const val INFERENCE_THREADS = 4
    const val CONTEXT_LENGTH = 2048
}

private const val PAIR_SEPARATOR = "->"

/**
 * A DataStore `StringSet` is unordered, so each recent-pair entry embeds a
 * monotonically increasing sequence number: `"<seq>|<src>-><tgt>"`. The seq is
 * re-normalized (0..n-1, newest = highest) on every push, which keeps entries
 * compact while preserving the recency order across restarts.
 */
private const val SEQ_SEPARATOR = "|"

private const val MAX_RECENT_PAIRS = 10
private const val MAX_FAVORITE_LANGUAGES = 12

private data class EncodedRecentPair(val seq: Int, val pair: Pair<String, String>)

private fun encodeRecentPair(seq: Int, pair: Pair<String, String>): String = "$seq$SEQ_SEPARATOR${pair.first}$PAIR_SEPARATOR${pair.second}"

private fun decodeRecentPair(raw: String): EncodedRecentPair? {
    val seqEnd = raw.indexOf(SEQ_SEPARATOR)
    if (seqEnd <= 0) return null
    val seq = raw.substring(0, seqEnd).toIntOrNull() ?: return null
    val remainder = raw.substring(seqEnd + SEQ_SEPARATOR.length)
    val separator = remainder.indexOf(PAIR_SEPARATOR)
    if (separator <= 0 || separator == remainder.length - PAIR_SEPARATOR.length) return null
    val source = remainder.substring(0, separator)
    val target = remainder.substring(separator + PAIR_SEPARATOR.length)
    return EncodedRecentPair(seq, source to target)
}

private fun Preferences.toAppSettings(): AppSettings = AppSettings(
    themeMode = this[Keys.THEME_MODE]
        ?.let { stored -> ThemeMode.entries.firstOrNull { mode -> mode.name == stored } }
        ?: ThemeMode.SYSTEM,
    sourceLanguageCode = this[Keys.SOURCE_LANGUAGE] ?: Defaults.SOURCE_LANGUAGE,
    targetLanguageCode = this[Keys.TARGET_LANGUAGE] ?: Defaults.TARGET_LANGUAGE,
    recentPairs = this[Keys.RECENT_PAIRS].orEmpty()
        .mapNotNull(::decodeRecentPair)
        .sortedByDescending { encoded -> encoded.seq }
        .map { encoded -> encoded.pair },
    favoriteLanguageCodes = this[Keys.FAVORITE_LANGUAGES].orEmpty().toList(),
    saveTranslatedPhotos = this[Keys.SAVE_TRANSLATED_PHOTOS] ?: true,
    glossaryEnabled = this[Keys.GLOSSARY_ENABLED] ?: true,
    translationStyle = this[Keys.TRANSLATION_STYLE] ?: Defaults.TRANSLATION_STYLE,
    inferenceThreads = this[Keys.INFERENCE_THREADS] ?: Defaults.INFERENCE_THREADS,
    contextLength = this[Keys.CONTEXT_LENGTH] ?: Defaults.CONTEXT_LENGTH,
    autoDetectLanguage = this[Keys.AUTO_DETECT_LANGUAGE] ?: true,
    floatingTranslationEnabled = this[Keys.FLOATING_TRANSLATION_ENABLED] ?: false,
    liveCameraTranslation = this[Keys.LIVE_CAMERA_TRANSLATION] ?: true,
    developerMode = this[Keys.DEVELOPER_MODE] ?: false
)

/** Preferences-[DataStore][androidx.datastore.preferences.core.Preferences] implementation of [SettingsRepository]. */
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    override val settings: Flow<AppSettings> = context.axisDataStore.data
        .map { preferences -> preferences.toAppSettings() }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.axisDataStore.edit { preferences -> preferences[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setSourceLanguage(code: String) {
        context.axisDataStore.edit { preferences -> preferences[Keys.SOURCE_LANGUAGE] = code }
    }

    override suspend fun setTargetLanguage(code: String) {
        context.axisDataStore.edit { preferences -> preferences[Keys.TARGET_LANGUAGE] = code }
    }

    override suspend fun pushRecentPair(source: String, target: String) {
        if (source.isBlank() || target.isBlank()) return
        val pair = source to target
        context.axisDataStore.edit { preferences ->
            val previous = preferences[Keys.RECENT_PAIRS].orEmpty()
                .mapNotNull(::decodeRecentPair)
                .sortedByDescending { encoded -> encoded.seq }
                .map { encoded -> encoded.pair }
            val updated = (listOf(pair) + previous.filterNot { it == pair }).take(MAX_RECENT_PAIRS)
            preferences[Keys.RECENT_PAIRS] = updated
                .mapIndexed { index, current -> encodeRecentPair(updated.size - 1 - index, current) }
                .toSet()
        }
    }

    override suspend fun toggleFavoriteLanguage(code: String) {
        if (code.isBlank()) return
        context.axisDataStore.edit { preferences ->
            val current = preferences[Keys.FAVORITE_LANGUAGES].orEmpty()
            preferences[Keys.FAVORITE_LANGUAGES] = when {
                code in current -> current - code
                current.size >= MAX_FAVORITE_LANGUAGES -> current
                else -> current + code
            }
        }
    }

    override suspend fun setSaveTranslatedPhotos(enabled: Boolean) {
        context.axisDataStore.edit { preferences -> preferences[Keys.SAVE_TRANSLATED_PHOTOS] = enabled }
    }

    override suspend fun setGlossaryEnabled(enabled: Boolean) {
        context.axisDataStore.edit { preferences -> preferences[Keys.GLOSSARY_ENABLED] = enabled }
    }

    override suspend fun setTranslationStyle(style: String) {
        context.axisDataStore.edit { preferences -> preferences[Keys.TRANSLATION_STYLE] = style }
    }

    override suspend fun setInferenceThreads(threads: Int) {
        context.axisDataStore.edit { preferences -> preferences[Keys.INFERENCE_THREADS] = threads }
    }

    override suspend fun setContextLength(length: Int) {
        context.axisDataStore.edit { preferences -> preferences[Keys.CONTEXT_LENGTH] = length }
    }

    override suspend fun setAutoDetectLanguage(enabled: Boolean) {
        context.axisDataStore.edit { preferences -> preferences[Keys.AUTO_DETECT_LANGUAGE] = enabled }
    }

    override suspend fun setFloatingTranslationEnabled(enabled: Boolean) {
        context.axisDataStore.edit { preferences -> preferences[Keys.FLOATING_TRANSLATION_ENABLED] = enabled }
    }

    override suspend fun setLiveCameraTranslation(enabled: Boolean) {
        context.axisDataStore.edit { preferences -> preferences[Keys.LIVE_CAMERA_TRANSLATION] = enabled }
    }

    override suspend fun setDeveloperMode(enabled: Boolean) {
        context.axisDataStore.edit { preferences -> preferences[Keys.DEVELOPER_MODE] = enabled }
    }
}

package com.axis.translate.domain.model

/**
 * A language available for translation.
 *
 * The actual list of selectable languages is driven by the model manifest
 * (see [com.axis.translate.data.model.ModelManifestEntry.languages]) so the UI
 * never advertises capabilities the installed model does not have.
 */
data class Language(
    val code: String,
    val displayName: String,
    val nativeName: String = displayName,
    val script: String = "Latin"
) {
    val isAuto: Boolean get() = code == AUTO_CODE

    fun matches(other: Language): Boolean = code == other.code

    override fun toString(): String = displayName

    companion object {
        const val AUTO_CODE = "auto"

        val AUTO = Language(AUTO_CODE, "Auto Detect", "Auto Detect", script = "auto")

        /**
         * Fallback catalog used by the language picker when no model manifest
         * languages are loaded. Mirrors the manifest file shipped in assets.
         */
        val FALLBACK_CATALOG: List<Language> = listOf(
            Language("en", "English", "English"),
            Language("id", "Indonesian", "Bahasa Indonesia"),
            Language("ja", "Japanese", "日本語", script = "Japanese"),
            Language("ko", "Korean", "한국어", script = "Korean"),
            Language("zh", "Chinese (Simplified)", "简体中文", script = "Chinese"),
            Language("es", "Spanish", "Español"),
            Language("fr", "French", "Français"),
            Language("de", "German", "Deutsch"),
            Language("it", "Italian", "Italiano"),
            Language("pt", "Portuguese", "Português"),
            Language("ru", "Russian", "Русский", script = "Cyrillic"),
            Language("ar", "Arabic", "العربية", script = "Arabic"),
            Language("hi", "Hindi", "हिन्दी", script = "Devanagari"),
            Language("th", "Thai", "ไทย", script = "Thai"),
            Language("vi", "Vietnamese", "Tiếng Việt"),
            Language("tr", "Turkish", "Türkçe"),
            Language("nl", "Dutch", "Nederlands"),
            Language("pl", "Polish", "Polski"),
            Language("ms", "Malay", "Bahasa Melayu"),
            Language("sv", "Swedish", "Svenska")
        )

        fun byCode(code: String): Language? = FALLBACK_CATALOG.firstOrNull { it.code == code }
    }
}

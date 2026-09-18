package com.axis.translate.domain.model

import kotlinx.serialization.Serializable

/** Language specification inside the model manifest. */
@Serializable
data class LanguageSpec(
    val code: String,
    val name: String,
    val nativeName: String = name,
    val script: String = "Latin"
)

/** One downloadable model entry. */
@Serializable
data class ModelManifestEntry(
    val id: String,
    val displayName: String,
    val description: String = "",
    val quantization: String,
    val file: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val contextLength: Int = 2048,
    val runtime: String = "llama.cpp",
    val license: String = "",
    val languages: List<LanguageSpec> = emptyList(),
    val default: Boolean = false
) {
    fun languageCatalog(): List<Language> = languages.map {
        Language(it.code, it.name, it.nativeName, it.script)
    }
}

/** Root of assets/model_manifest.json. */
@Serializable
data class ModelManifest(
    val schemaVersion: Int = 1,
    val models: List<ModelManifestEntry>
) {
    fun defaultEntry(): ModelManifestEntry? = models.firstOrNull { it.default } ?: models.firstOrNull()
    fun byId(id: String): ModelManifestEntry? = models.firstOrNull { it.id == id }
}

/** Lifecycle of the on-device model (see SPEC #35). */
enum class ModelStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    DOWNLOADED,
    VERIFYING,
    INSTALLING,
    LOADING,
    READY,
    UPDATING,
    CORRUPTED,
    ERROR
}

/** Download / install progress exposed to the UI. */
data class ModelProgress(
    val status: ModelStatus,
    val entryId: String? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null
) {
    val percent: Float
        get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
}

/** Installed model runtime view. */
data class InstalledModelInfo(
    val entry: ModelManifestEntry,
    val path: String,
    val sizeBytes: Long,
    val installedAt: Long
)

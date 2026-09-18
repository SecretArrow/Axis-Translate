package com.axis.translate.data.repo

import android.net.Uri
import com.axis.translate.domain.model.InstalledModelInfo
import com.axis.translate.domain.model.Language
import com.axis.translate.domain.model.ModelManifest
import com.axis.translate.domain.model.ModelManifestEntry
import com.axis.translate.domain.model.ModelProgress
import com.axis.translate.domain.model.ModelStatus
import kotlinx.coroutines.flow.StateFlow

/** Emitted while [ModelRepository] installs a model. */

/**
 * Offline model manager (SPEC #35–#38): list, download, SHA-256 verify,
 * install, remove, and manual import of GGUF model packages.
 */
interface ModelRepository {
    /** Manifest parsed from assets, hot-swappable in tests. */
    val manifest: ModelManifest

    /** Current install/verification progress for the UI. */
    val progress: StateFlow<ModelProgress>

    /** The currently installed model, if any. */
    val installed: StateFlow<InstalledModelInfo?>

    /** Languages supported by the installed (or default) model entry. */
    fun languageCatalog(): List<Language>

    suspend fun listEntries(): List<ModelManifestEntry>

    /** Download + verify + install the given entry. Emits [progress]. */
    suspend fun downloadAndInstall(
        entry: ModelManifestEntry,
        onProgress: (ModelProgress) -> Unit = {},
    ): Result<InstalledModelInfo>

    /**
     * Import a local model package (content URI) after validation:
     * structure, size, SHA-256 against the manifest when the file is known.
     */
    suspend fun importModel(uri: Uri): Result<InstalledModelInfo>

    suspend fun removeModel()

    fun installedModelPath(): String?

    /** True when the installed file passes the expected checksum. */
    suspend fun verifyInstalled(): Boolean

    companion object {
        const val MODELS_DIR = "models"
        const val MANIFEST_ASSET = "model_manifest.json"
    }
}

/** Simple result of a model scan. */
data class ModelScanResult(
    val status: ModelStatus,
    val info: InstalledModelInfo? = null,
    val message: String? = null,
)

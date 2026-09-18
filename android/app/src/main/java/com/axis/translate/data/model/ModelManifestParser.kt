package com.axis.translate.data.model

import android.content.Context
import com.axis.translate.data.repo.ModelRepository
import com.axis.translate.domain.model.ModelManifest
import kotlinx.serialization.json.Json

/**
 * Decodes [assets/model_manifest.json][ModelRepository.MANIFEST_ASSET] into a
 * [ModelManifest]. Unknown keys are ignored so a newer manifest never breaks an
 * older app build.
 */
object ModelManifestParser {

    private val format = Json { ignoreUnknownKeys = true }

    /** Parses [json] into a [ModelManifest]. */
    fun parse(json: String): ModelManifest = format.decodeFromString(ModelManifest.serializer(), json)

    /** Reads and parses the manifest bundled inside the APK assets. */
    fun fromAssets(context: Context): ModelManifest = parse(
        context.assets.open(ModelRepository.MANIFEST_ASSET).bufferedReader().use { reader -> reader.readText() }
    )
}

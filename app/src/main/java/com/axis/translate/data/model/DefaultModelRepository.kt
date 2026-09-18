package com.axis.translate.data.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.axis.translate.data.repo.ModelRepository
import com.axis.translate.data.settings.SettingsRepository
import com.axis.translate.domain.model.InstalledModelInfo
import com.axis.translate.domain.model.Language
import com.axis.translate.domain.model.ModelManifest
import com.axis.translate.domain.model.ModelManifestEntry
import com.axis.translate.domain.model.ModelProgress
import com.axis.translate.domain.model.ModelStatus
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production [ModelRepository]: downloads GGUF models listed in the shipped
 * manifest, verifies them with SHA-256, supports manual imports, and tracks the
 * single installed model in `filesDir/models/<entryId>/<file>`.
 *
 * Notes on manifest placeholders: an entry whose `sha256` is an all-zero
 * 64-char string (or whose `sizeBytes` is 0) is treated as "not yet
 * published" — the download is still attempted (the server usually 404s and
 * the normal error path applies), but checksum comparison is skipped so a
 * later-published file still installs cleanly.
 *
 * The [settingsRepository] constructor parameter is part of the AppContainer
 * wiring contract and reserved for future engine-default settings.
 */
class DefaultModelRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ModelRepository {

    override val manifest: ModelManifest = ModelManifestParser.fromAssets(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloader = ModelDownloader()

    /** Serializes install/import/remove so flows never observe torn state. */
    private val installMutex = Mutex()

    private val _progress = MutableStateFlow(ModelProgress(ModelStatus.NOT_INSTALLED))
    override val progress: StateFlow<ModelProgress> = _progress.asStateFlow()

    private val _installed = MutableStateFlow<InstalledModelInfo?>(null)
    override val installed: StateFlow<InstalledModelInfo?> = _installed.asStateFlow()

    init {
        scope.launch { scanInstalled() }
    }

    override suspend fun listEntries(): List<ModelManifestEntry> = manifest.models

    override fun languageCatalog(): List<Language> =
        (_installed.value?.entry ?: manifest.defaultEntry())?.languageCatalog() ?: Language.FALLBACK_CATALOG

    override fun installedModelPath(): String? = _installed.value?.path

    override suspend fun downloadAndInstall(
        entry: ModelManifestEntry,
        onProgress: (ModelProgress) -> Unit,
    ): Result<InstalledModelInfo> = installMutex.withLock {
        val partFile = File(File(modelsRoot(), entry.id), entry.file + PART_SUFFIX)

        fun emit(progress: ModelProgress) {
            _progress.value = progress
            onProgress(progress)
        }

        emit(ModelProgress(ModelStatus.DOWNLOADING, entryId = entry.id, totalBytes = entry.sizeBytes))
        try {
            val downloaded = downloader.download(entry.url, partFile) { bytes ->
                emit(
                    ModelProgress(
                        status = ModelStatus.DOWNLOADING,
                        entryId = entry.id,
                        bytesDownloaded = bytes,
                        totalBytes = entry.sizeBytes,
                    ),
                )
            }
            if (downloaded.isFailure) {
                val cause = downloaded.exceptionOrNull() ?: IOException("Model download failed")
                deleteQuietly(partFile)
                emit(ModelProgress(ModelStatus.ERROR, entryId = entry.id, error = cause.message ?: "Model download failed"))
                return Result.failure(cause)
            }
            if (partFile.length() <= 0L) {
                deleteQuietly(partFile)
                val message = "Downloaded model file is empty"
                emit(ModelProgress(ModelStatus.ERROR, entryId = entry.id, error = message))
                return Result.failure(IllegalStateException(message))
            }

            emit(ModelProgress(ModelStatus.VERIFYING, entryId = entry.id))
            val actualSha = withContext(Dispatchers.IO) { sha256(partFile) }
            if (isKnownHash(entry.sha256) && !actualSha.equals(entry.sha256, ignoreCase = true)) {
                deleteQuietly(partFile)
                emit(ModelProgress(ModelStatus.CORRUPTED, entryId = entry.id, error = VERIFICATION_FAILED_MESSAGE))
                return Result.failure(IllegalStateException(VERIFICATION_FAILED_MESSAGE))
            }

            val finalFile = withContext(Dispatchers.IO) { installPartFile(partFile, entry) }
            val info = InstalledModelInfo(
                entry = entry,
                path = finalFile.absolutePath,
                sizeBytes = finalFile.length(),
                installedAt = System.currentTimeMillis(),
            )
            _installed.value = info
            emit(
                ModelProgress(
                    status = ModelStatus.READY,
                    entryId = entry.id,
                    bytesDownloaded = info.sizeBytes,
                    totalBytes = info.sizeBytes,
                ),
            )
            Result.success(info)
        } catch (cancellation: CancellationException) {
            deleteQuietly(partFile)
            throw cancellation
        } catch (failure: Throwable) {
            deleteQuietly(partFile)
            emit(
                ModelProgress(
                    status = ModelStatus.ERROR,
                    entryId = entry.id,
                    error = failure.message ?: "Model installation failed",
                ),
            )
            Result.failure(failure)
        }
    }

    override suspend fun importModel(uri: Uri): Result<InstalledModelInfo> = installMutex.withLock {
        try {
            withContext(Dispatchers.IO) { importModelInternal(uri) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    override suspend fun removeModel() = installMutex.withLock {
        val current = _installed.value
        _installed.value = null
        _progress.value = ModelProgress(ModelStatus.NOT_INSTALLED)
        if (current != null) {
            withContext(Dispatchers.IO) {
                File(current.path).parentFile?.let { entryDir -> entryDir.deleteRecursively() }
            }
        }
        Unit
    }

    override suspend fun verifyInstalled(): Boolean {
        val info = _installed.value ?: return false
        return withContext(Dispatchers.IO) {
            val file = File(info.path)
            if (!file.isFile || file.length() <= 0L) return@withContext false
            val expected = info.entry.sha256
            if (!isKnownHash(expected)) return@withContext true
            try {
                sha256(file).equals(expected, ignoreCase = true)
            } catch (io: IOException) {
                false
            }
        }
    }

    /**
     * Looks for `filesDir/models/<entryId>/<file>` for every manifest entry and
     * publishes the first hit (manifest order = priority order). Imported
     * ad-hoc models live under `models/imported/` and are intentionally not
     * re-discovered after a process restart.
     */
    private suspend fun scanInstalled() {
        val found = withContext(Dispatchers.IO) {
            manifest.models.asSequence()
                .map { entry -> entry to entryFile(entry) }
                .firstOrNull { (_, file) -> file.isFile && file.length() > 0L }
                ?.let { (entry, file) ->
                    InstalledModelInfo(entry, file.absolutePath, file.length(), file.lastModified())
                }
        }
        if (found != null) {
            _installed.value = found
            _progress.value = ModelProgress(
                status = ModelStatus.READY,
                entryId = found.entry.id,
                bytesDownloaded = found.sizeBytes,
                totalBytes = found.sizeBytes,
            )
        }
    }

    /** Blocking import worker; must be called on the IO dispatcher. */
    private fun importModelInternal(uri: Uri): Result<InstalledModelInfo> {
        val destination = File(File(modelsRoot(), IMPORTED_DIR), resolveImportFileName(uri))
        destination.parentFile?.mkdirs()
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (security: SecurityException) {
            return Result.failure(security)
        } ?: return Result.failure(IllegalStateException("Cannot read the selected model file"))
        try {
            stream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
            }
        } catch (io: IOException) {
            deleteQuietly(destination)
            return Result.failure(io)
        }
        if (!destination.isFile || destination.length() <= 0L || !hasGgufMagic(destination)) {
            deleteQuietly(destination)
            return Result.failure(IllegalStateException(NOT_GGUF_MESSAGE))
        }

        val actualSha = sha256(destination)
        val associatedEntry = manifest.models.firstOrNull { entry ->
            isKnownHash(entry.sha256) && entry.sha256.equals(actualSha, ignoreCase = true)
        }
        val info = InstalledModelInfo(
            entry = associatedEntry ?: buildImportedEntry(destination, actualSha),
            path = destination.absolutePath,
            sizeBytes = destination.length(),
            installedAt = System.currentTimeMillis(),
        )
        _installed.value = info
        _progress.value = ModelProgress(
            status = ModelStatus.READY,
            entryId = info.entry.id,
            bytesDownloaded = info.sizeBytes,
            totalBytes = info.sizeBytes,
        )
        return Result.success(info)
    }

    /** Ad-hoc manifest entry describing a user-imported, unknown GGUF file. */
    private fun buildImportedEntry(file: File, fileSha256: String): ModelManifestEntry {
        val defaultEntry = manifest.defaultEntry()
        return ModelManifestEntry(
            id = "imported-" + fileSha256.take(HASH_PREFIX_LENGTH),
            displayName = "Imported model (${file.length() / BYTES_PER_MB} MB)",
            description = "Locally imported GGUF model file",
            quantization = "unknown",
            file = file.name,
            url = "",
            sha256 = fileSha256,
            sizeBytes = file.length(),
            contextLength = defaultEntry?.contextLength ?: DEFAULT_CONTEXT_LENGTH,
            runtime = "llama.cpp",
            license = "",
            languages = defaultEntry?.languages ?: emptyList(),
            default = false,
        )
    }

    /** Atomically moves a verified `.part` file to its final location. */
    private fun installPartFile(partFile: File, entry: ModelManifestEntry): File {
        val finalFile = entryFile(entry)
        finalFile.parentFile?.mkdirs()
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        return finalFile
    }

    private fun resolveImportFileName(uri: Uri): String {
        val documentFileName = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
        val displayName = documentFileName
            ?: queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
        return displayName?.let(::sanitizeFileName) ?: DEFAULT_IMPORT_FILE_NAME
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    private fun sanitizeFileName(name: String): String =
        name.replace(INVALID_FILENAME_CHARS, "").trim().ifEmpty { DEFAULT_IMPORT_FILE_NAME }

    private fun hasGgufMagic(file: File): Boolean {
        if (file.length() < GGUF_MAGIC.size) return false
        val header = ByteArray(GGUF_MAGIC.size)
        return try {
            val read = file.inputStream().use { input -> readFully(input, header) }
            read == header.size && header.contentEquals(GGUF_MAGIC)
        } catch (io: IOException) {
            false
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    /** Streaming SHA-256 hex digest (lowercase) of [file]. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** `false` for the all-zero "not yet published" placeholder checksums. */
    private fun isKnownHash(sha256: String): Boolean =
        sha256.isNotBlank() && !sha256.all { char -> char == '0' }

    private fun modelsRoot(): File = File(context.filesDir, ModelRepository.MODELS_DIR)

    private fun entryFile(entry: ModelManifestEntry): File = File(File(modelsRoot(), entry.id), entry.file)

    private fun deleteQuietly(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val IMPORTED_DIR = "imported"
        const val DEFAULT_IMPORT_FILE_NAME = "imported.gguf"
        const val VERIFICATION_FAILED_MESSAGE = "Model verification failed"
        const val NOT_GGUF_MESSAGE = "Not a valid GGUF model file"
        const val DEFAULT_CONTEXT_LENGTH = 2048
        const val HASH_PREFIX_LENGTH = 8
        const val BYTES_PER_MB = 1024L * 1024L
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val HASH_BUFFER_BYTES = 64 * 1024
        val GGUF_MAGIC = "GGUF".toByteArray()
        val INVALID_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]")
    }
}

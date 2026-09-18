package com.axis.translate.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.axis.translate.data.model.DefaultModelRepository
import com.axis.translate.data.settings.DataStoreSettingsRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the export path of [DefaultModelRepository].
 *
 * SAF-style destination URIs are simulated with file:// URIs: from the
 * repository's point of view both are opaque URIs resolved through
 * ContentResolver.openOutputStream, and Robolectric routes file://
 * straight onto real streams, so the copy and byte-count logic is the
 * production code path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultModelRepositoryExportTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun newRepository(): DefaultModelRepository = DefaultModelRepository(
        context = context,
        settingsRepository = DataStoreSettingsRepository(context)
    )

    /** Writes a fake GGUF (valid magic + deterministic payload) into [directory]. */
    private fun writeFakeGguf(directory: File, name: String = "export-test-model.gguf"): File {
        val file = File(directory, name)
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            output.write("GGUF".toByteArray())
            // Payload large enough to span several 64 KB copy chunks.
            val payload = ByteArray(PAYLOAD_BYTES) { index -> (index % 251).toByte() }
            output.write(payload)
        }
        return file
    }

    @Test
    fun `exportModel fails when no model is installed`() = runTest {
        val repository = newRepository()
        val destination = File(context.cacheDir, "no-model-export.gguf")

        val result = repository.exportModel(Uri.fromFile(destination))

        assertTrue(result.isFailure)
        assertEquals("No model installed to export", result.exceptionOrNull()?.message)
        assertTrue(!destination.exists())
    }

    @Test
    fun `exportModel copies the installed gguf byte for byte`() = runTest {
        val repository = newRepository()
        val source = writeFakeGguf(File(context.cacheDir, "import-source"))
        assertTrue(repository.importModel(Uri.fromFile(source)).isSuccess)

        val destination = File(context.cacheDir, "exported.gguf")
        val result = repository.exportModel(Uri.fromFile(destination))

        assertTrue(result.isSuccess)
        assertTrue(destination.isFile)
        assertEquals(source.length(), destination.length())
        assertArrayEquals(source.readBytes(), destination.readBytes())
    }

    @Test
    fun `exportModel fails when the installed file disappeared`() = runTest {
        val repository = newRepository()
        val source = writeFakeGguf(File(context.cacheDir, "import-source"), "vanishing.gguf")
        assertTrue(repository.importModel(Uri.fromFile(source)).isSuccess)

        val installedPath = repository.installedModelPath()
        assertNotNull(installedPath)
        assertTrue(File(installedPath!!).delete())

        val destination = File(context.cacheDir, "exported-after-delete.gguf")
        val result = repository.exportModel(Uri.fromFile(destination))

        assertTrue(result.isFailure)
    }

    private companion object {
        const val PAYLOAD_BYTES = 192 * 1024
    }
}

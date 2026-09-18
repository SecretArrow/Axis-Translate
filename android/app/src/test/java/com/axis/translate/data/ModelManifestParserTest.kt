package com.axis.translate.data

import com.axis.translate.data.model.ModelManifestParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM parsing tests for [ModelManifestParser]. */
class ModelManifestParserTest {

    @Test
    fun `parse reads entries and ignores unknown fields`() {
        val manifest = ModelManifestParser.parse(MANIFEST_JSON)

        assertEquals(2, manifest.models.size)
        val first = manifest.models[0]
        assertEquals("test-a", first.id)
        assertEquals("Test Model A", first.displayName)
        assertEquals("Primary test model", first.description)
        assertEquals("Q4_K_M", first.quantization)
        assertEquals("test-a.gguf", first.file)
        assertEquals("https://example.com/test-a.gguf", first.url)
        assertEquals("aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11", first.sha256)
        assertEquals(123456L, first.sizeBytes)
        assertEquals(4096, first.contextLength)
        assertEquals("llama.cpp", first.runtime)
        assertEquals("MIT", first.license)
        assertTrue(first.default)
    }

    @Test
    fun `optional fields fall back to defaults`() {
        val manifest = ModelManifestParser.parse(MANIFEST_JSON)

        val fallbackEntry = manifest.byId("test-b")
        requireNotNull(fallbackEntry)
        assertEquals("", fallbackEntry.description)
        assertEquals(2048, fallbackEntry.contextLength)
        assertEquals("llama.cpp", fallbackEntry.runtime)
        assertEquals("", fallbackEntry.license)
        assertTrue(fallbackEntry.languages.isEmpty())
        assertFalse(fallbackEntry.default)
    }

    @Test
    fun `defaultEntry prefers the default-flagged entry`() {
        val manifest = ModelManifestParser.parse(MANIFEST_JSON)

        assertEquals("test-a", manifest.defaultEntry()?.id)
    }

    @Test
    fun `defaultEntry falls back to the first entry without a default flag`() {
        val manifest = ModelManifestParser.parse(NO_DEFAULT_JSON)

        assertEquals("first", manifest.defaultEntry()?.id)
    }

    @Test
    fun `byId finds and misses entries`() {
        val manifest = ModelManifestParser.parse(MANIFEST_JSON)

        assertEquals("test-b", manifest.byId("test-b")?.id)
        assertNull(manifest.byId("missing"))
    }

    @Test
    fun `languageCatalog maps language specs into domain languages`() {
        val manifest = ModelManifestParser.parse(MANIFEST_JSON)

        val catalog = requireNotNull(manifest.byId("test-a")).languageCatalog()
        assertEquals(2, catalog.size)

        val english = catalog[0]
        assertEquals("en", english.code)
        assertEquals("English", english.displayName)
        assertEquals("English", english.nativeName)
        assertEquals("Latin", english.script)

        val japanese = catalog[1]
        assertEquals("ja", japanese.code)
        assertEquals("Japanese", japanese.displayName)
        assertEquals("日本語", japanese.nativeName)
        assertEquals("Japanese", japanese.script)
    }

    @Test
    fun `manifest without entries has no default entry`() {
        val manifest = ModelManifestParser.parse("""{"models": []}""")

        assertTrue(manifest.models.isEmpty())
        assertEquals(1, manifest.schemaVersion)
        assertNull(manifest.defaultEntry())
    }

    private companion object {
        val MANIFEST_JSON = """
            {
              "schemaVersion": 1,
              "generatedBy": "unit-test",
              "models": [
                {
                  "id": "test-a",
                  "displayName": "Test Model A",
                  "description": "Primary test model",
                  "quantization": "Q4_K_M",
                  "file": "test-a.gguf",
                  "url": "https://example.com/test-a.gguf",
                  "sha256": "aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11",
                  "sizeBytes": 123456,
                  "contextLength": 4096,
                  "runtime": "llama.cpp",
                  "license": "MIT",
                  "languages": [
                    { "code": "en", "name": "English" },
                    { "code": "ja", "name": "Japanese", "nativeName": "日本語", "script": "Japanese", "futureField": 1 }
                  ],
                  "default": true,
                  "futureEntryField": { "nested": true }
                },
                {
                  "id": "test-b",
                  "displayName": "Test Model B",
                  "quantization": "Q8_0",
                  "file": "test-b.gguf",
                  "url": "https://example.com/test-b.gguf",
                  "sha256": "bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22",
                  "sizeBytes": 654321
                }
              ]
            }
        """.trimIndent()

        val NO_DEFAULT_JSON = """
            {
              "schemaVersion": 1,
              "models": [
                {
                  "id": "first",
                  "displayName": "First",
                  "quantization": "Q4_0",
                  "file": "first.gguf",
                  "url": "https://example.com/first.gguf",
                  "sha256": "cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33",
                  "sizeBytes": 1
                },
                {
                  "id": "second",
                  "displayName": "Second",
                  "quantization": "Q4_0",
                  "file": "second.gguf",
                  "url": "https://example.com/second.gguf",
                  "sha256": "dd44dd44dd44dd44dd44dd44dd44dd44dd44dd44dd44dd44dd44dd44dd44dd44",
                  "sizeBytes": 2
                }
              ]
            }
        """.trimIndent()
    }
}

package com.axis.translate.domain.documents

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * [DocumentProcessor] for TXT / MD / HTML documents (SPEC #24).
 *
 * Extraction is pure-Kotlin (regex based HTML stripping — no extra parser
 * dependency), and exports always write a NEW translated file under
 * files/exports; the original document is never modified.
 */
class HtmlDocumentProcessor : DocumentProcessor {

    override suspend fun extract(context: Context, uri: Uri): Result<DocumentContent> =
        withContext(Dispatchers.IO) {
            try {
                val document = DocumentFile.fromSingleUri(context, uri)
                val fileName = document?.name?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME
                val declaredMime = document?.type

                // Capped read: a huge file must fail fast instead of OOM-ing.
                val bytes = context.contentResolver.openInputStream(uri)?.use(::readBytesCapped)
                    ?: return@withContext Result.failure(IOException("Document is not readable"))

                val isHtml = declaredMime?.contains("html", ignoreCase = true) == true ||
                    fileName.endsWith(".html", ignoreCase = true) ||
                    fileName.endsWith(".htm", ignoreCase = true)
                val isMarkdown = declaredMime?.contains("markdown", ignoreCase = true) == true ||
                    fileName.endsWith(".md", ignoreCase = true) ||
                    fileName.endsWith(".markdown", ignoreCase = true)
                val normalizedMime = when {
                    isHtml -> MIME_HTML
                    isMarkdown -> MIME_MARKDOWN
                    else -> MIME_PLAIN
                }

                val raw = String(bytes, Charsets.UTF_8)
                val text = if (isHtml) stripHtml(raw) else normalizeLineEndings(raw)
                if (text.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Document is empty"))
                }

                Result.success(
                    DocumentContent(
                        title = fileName.substringBeforeLast('.').ifBlank { fileName },
                        text = text,
                        mimeType = normalizedMime,
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    override suspend fun exportTranslated(
        context: Context,
        original: DocumentContent,
        translatedText: String,
        targetLanguageCode: String,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
            val fileName = ("${original.title.ifBlank { FALLBACK_NAME }}-$targetLanguageCode.txt")
                .replace(UNSUPPORTED_FILENAME_REGEX, "_")
            val file = File(exportDir, fileName)
            file.writeText(translatedText, Charsets.UTF_8)
            Result.success(
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /**
     * Converts an HTML document to plain text: drops script/style blocks and
     * comments, converts structural tags to newlines/tabs, strips remaining
     * tags, decodes common entities and collapses excess blank lines.
     */
    fun stripHtml(html: String): String {
        var text = normalizeLineEndings(html)
        text = SCRIPT_STYLE_BLOCK_REGEX.replace(text, "")
        text = BLOCK_BREAK_REGEX.replace(text, "\n")
        text = ROW_END_REGEX.replace(text, "\n")
        text = CELL_END_REGEX.replace(text, "\t")
        text = ANY_TAG_REGEX.replace(text, "")
        text = decodeEntities(text)
        text = text.lineSequence().map { it.trimEnd() }.joinToString("\n")
        text = EXCESS_NEWLINES_REGEX.replace(text, "\n\n")
        return text.trim()
    }

    private fun decodeEntities(input: String): String {
        var output = input
        // Named entities first; "&amp;" is decoded LAST so the result of a
        // previous decoding can never be re-interpreted as an entity.
        for ((entity, replacement) in NAMED_ENTITIES) {
            output = output.replace(entity, replacement, ignoreCase = true)
        }
        output = DECIMAL_ENTITY_REGEX.replace(output) { match ->
            match.groupValues[1].toIntOrNull()?.let(::codePointToString) ?: match.value
        }
        output = HEX_ENTITY_REGEX.replace(output) { match ->
            match.groupValues[1].toIntOrNull(HEX_RADIX)?.let(::codePointToString) ?: match.value
        }
        return output.replace("&amp;", "&", ignoreCase = true)
    }

    private fun codePointToString(codePoint: Int): String? =
        if (codePoint in 1..Character.MAX_CODE_POINT) {
            String(Character.toChars(codePoint))
        } else {
            null
        }

    private fun readBytesCapped(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total >= MAX_DOCUMENT_BYTES) {
                throw IllegalStateException("Document is too large")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    companion object {
        private const val MAX_DOCUMENT_BYTES = 10 * 1024 * 1024 // 10 MB
        private const val READ_CHUNK_BYTES = 16 * 1024
        private const val FALLBACK_NAME = "document"
        private const val MIME_HTML = "text/html"
        private const val MIME_MARKDOWN = "text/markdown"
        private const val MIME_PLAIN = "text/plain"
        private const val HEX_RADIX = 16

        private val UNSUPPORTED_FILENAME_REGEX = Regex("[^A-Za-z0-9._-]")

        // (?is) = DOTALL + IGNORE_CASE — script/style bodies span lines.
        private val SCRIPT_STYLE_BLOCK_REGEX =
            Regex("(?is)<script\\b[^>]*>.*?</script\\s*>|<style\\b[^>]*>.*?</style\\s*>|<!--.*?-->")
        private val BLOCK_BREAK_REGEX = Regex("(?i)<br\\s*/?>|</p\\s*>|</div\\s*>|</li\\s*>")
        private val ROW_END_REGEX = Regex("(?i)</tr\\s*>")
        private val CELL_END_REGEX = Regex("(?i)</td\\s*>|</th\\s*>")
        private val ANY_TAG_REGEX = Regex("<[^>]*>")
        private val EXCESS_NEWLINES_REGEX = Regex("\n{3,}")
        private val DECIMAL_ENTITY_REGEX = Regex("&#(\\d+);")
        private val HEX_ENTITY_REGEX = Regex("&#x([0-9A-Fa-f]+);")

        private val NAMED_ENTITIES = mapOf(
            "&lt;" to "<",
            "&gt;" to ">",
            "&quot;" to "\"",
            "&#39;" to "'",
            "&nbsp;" to " ",
            "&mdash;" to "\u2014",
            "&ndash;" to "\u2013",
            "&hellip;" to "\u2026",
            "&laquo;" to "\u00AB",
            "&raquo;" to "\u00BB",
        )
    }
}

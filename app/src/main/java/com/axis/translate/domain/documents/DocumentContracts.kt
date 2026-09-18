package com.axis.translate.domain.documents

import android.content.Context
import android.net.Uri

/** Extracted document content ready for chunked translation (SPEC #24). */
data class DocumentContent(
    val title: String,
    val text: String,
    val mimeType: String
) {
    val isHtml: Boolean get() = mimeType.contains("html")
}

/**
 * Local document pipeline: TXT / MD / HTML extraction and re-export.
 * The original document is never modified — a new translated file is written.
 */
interface DocumentProcessor {
    /** Supported pickers: text/plain, text/markdown, text/html. */
    suspend fun extract(context: Context, uri: Uri): Result<DocumentContent>

    /**
     * Writes the translated document into app files/exports and returns a
     * content URI suitable for sharing via FileProvider.
     */
    suspend fun exportTranslated(context: Context, original: DocumentContent, translatedText: String, targetLanguageCode: String): Result<Uri>
}

package com.axis.translate.ui.navigation

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Share-target handoff: [com.axis.translate.MainActivity] parses ACTION_SEND
 * intents and parks the payload here; screens (Home, Photo, Documents) own
 * the corresponding flows, observe them, and consume the values.
 *
 * Consumers should null out only the slot they consumed (calling [clear]
 * would also drop payloads destined for other screens).
 */
object PendingInput {

    /** Plain text shared into the app (`text/plain`). */
    val sharedText = MutableStateFlow<String?>(null)

    /** Image shared into the app (any image MIME), routed to the photo pipeline. */
    val sharedImageUri = MutableStateFlow<Uri?>(null)

    /** Document shared into the app (html / markdown / octet-stream). */
    val sharedDocumentUri = MutableStateFlow<Uri?>(null)

    fun clear() {
        sharedText.value = null
        sharedImageUri.value = null
        sharedDocumentUri.value = null
    }
}

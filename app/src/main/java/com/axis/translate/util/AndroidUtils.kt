package com.axis.translate.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.axis.translate.AxisApp
import com.axis.translate.di.AppContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Access the app-wide DI container from composables. */
@Composable
fun rememberContainer(): AppContainer {
    val context = LocalContext.current.applicationContext
    return (context as AxisApp).container
}

object AndroidUtils {

    fun clipboardText(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }

    fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Axis Translate", text))
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share translation"))
    }

    fun shareFile(context: Context, uri: Uri, mime: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    fun formatDate(timestamp: Long): String =
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))

    fun formatDuration(ms: Long): String = when {
        ms >= 1000 -> "%.1fs".format(ms / 1000f)
        else -> "${ms}ms"
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824f)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }
}

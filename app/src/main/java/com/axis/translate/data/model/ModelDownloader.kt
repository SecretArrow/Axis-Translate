package com.axis.translate.data.model

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Streams a remote model file to local storage.
 *
 * The response body is streamed with a small buffer and reports progress at
 * most every [PROGRESS_INTERVAL_BYTES] bytes. Coroutine cancellation is checked
 * between reads; a cancelled or failed download always deletes the partial
 * file so a `.part` file never lingers as a phantom install candidate.
 */
class ModelDownloader {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads [url] into [destination] (creating parent directories as
     * needed). Returns [Result.success] with the destination on completion, or
     * [Result.failure] for non-2xx responses, IO errors, size mismatches
     * against a known `Content-Length`, and cancellation (partial file removed,
     * [CancellationException] rethrown to the caller).
     */
    suspend fun download(
        url: String,
        destination: File,
        onProgress: (bytesDownloaded: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val parent = destination.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            return@withContext Result.failure(IOException("Cannot create download directory: ${parent.absolutePath}"))
        }
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Download failed with HTTP ${response.code} for $url"))
                }
                val body = response.body
                    ?: return@withContext Result.failure(IOException("Download failed: empty response body"))
                val expectedBytes = body.contentLength()
                val written = try {
                    writeBodyToFile(body.byteStream(), destination, onProgress)
                } catch (cancellation: CancellationException) {
                    destination.delete()
                    throw cancellation
                } catch (io: IOException) {
                    destination.delete()
                    throw io
                }
                if (expectedBytes >= 0L && written != expectedBytes) {
                    destination.delete()
                    return@withContext Result.failure(
                        IOException("Download incomplete: received $written of $expectedBytes bytes"),
                    )
                }
                onProgress(written)
                Result.success(destination)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    private suspend fun writeBodyToFile(
        input: InputStream,
        destination: File,
        onProgress: (bytesDownloaded: Long) -> Unit,
    ): Long {
        var written = 0L
        var lastReported = 0L
        destination.outputStream().use { output ->
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                written += read
                if (written - lastReported >= PROGRESS_INTERVAL_BYTES) {
                    lastReported = written
                    onProgress(written)
                }
            }
        }
        return written
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 120L
        const val BUFFER_SIZE_BYTES = 8 * 1024
        const val PROGRESS_INTERVAL_BYTES = 512_000L
    }
}

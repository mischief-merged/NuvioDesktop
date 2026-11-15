package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_private_address
import org.jetbrains.compose.resources.getString
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import nuvio.composeapp.generated.resources.downloads_error_file_checksum
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories

private val desktopDownloadHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

internal actual object DownloadsPlatformDownloader {
    private val downloadsDir: File
        get() = File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())

    /**
     * A download that is stored as downloading was cut off when the app was closed. It is left as it
     * is, so that it is started again when the list is loaded and goes on from its partial file.
     * One the user paused is stored as paused and stays paused.
     */
    /** Whether the internet cannot be reached right now, asked when a source keeps failing. */
    internal var isOffline: suspend () -> Boolean = { !NetworkStatusRepository.isInternetReachable() }

    internal var waitBeforeRetry: suspend (delayMs: Long) -> Unit = { delay(it) }

    /** A transfer that receives nothing for this long is cut off and tried again. */
    internal var stallTimeoutMs: Long = 60_000L

    actual fun restoreItem(item: DownloadItem): DownloadItem = item

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)

        scope.launch {
            val destination = File(downloadsDir, request.destinationFileName)
            val tempFile = File(downloadsDir, "${request.destinationFileName}.part")

            // Dropped connections are retried in place (resuming from the .part file) with backoff.
            // The retry budget resets whenever an attempt makes progress, so long downloads survive
            // periodic disconnects and only fail after repeated attempts that receive nothing while
            // the internet can be reached. Without internet the download waits for it to come back.
            val retries = DownloadRetries(isOffline, waitBeforeRetry)
            while (true) {
                val bytesBeforeAttempt = tempFile.partialLength()
                try {
                    val transfer = transferToTempFile(request, tempFile, onProgress)
                    val totalBytes = transfer.totalBytes
                    if (transfer.digestMatched == false) {
                        // Not the body the server said it sent. Nothing of it is worth resuming.
                        tempFile.delete()
                        error(runBlocking { getString(Res.string.downloads_error_file_checksum) })
                    }
                    if (transfer.digestMatched == true) request.onDigestVerified()

                    if (destination.exists()) {
                        destination.delete()
                    }
                    if (!tempFile.renameTo(destination)) {
                        tempFile.copyTo(destination, overwrite = true)
                        tempFile.delete()
                    }

                    val finalSize = destination.length()
                    onSuccess(destination.toURI().toString(), totalBytes ?: finalSize)
                    return@launch
                } catch (error: CancellationException) {
                    onPaused()
                    throw error
                } catch (error: IOException) {
                    if (!retries.awaitNextAttempt(madeProgress = tempFile.partialLength() > bytesBeforeAttempt)) {
                        onFailure(error.message?.takeIf { it.isNotBlank() && it != "closed" } ?: "Connection lost")
                        return@launch
                    }
                } catch (error: Throwable) {
                    onFailure(error.message ?: "Download failed")
                    return@launch
                }
            }
        }

        return DesktopDownloadsTaskHandle(job)
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val tempFile = File(downloadsDir, "$destinationFileName.part")
        if (!tempFile.exists()) return true
        return runCatching { tempFile.delete() }.getOrDefault(false)
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalFileOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: return null
        return File(downloadsDir, fileName).takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val directory = downloadsDir
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()

        if (desktop != null && Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
            val opened = runCatching { desktop.open(directory) }.isSuccess
            if (opened) return true
        }

        return openDirectoryWithPlatformCommand(directory)
    }

    private class Transferred(
        val totalBytes: Long?,
        /** Whether the body matched the digest the server sent. Null when there was none to compare. */
        val digestMatched: Boolean?,
    )

    /**
     * Downloads into [tempFile], resuming from its current length when possible. Reports the total
     * size if known. A digest the server sent with the body is compared with what was written, but
     * only for a body that was received whole in this transfer: a transfer that resumed a partial
     * file saw only part of the body, so its digest is not checked.
     */
    private suspend fun transferToTempFile(
        request: DownloadPlatformRequest,
        tempFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Transferred {
        // The address check at queue time reads only the text of the URL. Here the name is resolved.
        if (request.item.autoDownloadRuleKey != null && !resolvesToPublicAddresses(request.sourceUrl)) {
            error(runBlocking { getString(Res.string.downloads_error_private_address) })
        }

        var resumeFromBytes = tempFile.partialLength()
        var attemptedRangeRequest = resumeFromBytes > 0L
        var response = sendDownloadRequest(request, if (attemptedRangeRequest) resumeFromBytes else null)

        if (attemptedRangeRequest && response.statusCode() == 416) {
            response.body().close()
            tempFile.delete()
            resumeFromBytes = 0L
            attemptedRangeRequest = false
            response = sendDownloadRequest(request, null)
        }

        val statusCode = response.statusCode()
        if (statusCode !in 200..299) {
            response.body().close()
            if (statusCode == 408 || statusCode == 429 || statusCode in 500..599) {
                throw IOException("Download failed with HTTP $statusCode")
            }
            error("Download failed with HTTP $statusCode")
        }

        val isPartialResume = attemptedRangeRequest && statusCode == 206 && resumeFromBytes > 0L
        val appendToTemp = isPartialResume
        val startingBytes = if (appendToTemp) resumeFromBytes else 0L
        if (!appendToTemp && tempFile.exists()) {
            tempFile.delete()
        }

        val totalBytes = resolveTotalBytes(
            startingBytes = startingBytes,
            isPartialResume = isPartialResume,
            contentRangeHeader = response.headers().firstValue("Content-Range").orElse(null),
            contentLength = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull(),
        )
        var downloadedBytes = startingBytes
        onProgress(downloadedBytes, totalBytes)
        val expectedDigest = if (statusCode == 200 && !appendToTemp) {
            expectedBodyDigest { name -> response.headers().firstValue(name).orElse(null) }
        } else {
            null
        }
        val digest = expectedDigest?.let { runCatching { MessageDigest.getInstance(it.algorithm) }.getOrNull() }

        response.body().use { input ->
            coroutineScope {
                // A connection that goes silent never ends the read by itself. Closing the body
                // ends it, and the transfer is then tried again like any other dropped connection.
                val lastReadAtNanos = AtomicLong(System.nanoTime())
                val stalled = AtomicBoolean(false)
                val stallWatch = launch {
                    val timeoutMs = stallTimeoutMs
                    while (true) {
                        delay((timeoutMs / 4).coerceAtLeast(1L))
                        if (System.nanoTime() - lastReadAtNanos.get() > timeoutMs * 1_000_000L) {
                            stalled.set(true)
                            runCatching { input.close() }
                            break
                        }
                    }
                }
                try {
                    FileOutputStream(tempFile, appendToTemp).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            lastReadAtNanos.set(System.nanoTime())
                            output.write(buffer, 0, read)
                            digest?.update(buffer, 0, read)
                            downloadedBytes += read.toLong()
                            onProgress(downloadedBytes, totalBytes)
                        }
                        output.flush()
                    }
                } catch (error: IOException) {
                    throw if (stalled.get()) IOException("Connection stalled") else error
                } finally {
                    stallWatch.cancel()
                }
                if (stalled.get()) throw IOException("Connection stalled")
            }
        }

        // A server that ends the stream early would otherwise leave a truncated file marked as completed.
        if (totalBytes != null && downloadedBytes < totalBytes) {
            throw IOException("Connection closed after $downloadedBytes of $totalBytes bytes")
        }
        return Transferred(
            totalBytes = totalBytes,
            digestMatched = if (digest != null && expectedDigest != null) digest.digest().contentEquals(expectedDigest.value) else null,
        )
    }

    private fun sendDownloadRequest(
        request: DownloadPlatformRequest,
        rangeStart: Long?,
    ): HttpResponse<java.io.InputStream> {
        val builder = HttpRequest.newBuilder()
            .uri(URI(request.sourceUrl))
            .timeout(Duration.ofSeconds(60))
            .GET()
        request.sourceHeaders.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                builder.header(key, value)
            }
        }
        if (rangeStart != null && rangeStart > 0L) {
            builder.header("Range", "bytes=$rangeStart-")
        }
        return desktopDownloadHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }
}

/** False when the host of [url] cannot be resolved or any of its addresses is not a public one. */
private fun resolvesToPublicAddresses(url: String): Boolean {
    val addresses = runCatching { InetAddress.getAllByName(URI(url).host) }.getOrNull()
    return !addresses.isNullOrEmpty() && addresses.none(::isPrivateNetworkAddress)
}

/** Loopback, link-local, private-network and carrier-shared addresses, and the unspecified address. */
internal fun isPrivateNetworkAddress(address: InetAddress): Boolean {
    val bytes = address.address
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    return address.isLoopbackAddress ||
        address.isAnyLocalAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        (address is Inet6Address && (first and 0xfe) == 0xfc) ||
        (bytes.size == 4 && (first == 0 || (first == 100 && second in 64..127)))
}

private fun openDirectoryWithPlatformCommand(directory: File): Boolean {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        osName.contains("mac") -> listOf("open", directory.absolutePath)
        osName.contains("win") -> listOf("explorer", directory.absolutePath)
        else -> listOf("xdg-open", directory.absolutePath)
    }
    return runCatching { ProcessBuilder(command).start() }.isSuccess
}

private class DesktopDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    override fun cancel() {
        job.cancel()
    }
}

private fun File.partialLength(): Long =
    takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L

private fun String.toLocalFileOrNull(): File? =
    runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()

private fun resolveTotalBytes(
    startingBytes: Long,
    isPartialResume: Boolean,
    contentRangeHeader: String?,
    contentLength: Long?,
): Long? {
    parseContentRangeTotal(contentRangeHeader)?.let { return it }
    val normalizedLength = contentLength?.takeIf { it > 0L } ?: return null
    return if (isPartialResume && startingBytes > 0L) {
        startingBytes + normalizedLength
    } else {
        normalizedLength
    }
}

private fun parseContentRangeTotal(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isBlank()) return null
    val slashIndex = value.lastIndexOf('/')
    if (slashIndex == -1 || slashIndex == value.lastIndex) return null
    val totalPart = value.substring(slashIndex + 1).trim()
    if (totalPart == "*") return null
    return totalPart.toLongOrNull()?.takeIf { it > 0L }
}

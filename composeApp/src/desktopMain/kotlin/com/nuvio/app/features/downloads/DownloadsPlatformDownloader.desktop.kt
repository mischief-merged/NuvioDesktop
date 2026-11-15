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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_not_enough_space
import nuvio.composeapp.generated.resources.downloads_error_private_address
import org.jetbrains.compose.resources.getString
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
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
    /** The folder of the app's own downloads, and the one the "open" button shows when the default is away. */
    private val appDownloadsDir: File
        get() = File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())

    /**
     * A download that is stored as downloading was cut off when the app was closed. It is left as it
     * is, so that it is started again when the list is loaded and goes on from its partial file.
     * One the user paused is stored as paused and stays paused.
     */
    /** Whether the internet cannot be reached right now, asked when a source keeps failing. */
    internal var isOffline: suspend () -> Boolean = { !NetworkStatusRepository.isInternetReachable() }

    /** Free space on the volume of the downloads folder, or null when the system does not tell. Replaced in tests. */
    internal var freeBytesProvider: () -> Long? = UsePerLocationFreeSpace

    /** Free space on the volume of the location the item goes to, unless a test replaced [freeBytesProvider]. */
    private fun freeBytesFor(item: DownloadItem): Long? =
        if (freeBytesProvider !== UsePerLocationFreeSpace) freeBytesProvider() else DesktopDownloads.folders.freeBytesOf(item)

    /** How often the repository is told how far a transfer has come. The stored list is written each time. */
    internal var progressIntervalMs: Long = ProgressIntervalMs

    internal var waitBeforeRetry: suspend (delayMs: Long) -> Unit = { delay(it) }

    /** A transfer that receives nothing for this long is cut off and tried again. */
    internal var stallTimeoutMs: Long = 60_000L

    /**
     * An item whose file or partial file is in another location than its record says, after a move
     * that was cut short, follows the file.
     */
    /**
     * A file is found only where its record says. Nothing is adopted from another location by name. A
     * finished record written before addresses were kept is given its address once, only when the file
     * is verifiably its own (see [legacyAddressFor]).
     */
    actual fun restoreItem(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed || item.localFileUri != null) return item
        val profileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId
        val address = runCatching {
            legacyAddressFor(item, profileId, DesktopDownloads.folders.locations, storedRecordsOfAllProfiles(DesktopStorage.store("nuvio_downloads")))
        }.getOrNull() ?: return item
        return item.copy(localFileUri = address)
    }

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val handle = DesktopDownloadsTaskHandle(job)

        scope.launch {
            // A download goes to the location of its record. When that is away, or a move is
            // carrying the item, it waits and goes on by itself when it can.
            val folders = DesktopDownloads.folders
            if (folders.heldByFolder(request.item)) {
                onPaused()
                return@launch
            }
            // One writer per download. One that was paused may still be closing its file: it is waited for.
            if (!WriterTokens.acquire(request.item.id, WriterTokens.Kind.Writer, WriterWaitMs)) {
                onPaused()
                return@launch
            }
            try {
            val location = folders.locations.locationOfRecord(request.item.locationId, null)
            val downloadsDir = File(location.path.also { if (location.id == DownloadLocations.AppId) it.createDirectories() }.toUri())
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
                } catch (error: TransferStoppedByLimit) {
                    request.limits?.onStopped?.invoke(error.reason)
                    return@launch
                } catch (error: IOException) {
                    // A full disk is still full after a wait, so it is not tried again.
                    if (classifyDownloadFailure(error.message.orEmpty()) == DownloadFailureKind.NotEnoughSpace) {
                        onFailure(runBlocking { getString(Res.string.downloads_error_not_enough_space) })
                        return@launch
                    }
                    if (!retries.awaitNextAttempt(madeProgress = tempFile.partialLength() > bytesBeforeAttempt)) {
                        onFailure(error.message?.takeIf { it.isNotBlank() && it != "closed" } ?: "Connection lost")
                        return@launch
                    }
                } catch (error: Throwable) {
                    onFailure(error.message ?: "Download failed")
                    return@launch
                }
            }
            } finally {
                // Only now, with the file closed, can anything else write or move it.
                WriterTokens.release(request.item.id)
            }
        }

        return handle
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        // Without a record to say where, only the app's own folder: never a file of that name elsewhere.
        val tempFile = File(appDownloadsDir, "$destinationFileName.part")
        return !tempFile.exists() || runCatching { tempFile.delete() }.getOrDefault(false)
    }

    /** The recorded address when a file is there. Never a file found by its name instead. */
    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.isFile }
            ?.let { return it.toURI().toString() }
        return (DesktopDownloads.folders.locations.locate(null, localFileUri, "") as? FileLookup.Found)?.uri
    }

    actual fun openDownloadsDirectory(): Boolean {
        val folders = DesktopDownloads.folders
        val defaultLocation = folders.locations.defaultLocation()
        val directory = if (folders.locations.availability(defaultLocation) == FolderAvailability.Available) {
            defaultLocation.path.also { runCatching { it.createDirectories() } }.toFile()
        } else {
            appDownloadsDir
        }
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()

        if (desktop != null && Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
            val opened = runCatching { desktop.open(directory) }.isSuccess
            if (opened) return true
        }

        return openDirectoryWithPlatformCommand(directory)
    }

    internal class Transferred(
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
    internal suspend fun transferToTempFile(
        request: DownloadPlatformRequest,
        tempFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        limiter: DownloadSpeedLimiter = DownloadBandwidth.limiter.also { DownloadBandwidth.applyStoredLimit() },
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
        val reporter = ThrottledProgress(progressIntervalMs, System::nanoTime) { bytes -> onProgress(bytes, totalBytes) }
        // Checked before anything of the body is written, for every download: a file announced as
        // larger than the room on the disk fails with a plain message instead of a system error.
        if (totalBytes != null) {
            val free = freeBytesFor(request.item)
            if (free != null && totalBytes - startingBytes > free) {
                response.body().close()
                error(runBlocking { getString(Res.string.downloads_error_not_enough_space) })
            }
        }
        // Checked before anything of the body is written: a file that is announced as too large for
        // the limits of an automatic download is not started.
        val limits = request.limits
        limits?.stopBeforeBody(downloadedBytes, totalBytes, freeBytesFor(request.item))?.let { reason ->
            response.body().close()
            throw TransferStoppedByLimit(reason)
        }
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
                // Cancelling the transfer closes the body, which ends a read that is blocked on a
                // silent connection, so a pause or a move does not wait for data that never comes.
                val closer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { input.close() }
                    }
                }
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
                        val buffer = ByteArray(ReadBufferBytes)
                        var nextLimitCheckAtBytes = downloadedBytes + LimitCheckIntervalBytes
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer, 0, readChunkSize(limiter.bytesPerSecond))
                            if (read <= 0) break
                            lastReadAtNanos.set(System.nanoTime())
                            output.write(buffer, 0, read)
                            digest?.update(buffer, 0, read)
                            downloadedBytes += read.toLong()
                            reporter.offer(downloadedBytes)
                            limiter.throttle(read)
                            // Waiting for the speed limit is not a connection that went silent.
                            lastReadAtNanos.set(System.nanoTime())
                            // A body without an announced size, or one that is longer than announced, is
                            // caught here, as is free space that other writers used up meanwhile.
                            if (limits != null && downloadedBytes >= nextLimitCheckAtBytes) {
                                nextLimitCheckAtBytes = downloadedBytes + LimitCheckIntervalBytes
                                limits.stopFor(downloadedBytes, totalBytes, freeBytesFor(request.item))?.let { reason ->
                                    output.flush()
                                    throw TransferStoppedByLimit(reason)
                                }
                            }
                        }
                        output.flush()
                    }
                } catch (error: IOException) {
                    // A read that ended because the transfer was cancelled is a cancellation, not a dropped connection.
                    currentCoroutineContext().ensureActive()
                    throw if (stalled.get()) IOException("Connection stalled") else error
                } finally {
                    stallWatch.cancel()
                    closer.cancel()
                    // The last value is always reported, whether the transfer ended, failed or was stopped.
                    reporter.flush(downloadedBytes)
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

    private suspend fun sendDownloadRequest(
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
        // Interrupted when the transfer is cancelled, so that waiting for a server does not hold it.
        return runInterruptible(Dispatchers.IO) {
            desktopDownloadHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        }
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

    override fun cancelAndWait(timeoutMs: Long): Boolean {
        job.cancel()
        return runBlocking { withTimeoutOrNull(timeoutMs) { job.children.toList().forEach { it.join() } } } != null
    }
}

/** How long a new transfer waits for a writer that was paused and is still closing its file. */
internal const val WriterWaitMs = 30_000L

/** The bytes read from the connection at a time. */
internal const val ReadBufferBytes = 256 * 1024

/** The least time between two reports of progress. */
internal const val ProgressIntervalMs = 250L

/**
 * Passes progress on at most once every [intervalMs], and the last value always, through [flush].
 * Without it every read would reach the stored list.
 */
internal class ThrottledProgress(
    private val intervalMs: Long,
    private val nanoTime: () -> Long,
    private val report: (Long) -> Unit,
) {
    private var lastReportedNanos = Long.MIN_VALUE
    private var lastReportedBytes = -1L

    fun offer(bytes: Long) {
        val now = nanoTime()
        if (lastReportedNanos == Long.MIN_VALUE || now - lastReportedNanos >= intervalMs * 1_000_000L) {
            lastReportedNanos = now
            lastReportedBytes = bytes
            report(bytes)
        }
    }

    fun flush(bytes: Long) {
        if (bytes != lastReportedBytes) {
            lastReportedBytes = bytes
            report(bytes)
        }
    }
}

/**
 * How much to read at a time. A low limit gets small reads, so that the wait for the limit is spread
 * over the transfer and not paid in one long pause after a large chunk.
 */
internal fun readChunkSize(limitBytesPerSecond: Long?): Int =
    if (limitBytesPerSecond == null) ReadBufferBytes
    else (limitBytesPerSecond / 10).coerceIn(4 * 1024L, ReadBufferBytes.toLong()).toInt()

private val UsePerLocationFreeSpace: () -> Long? = { null }

private class TransferStoppedByLimit(val reason: AutoDownloadStop) : RuntimeException()

private const val LimitCheckIntervalBytes = 256L * 1024

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

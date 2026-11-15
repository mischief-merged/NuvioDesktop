package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable

/** Why an automatic transfer was stopped, or not started, by a limit. */
@Serializable
enum class AutoDownloadStop {
    /** The storage limit for automatic downloads would be exceeded. */
    StorageLimit,

    /** The downloads volume would be left with less free space than automatic downloads must leave. */
    LowDiskSpace,
}

/** What limits automatic downloads. Downloads the user starts are never limited by it. */
internal interface AutoDownloadLimits {
    /** The most bytes all automatic downloads together may take, or null for no limit. */
    fun storageLimitBytes(): Long?

    /** Free space on the downloads volume that an automatic transfer must leave untouched. */
    val minFreeBytes: Long
}

internal object SavedAutoDownloadLimits : AutoDownloadLimits {
    const val MinFreeBytes = 10L * BytesPerGigabyte

    override fun storageLimitBytes(): Long? = AutoDownloads.storageLimitBytes()

    override val minFreeBytes: Long get() = MinFreeBytes
}

internal const val BytesPerGigabyte = 1_000_000_000L

/**
 * The limits of one automatic transfer, asked by the platform downloader before it writes the body
 * and again while it writes.
 */
internal class DownloadTransferLimits(
    /** The most bytes this file may have, given what the other automatic downloads take. Null: no limit. */
    private val maxTotalBytes: () -> Long?,
    private val minFreeBytes: Long,
    /** Called once when the transfer stops because of a limit. Nothing else is reported for it. */
    val onStopped: (AutoDownloadStop) -> Unit,
    /**
     * Runs the check made before the body and, when it allows the transfer, the reservation of the
     * announced size as one step, so that two transfers cannot both take the same remaining room.
     */
    private val checkAndReserve: (check: () -> AutoDownloadStop?, announcedBytes: Long?) -> AutoDownloadStop? =
        { check, _ -> check() },
) {
    /** [stopFor] for a transfer that is about to write its body. A size that is allowed is reserved. */
    fun stopBeforeBody(downloadedBytes: Long, totalBytes: Long?, freeBytes: Long?): AutoDownloadStop? =
        checkAndReserve({ stopFor(downloadedBytes, totalBytes, freeBytes) }, totalBytes)

    /**
     * The limit that does not allow the transfer to go on, if there is one. [totalBytes] is the size
     * the server announced, when it did, and [freeBytes] the free space on the downloads volume,
     * when it is known.
     */
    fun stopFor(downloadedBytes: Long, totalBytes: Long?, freeBytes: Long?): AutoDownloadStop? {
        val size = maxOf(totalBytes ?: 0L, downloadedBytes)
        val max = maxTotalBytes()
        if (max != null && size > max) return AutoDownloadStop.StorageLimit
        if (freeBytes != null && freeBytes - (size - downloadedBytes) < minFreeBytes) return AutoDownloadStop.LowDiskSpace
        return null
    }
}

package com.nuvio.app.features.downloads

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_result_basic
import nuvio.composeapp.generated.resources.downloads_result_not_checked
import nuvio.composeapp.generated.resources.downloads_result_suspect
import nuvio.composeapp.generated.resources.downloads_result_verified_fingerprint
import nuvio.composeapp.generated.resources.downloads_result_verified_server
import nuvio.composeapp.generated.resources.downloads_result_verified_size
import nuvio.composeapp.generated.resources.downloads_status_checking
import nuvio.composeapp.generated.resources.downloads_status_completed
import nuvio.composeapp.generated.resources.downloads_status_downloading
import nuvio.composeapp.generated.resources.downloads_status_failed
import nuvio.composeapp.generated.resources.downloads_status_paused
import nuvio.composeapp.generated.resources.downloads_status_paused_low_disk
import nuvio.composeapp.generated.resources.downloads_status_paused_storage_limit
import nuvio.composeapp.generated.resources.downloads_status_reconnecting
import nuvio.composeapp.generated.resources.downloads_status_waiting_network
import org.jetbrains.compose.resources.StringResource

/** A running transfer that has received nothing for this long is shown as reconnecting. */
internal const val DownloadStalledAfterMs = 20_000L

/** What a download row says it is doing, worked out from the item alone. */
internal enum class DownloadPhase(
    val message: StringResource,
    /** Whether the message has a place for "downloaded / total". */
    val showsSize: Boolean,
) {
    Downloading(Res.string.downloads_status_downloading, showsSize = true),

    /** The internet cannot be reached: the transfer waits and goes on by itself. */
    WaitingForNetwork(Res.string.downloads_status_waiting_network, showsSize = true),

    /** The internet looks reachable but nothing arrives: the transfer is being tried again. */
    Reconnecting(Res.string.downloads_status_reconnecting, showsSize = true),

    /** All bytes are in; the file and its pictures are being looked at. */
    CheckingFile(Res.string.downloads_status_checking, showsSize = false),

    Paused(Res.string.downloads_status_paused, showsSize = true),
    PausedStorageLimit(Res.string.downloads_status_paused_storage_limit, showsSize = true),
    PausedLowDiskSpace(Res.string.downloads_status_paused_low_disk, showsSize = true),
    Completed(Res.string.downloads_status_completed, showsSize = true),
    Failed(Res.string.downloads_status_failed, showsSize = false),
}

/** What was checked about a finished file, in the words the row shows. */
internal enum class DownloadResult(val message: StringResource) {
    /** The hash the source stated for its file matched. */
    VerifiedBySource(Res.string.downloads_result_verified_fingerprint),

    /** The fingerprint the server sent with the file matched. */
    VerifiedByServer(Res.string.downloads_result_verified_server),

    /** The size the server or source announced matched. */
    VerifiedBySize(Res.string.downloads_result_verified_size),

    /** Only that the file is there and is a video. */
    BasicCheck(Res.string.downloads_result_basic),

    /** Nothing is recorded: the download finished before checks were recorded. */
    NotChecked(Res.string.downloads_result_not_checked),

    /** The file's own title names another episode. */
    Suspect(Res.string.downloads_result_suspect),
}

internal data class DownloadRowStatus(
    val phase: DownloadPhase,
    /** Set for a finished download. */
    val result: DownloadResult? = null,
    /** Frames of the finished file were decoded and looked like a video. */
    val pictureChecked: Boolean = false,
    /** Queued by an automatic-download rule. */
    val isAuto: Boolean = false,
)

/**
 * The sub-status of a download row.
 *
 * [nowEpochMs] is the time of the look and [offline] whether the internet cannot be reached; the
 * rest is read from [item]. Nothing here is stored.
 */
internal fun downloadRowStatus(
    item: DownloadItem,
    nowEpochMs: Long,
    offline: Boolean = false,
): DownloadRowStatus {
    val isAuto = item.autoDownloadRuleKey != null
    return when (item.status) {
        DownloadStatus.Downloading -> {
            val total = item.totalBytes
            val allBytesIn = total != null && total > 0L && item.downloadedBytes >= total
            val silentFor = nowEpochMs - item.updatedAtEpochMs
            val phase = when {
                allBytesIn -> DownloadPhase.CheckingFile
                offline -> DownloadPhase.WaitingForNetwork
                silentFor > DownloadStalledAfterMs -> DownloadPhase.Reconnecting
                else -> DownloadPhase.Downloading
            }
            DownloadRowStatus(phase, isAuto = isAuto)
        }
        DownloadStatus.Paused -> DownloadRowStatus(
            phase = when (item.autoDownloadStop) {
                AutoDownloadStop.StorageLimit -> DownloadPhase.PausedStorageLimit
                AutoDownloadStop.LowDiskSpace -> DownloadPhase.PausedLowDiskSpace
                null -> DownloadPhase.Paused
            },
            isAuto = isAuto,
        )
        DownloadStatus.Failed -> DownloadRowStatus(DownloadPhase.Failed, isAuto = isAuto)
        DownloadStatus.Completed -> DownloadRowStatus(
            phase = DownloadPhase.Completed,
            result = when {
                item.framesCheck == DownloadFramesCheck.Suspect -> DownloadResult.Suspect
                else -> when (item.verifiedBy) {
                    DownloadVerification.Hash -> DownloadResult.VerifiedBySource
                    DownloadVerification.Digest -> DownloadResult.VerifiedByServer
                    DownloadVerification.Size -> DownloadResult.VerifiedBySize
                    DownloadVerification.None -> DownloadResult.BasicCheck
                    null -> DownloadResult.NotChecked
                }
            },
            pictureChecked = item.framesCheck == DownloadFramesCheck.Passed,
            isAuto = isAuto,
        )
    }
}

/** The name of a download in a label that is read out of its row: "Pantheon S02E03", or the title of a movie. */
internal fun DownloadItem.nameForLabels(): String {
    val season = seasonNumber
    val episode = episodeNumber
    if (season == null || episode == null) return title
    return "$title S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
}

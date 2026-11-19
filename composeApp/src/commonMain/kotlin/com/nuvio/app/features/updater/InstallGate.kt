package com.nuvio.app.features.updater

import com.nuvio.app.core.ui.AppPresenceState
import com.nuvio.app.core.ui.PresenceSnapshot
import com.nuvio.app.features.downloads.DownloadsRepository

internal enum class InstallBlockReason {
    VIDEO_PLAYING,
    DOWNLOAD_ACTIVE,
    VIDEO_AND_DOWNLOAD,
}

internal class InstallDeferredException(
    val reason: InstallBlockReason,
) : IllegalStateException("Installation deferred because activity resumed: $reason")

internal class InstallGate(
    private val isVideoPlaying: () -> Boolean = {
        (AppPresenceState.current.value as? PresenceSnapshot.Player)?.isPlaying == true
    },
    private val isDownloadBusy: () -> Boolean = {
        DownloadsRepository.hasActiveOrFinishingDownloads()
    },
) {
    fun blockReason(): InstallBlockReason? {
        val playing = isVideoPlaying()
        val downloading = isDownloadBusy()
        return when {
            playing && downloading -> InstallBlockReason.VIDEO_AND_DOWNLOAD
            playing -> InstallBlockReason.VIDEO_PLAYING
            downloading -> InstallBlockReason.DOWNLOAD_ACTIVE
            else -> null
        }
    }

    fun isReadyToInstall(): Boolean = blockReason() == null
}

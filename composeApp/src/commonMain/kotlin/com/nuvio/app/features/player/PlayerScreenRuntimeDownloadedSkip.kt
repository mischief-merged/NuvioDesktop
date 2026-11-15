package com.nuvio.app.features.player

import com.nuvio.app.features.downloads.DownloadCompanions
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.OfflineSkipTimes
import com.nuvio.app.features.downloads.SidecarIo
import com.nuvio.app.features.downloads.SkipTimesRequest

/**
 * Skip intervals for a title played from a finished download. Returns false when the title is not
 * one, and the caller then looks the intervals up online as for any other source.
 *
 * The intervals saved beside the file are used at once, with no network. Online, the services are
 * asked again in the background and the intervals are replaced only by a different answer; an
 * unreachable or slow service leaves what is saved in place. The merge with chapters in the file
 * is the usual one in [recomputeMergedSkipIntervals].
 */
internal suspend fun PlayerScreenRuntime.applyDownloadedSkipIntervals(): Boolean {
    val localUri = activeSourceUrl.takeIf { it.startsWith("file:") } ?: return false
    // Every look at the disk is off the main thread and bounded; the result is applied here.
    // When the drive is too slow to even tell, the title is looked up online like any other source.
    if (SidecarIo.read { DownloadsRepository.findCompletedDownloadByLocalFile(localUri) } == null) return false
    val skipTimes = OfflineSkipTimes.app

    apiSkipIntervals = SidecarIo.read { skipTimes.saved(localUri) }.orEmpty()
    recomputeMergedSkipIntervals()

    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return true
    val imdbFromContent = parentMetaId.takeIf { it.startsWith("tt") }
        ?: (metaUiState.meta ?: playerMeta)
            ?.takeIf { it.id == parentMetaId }
            ?.imdbId
            ?.takeIf { it.startsWith("tt") }
    val changed = skipTimes.refresh(
        SkipTimesRequest(
            isMovie = (contentType ?: parentMetaType).equals("movie", ignoreCase = true),
            parentMetaId = parentMetaId,
            videoId = videoId,
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
            imdbId = imdbFromContent,
        ),
        localUri,
    ) ?: return true
    apiSkipIntervals = changed
    recomputeMergedSkipIntervals()
    return true
}

/**
 * When a finished download is played online, saves what it still lacks beside it (subtitles and
 * skip times for downloads made before they were saved). Quiet, and never touches what is saved.
 * What is saved now is used the next time the file is played.
 */
internal suspend fun PlayerScreenRuntime.saveCompanionsOfPlayingDownload() {
    val localUri = activeSourceUrl.takeIf { it.startsWith("file:") } ?: return
    val item = SidecarIo.read { DownloadsRepository.findCompletedDownloadByLocalFile(localUri) } ?: return
    DownloadCompanions.app.ensure(item, localUri)
}

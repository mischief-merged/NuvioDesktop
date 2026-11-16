package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.looksLikePlaceholder

/*
 * Whether a downloaded file is the video, or the short clip a provider serves in its place ("not
 * ready", "failed"). The look weighs what is known and says nothing about what is not:
 *
 *  - size: the file is far smaller than the size the source claimed for it. Decisive on its own,
 *    because a source's size and the file it serves do not differ by a factor of ten, except when
 *    the file is not what was announced. Only a claim of a real size counts: a claim below
 *    [PlaceholderMinClaimBytes] is too small to say anything.
 *  - bitrate: the file's size over the running time of the title is below [PlaceholderMinBitsPerSecond],
 *    which no real video of that length reaches. Decisive on its own, and needs no claim from the
 *    source, only the title's running time. Only a finished file is judged.
 *  - running time: the file is under ten minutes and under a fifth of the running time of the
 *    title, the same rule playback uses ([looksLikePlaceholder]), here on the duration the file
 *    states. Weak, because a runtime can be wrong.
 *  - picture: the frames are one still picture or blank. Weak, because a title card can be long.
 *
 * One decisive signal, or two weak ones, make the verdict. Anything less never flags: a short film
 * and a trailer-length extra carry their own short running time, a small file of low quality has
 * no claim to be far below, and with nothing to compare the answer is that it could not be checked,
 * never a pass and never a fail.
 */

/** The file must be smaller than this part of the claimed size... */
internal const val PlaceholderSizeFraction = 0.10

/** ...of a claim of at least this size (200 MB), for the size alone to decide. */
internal const val PlaceholderMinClaimBytes = 200_000_000L

/**
 * A file whose size, spread over the whole running time of the title, comes to less than this many
 * bits per second (100 kbit/s) cannot be that title: even a poor video or an audio-only file is above it.
 */
internal const val PlaceholderMinBitsPerSecond = 100_000L

/** What the file is to be compared with, as far as it is known. */
internal class PlaceholderEvidence(
    /** Bytes in the file. Null when the file could not be looked at. */
    val fileBytes: Long?,
    /** The size the source claimed. Null when none is known. */
    val claimedBytes: Long?,
    /** How long the file says it runs. Null when it could not be read. */
    val durationMs: Long?,
    /** The running time of the title in minutes. Null when it is not known. */
    val runtimeMinutes: Int?,
    /** What a look at decoded frames found, when one was taken. Only a still or blank picture counts here. */
    val frames: DownloadedFramesProblem? = null,
)

/** Why a file is taken for a placeholder. */
internal sealed interface PlaceholderReason {
    /** [fileBytes] arrived and the source claimed [claimedBytes]. */
    data class SizeBelowClaim(val fileBytes: Long, val claimedBytes: Long) : PlaceholderReason

    /** [fileBytes] is far too small for a title that runs [runtimeMinutes]: its bitrate would be under the floor. */
    data class TooSmallForRuntime(val fileBytes: Long, val runtimeMinutes: Int) : PlaceholderReason

    /** The file runs [durationMs] where the title runs about [runtimeMinutes], and its picture does not change either. */
    data class ShortForRuntime(val durationMs: Long, val runtimeMinutes: Int) : PlaceholderReason
}

internal sealed interface PlaceholderVerdict {
    /** Not the video. */
    data class NotTheRealVideo(val reason: PlaceholderReason) : PlaceholderVerdict

    /** Something was compared and the file is in line with it. */
    data object LooksRight : PlaceholderVerdict

    /** There was nothing, or too little, to compare. Says nothing about the file. */
    data object CouldNotCheck : PlaceholderVerdict
}

/** Whether the size of the file is far below a claim big enough to judge by. */
internal fun isFarBelowClaim(fileBytes: Long?, claimedBytes: Long?): Boolean {
    val file = fileBytes?.takeIf { it >= 0L } ?: return false
    val claim = claimedBytes?.takeIf { it >= PlaceholderMinClaimBytes } ?: return false
    return file.toDouble() < claim.toDouble() * PlaceholderSizeFraction
}

/** Whether [bytes] spread over [runtimeMinutes] is a bitrate under [PlaceholderMinBitsPerSecond]. False when either is unknown. */
internal fun isTooSmallForRuntime(bytes: Long?, runtimeMinutes: Int?): Boolean {
    val size = bytes?.takeIf { it >= 0L } ?: return false
    val minutes = runtimeMinutes?.takeIf { it > 0 } ?: return false
    return size.toDouble() * 8.0 / (minutes * 60.0) < PlaceholderMinBitsPerSecond.toDouble()
}

/**
 * Whether the server's announced total at the start of a transfer is far below a claim big enough
 * to judge by, so that the source can be given up before the placeholder is downloaded.
 */
internal fun announcedSizeIsFarBelowClaim(announcedBytes: Long?, claimedBytes: Long?): Boolean =
    announcedBytes != null && announcedBytes > 0L && isFarBelowClaim(announcedBytes, claimedBytes)

internal fun placeholderVerdict(evidence: PlaceholderEvidence): PlaceholderVerdict {
    val fileBytes = evidence.fileBytes
    val claimed = evidence.claimedBytes
    if (fileBytes != null && claimed != null && isFarBelowClaim(fileBytes, claimed)) {
        return PlaceholderVerdict.NotTheRealVideo(PlaceholderReason.SizeBelowClaim(fileBytes, claimed))
    }

    val duration = evidence.durationMs?.takeIf { it > 0L }
    val runtime = evidence.runtimeMinutes?.takeIf { it > 0 }
    if (fileBytes != null && runtime != null && isTooSmallForRuntime(fileBytes, runtime)) {
        return PlaceholderVerdict.NotTheRealVideo(PlaceholderReason.TooSmallForRuntime(fileBytes, runtime))
    }
    val canJudgeLength = duration != null && runtime != null
    val shortForRuntime = canJudgeLength && looksLikePlaceholder(duration!!, runtime)
    val pictureProblem = evidence.frames?.takeIf {
        it == DownloadedFramesProblem.StillPicture || it == DownloadedFramesProblem.Blank
    }

    if (shortForRuntime && pictureProblem != null) {
        return PlaceholderVerdict.NotTheRealVideo(PlaceholderReason.ShortForRuntime(duration!!, runtime!!))
    }

    val sizeJudged = fileBytes != null && claimed != null && claimed >= PlaceholderMinClaimBytes
    // One weak signal on its own proves nothing, and neither does a comparison that was not possible.
    if (shortForRuntime || pictureProblem != null) return PlaceholderVerdict.CouldNotCheck
    return if (sizeJudged || canJudgeLength || (fileBytes != null && runtime != null)) PlaceholderVerdict.LooksRight else PlaceholderVerdict.CouldNotCheck
}

/** Minutes as a few words: "5 minutes", "1 hour", "about 2 hours" without the "about". */
internal fun formatRunLength(minutes: Int): String = when {
    minutes < 1 -> "less than a minute"
    minutes == 1 -> "1 minute"
    minutes < 90 -> "$minutes minutes"
    else -> {
        val tenths = (minutes * 10 + 30) / 60
        val hours = tenths / 10.0
        if (tenths % 10 == 0) "${tenths / 10} hours" else "$hours hours"
    }
}

/** How long a file runs, in the words of [formatRunLength]: "5 minutes", "45 seconds". */
internal fun formatFileLength(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(0L)
    return if (seconds < 90L) {
        if (seconds == 1L) "1 second" else "$seconds seconds"
    } else {
        formatRunLength(((seconds + 30L) / 60L).toInt())
    }
}

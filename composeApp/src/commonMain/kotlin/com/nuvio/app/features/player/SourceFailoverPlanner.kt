package com.nuvio.app.features.player

import com.nuvio.app.core.network.NetworkCondition

/** How far before the point where a source died the next one resumes, so nothing is missed. */
internal const val SOURCE_FAILOVER_RESUME_BACKOFF_MS = 2_000L

/** The resume point stays at least this far before the end, so a source that died at the end still plays something. */
internal const val SOURCE_FAILOVER_END_MARGIN_MS = 1_000L

/**
 * A source the user picked by hand that never starts is not replaced silently: the error screen
 * stays, because the user chose that source on purpose and can pick another one from the list.
 * Set to `true` to treat a start failure in Manual mode like one in an auto-play mode.
 */
internal const val SOURCE_FAILOVER_ON_START_IN_MANUAL_MODE = false

internal enum class SourceFailure {
    /** The source errored, or showed no first frame in time. */
    FailedToStart,

    /** The source errored after playback had started. */
    DiedDuringPlayback,

    /**
     * The source plays without an error, but what plays is a short placeholder clip, such as a notice that
     * the file is not ready, and not the video. It applies in every mode: the user never chose a placeholder.
     */
    PlaceholderClip,
}

internal enum class SourceFailoverStop {
    /** The setting is off. */
    Disabled,

    /** Moving on does not apply to this failure, for example a source picked by hand that never started. */
    NotApplicable,

    /** The user pressed Back or picked a source themselves. */
    UserCancelled,

    /** The device is offline, so the failure says nothing about the source. */
    Offline,

    /** The fixed number of sources was already tried. */
    LimitReached,

    /** Every source in the list failed already, or none is left. */
    Exhausted,
}

internal sealed interface SourceFailoverDecision<out T> {
    data class TryNext<out T>(val candidate: T) : SourceFailoverDecision<T>

    data class Stop(val reason: SourceFailoverStop) : SourceFailoverDecision<Nothing>
}

/**
 * Decides what to do after a source failed. It looks at nothing but its arguments.
 *
 * [failedSourceChosenByApp] is true when the app picked the failed source and not the user.
 * [ranked] is the list in the order the user's mode produced it. [failedKeys] holds the identity of
 * every source that already failed in this playback session, [keyOf] gives that identity (a source
 * without one is never treated as failed, the [attempts] limit still bounds it).
 */
internal fun <T> decideSourceFailover(
    failure: SourceFailure,
    settingEnabled: Boolean,
    manualMode: Boolean,
    failedSourceChosenByApp: Boolean,
    userCancelled: Boolean,
    network: NetworkCondition,
    attempts: Int,
    maxAttempts: Int,
    ranked: List<T>,
    failedKeys: Set<String>,
    keyOf: (T) -> String?,
): SourceFailoverDecision<T> {
    if (userCancelled) return SourceFailoverDecision.Stop(SourceFailoverStop.UserCancelled)
    if (!settingEnabled) return SourceFailoverDecision.Stop(SourceFailoverStop.Disabled)
    if (failure == SourceFailure.FailedToStart) {
        // A source the app chose (auto-play, or an earlier move to the next source) may be replaced. A source
        // the user picked by hand may not, unless the constant above says so for Manual mode.
        val appliesToStart = failedSourceChosenByApp || (manualMode && SOURCE_FAILOVER_ON_START_IN_MANUAL_MODE)
        if (!appliesToStart) return SourceFailoverDecision.Stop(SourceFailoverStop.NotApplicable)
    }
    if (network == NetworkCondition.NoInternet || network == NetworkCondition.ServersUnreachable) {
        return SourceFailoverDecision.Stop(SourceFailoverStop.Offline)
    }
    val limit = maxAttempts.coerceIn(1, SOURCE_FALLBACK_MAX_SOURCES_RANGE.last)
    if (attempts >= limit) return SourceFailoverDecision.Stop(SourceFailoverStop.LimitReached)
    val next = ranked.firstOrNull { candidate ->
        val key = keyOf(candidate)
        key == null || key !in failedKeys
    } ?: return SourceFailoverDecision.Stop(SourceFailoverStop.Exhausted)
    return SourceFailoverDecision.TryNext(next)
}

/**
 * Checked right before a replacement is loaded, after its link was resolved, which can take a
 * while: the device may have gone offline or the user may have left in the meantime. Returns the
 * reason to stop, or null when loading the replacement is fine.
 */
internal fun sourceFailoverStopBeforeLoading(userCancelled: Boolean, network: NetworkCondition): SourceFailoverStop? =
    when {
        userCancelled -> SourceFailoverStop.UserCancelled
        network == NetworkCondition.NoInternet || network == NetworkCondition.ServersUnreachable -> SourceFailoverStop.Offline
        else -> null
    }

/**
 * Where the next source starts after one died at [positionMs]: a couple of seconds earlier, never
 * before the start, and when the length is known never past [SOURCE_FAILOVER_END_MARGIN_MS] before the end.
 */
internal fun sourceFailoverResumePositionMs(positionMs: Long, durationMs: Long): Long {
    val back = (positionMs - SOURCE_FAILOVER_RESUME_BACKOFF_MS).coerceAtLeast(0L)
    return if (durationMs > SOURCE_FAILOVER_END_MARGIN_MS) {
        back.coerceAtMost(durationMs - SOURCE_FAILOVER_END_MARGIN_MS)
    } else {
        back
    }
}

/** A file shorter than this can be a placeholder. */
internal const val PLACEHOLDER_MAX_DURATION_MS = 5L * 60_000L

/** A file shorter than this part of the expected running time can be a placeholder. */
internal const val PLACEHOLDER_MAX_FRACTION_OF_RUNTIME = 0.30

/**
 * How far playback may have advanced from the first frame for the placeholder check to still run.
 * The check is for the start of a source, not for one the user has watched for a while.
 */
internal const val PLACEHOLDER_CHECK_WINDOW_MS = 30_000L

/**
 * Whether the playing file is a placeholder rather than the video. Both the length of the file
 * and the expected running time (in minutes, from the metadata) must be known, and the file must
 * be both under [PLACEHOLDER_MAX_DURATION_MS] and under [PLACEHOLDER_MAX_FRACTION_OF_RUNTIME] of the
 * running time. With either unknown, nothing is claimed. A still picture on a file of full length
 * is never a placeholder by this rule.
 */
internal fun looksLikePlaceholder(durationMs: Long, expectedRuntimeMinutes: Int?): Boolean {
    if (durationMs <= 0L) return false
    val expectedMs = (expectedRuntimeMinutes ?: return false).takeIf { it > 0 }?.let { it * 60_000L } ?: return false
    return durationMs < PLACEHOLDER_MAX_DURATION_MS && durationMs < expectedMs * PLACEHOLDER_MAX_FRACTION_OF_RUNTIME
}

/**
 * Whether the placeholder check should look now: it has not run for this source yet, playback has
 * advanced no more than [PLACEHOLDER_CHECK_WINDOW_MS] since the first frame, and the device is not offline.
 */
internal fun placeholderCheckDue(alreadyChecked: Boolean, playedSinceFirstFrameMs: Long, network: NetworkCondition): Boolean =
    !alreadyChecked &&
        playedSinceFirstFrameMs <= PLACEHOLDER_CHECK_WINDOW_MS &&
        network != NetworkCondition.NoInternet &&
        network != NetworkCondition.ServersUnreachable

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

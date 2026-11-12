package com.nuvio.app.features.downloads

/**
 * Decides what happens after a transfer was cut off. A source that keeps failing while the internet
 * can be reached is given up after [MaxTransientRetries] attempts in a row that received nothing.
 * While the internet cannot be reached the download is not given up: it waits and tries again for
 * as long as that takes, and the source gets its attempts anew once the internet is back.
 */
internal class DownloadRetries(
    private val isOffline: suspend () -> Boolean,
    private val wait: suspend (delayMs: Long) -> Unit,
) {
    private var failedAttemptsWithoutProgress = 0
    private var waitedForInternet = false

    /** Waits before the next attempt. False when the download is to be given up instead. */
    suspend fun awaitNextAttempt(madeProgress: Boolean): Boolean {
        if (madeProgress) {
            failedAttemptsWithoutProgress = 0
            waitedForInternet = false
        }
        failedAttemptsWithoutProgress++
        if (failedAttemptsWithoutProgress > MaxTransientRetries) {
            when {
                isOffline() -> {
                    waitedForInternet = true
                    failedAttemptsWithoutProgress = MaxTransientRetries
                }
                waitedForInternet -> {
                    waitedForInternet = false
                    failedAttemptsWithoutProgress = 1
                }
                else -> return false
            }
        }
        wait(retryDelayMs(failedAttemptsWithoutProgress))
        return true
    }
}

internal const val MaxTransientRetries = 10
private const val MaxRetryDelayMs = 30_000L

/** 2s, 4s, 8s, 16s, then capped at 30s. */
internal fun retryDelayMs(failedAttempts: Int): Long =
    (1_000L shl failedAttempts.coerceIn(1, 5)).coerceAtMost(MaxRetryDelayMs)

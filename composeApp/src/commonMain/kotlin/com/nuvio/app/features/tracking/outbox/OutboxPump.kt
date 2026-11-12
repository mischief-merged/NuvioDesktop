package com.nuvio.app.features.tracking.outbox

import co.touchlab.kermit.Logger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [flush] once when started, again whenever [wake] is called, and when the next queued entry
 * becomes due. One coroutine does all of it, so flushes never overlap and none is cancelled half way.
 */
internal class OutboxPump(
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val flush: suspend () -> Unit,
    private val nextWakeAtEpochMs: () -> Long?,
) {
    private val log = Logger.withTag("TrackerOutbox")
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val started = atomic(false)

    fun start() {
        if (!started.compareAndSet(expect = false, update = true)) return
        scope.launch {
            while (true) {
                val wakeAt = try {
                    flush()
                    nextWakeAtEpochMs()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.w { "Tracker outbox flush failed: ${error.message}" }
                    nowEpochMs() + FailureRetryDelayMs
                }
                if (wakeAt == null) {
                    wakeups.receive()
                } else {
                    withTimeoutOrNull(maxOf(wakeAt - nowEpochMs(), MinWakeDelayMs)) { wakeups.receive() }
                }
            }
        }
    }

    fun wake() {
        wakeups.trySend(Unit)
    }

    companion object {
        const val MinWakeDelayMs = 5_000L
        const val FailureRetryDelayMs = 60_000L
    }
}

package com.nuvio.app.features.tracking.outbox

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The profile and tracker account whose queue is shown. [accountKey] is null while no account is connected. */
internal data class QueueIdentity(val profileId: Int, val accountKey: String?)

/** What one "Sync now" achieved, in numbers the screen turns into a sentence. */
internal data class QueueSyncResult(
    val sent: Int,
    val stillWaiting: Int,
    /** Watches the tracker refused during this attempt. */
    val newlyRefused: Int,
    val offline: Boolean,
    /** How long until the next watch is due again, when that is known. */
    val retryInMs: Long?,
)

internal fun queueSyncResult(
    before: OutboxCounts,
    after: OutboxCounts,
    offline: Boolean,
    retryInMs: Long?,
): QueueSyncResult {
    val refused = (after.undeliverable - before.undeliverable).coerceAtLeast(0)
    return QueueSyncResult(
        sent = (before.waiting - after.waiting - refused).coerceAtLeast(0),
        stillWaiting = after.waiting,
        newlyRefused = refused,
        offline = offline,
        retryInMs = retryInMs?.takeIf { it > 0 },
    )
}

/** Nothing is reported while no account is connected: its watches are kept but are not Trakt's to show. */
private fun OutboxSnapshot.countsFor(who: QueueIdentity): OutboxCounts =
    if (who.accountKey == null) OutboxCounts() else countsFor(who.profileId, who.accountKey)

internal sealed interface QueueSyncState {
    data object Idle : QueueSyncState
    data object Syncing : QueueSyncState
    data class Done(val result: QueueSyncResult) : QueueSyncState
}

/** What the tracking settings read from and ask of the queue. */
internal interface TrackerQueueController {
    val counts: StateFlow<OutboxCounts>
    val syncState: StateFlow<QueueSyncState>

    /** Sends what can be sent now. Does nothing while a sync is already under way. */
    fun syncNow()

    /** Puts the refused watches back in the queue and sends them. */
    fun retryUndeliverable()

    fun dismissUndeliverable()
    fun dismissDropped()

    /** Forgets the last result, for example when the screen showing it is left. */
    fun clearResult()
}

/** The outbox behind [TrackerQueueController]. It never talks to the network itself: [flush] does. */
internal class OutboxQueueController(
    private val outbox: TrackerOutbox,
    private val scope: CoroutineScope,
    private val identity: StateFlow<QueueIdentity>,
    private val isOnline: () -> Boolean,
    private val nowEpochMs: () -> Long,
    private val flush: suspend (profileId: Int) -> Unit = outbox::flush,
) : TrackerQueueController {
    override val counts: StateFlow<OutboxCounts> =
        combine(outbox.snapshot, identity) { snapshot, who -> snapshot.countsFor(who) }
            .stateIn(scope, SharingStarted.Eagerly, outbox.snapshot.value.countsFor(identity.value))

    private val state = MutableStateFlow<QueueSyncState>(QueueSyncState.Idle)
    override val syncState: StateFlow<QueueSyncState> = state.asStateFlow()
    private val busy = atomic(false)

    override fun syncNow() {
        if (!busy.compareAndSet(expect = false, update = true)) return
        state.value = QueueSyncState.Syncing
        val who = identity.value
        scope.launch {
            try {
                state.value = QueueSyncState.Done(runSync(who))
            } catch (error: CancellationException) {
                state.value = QueueSyncState.Idle
                throw error
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun runSync(who: QueueIdentity): QueueSyncResult {
        val before = outbox.snapshot.value.countsFor(who)
        try {
            flush(who.profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // The queue keeps what it could not send. The next automatic attempt tries again.
        }
        val after = outbox.snapshot.value.countsFor(who)
        val nextDueAt = outbox.nextWakeAtEpochMs(who.profileId)
        return queueSyncResult(before, after, offline = !isOnline(), retryInMs = nextDueAt?.let { it - nowEpochMs() })
    }

    override fun retryUndeliverable() {
        val who = identity.value
        val account = who.accountKey ?: return
        if (safely(0) { outbox.retryUndeliverable(who.profileId, account) } > 0) syncNow()
    }

    override fun dismissUndeliverable() {
        val who = identity.value
        val account = who.accountKey ?: return
        safely(0) { outbox.dismissUndeliverable(who.profileId, account) }
    }

    override fun dismissDropped() {
        val profileId = identity.value.profileId
        safely(Unit) { outbox.dismissDropped(profileId) }
    }

    /** A queue that cannot be written leaves the screen as it was instead of crashing it. */
    private inline fun <T> safely(default: T, block: () -> T): T =
        try {
            block()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            default
        }

    override fun clearResult() {
        if (state.value is QueueSyncState.Done) state.value = QueueSyncState.Idle
    }
}

/** Tells the user once per session that a watch was kept for later. */
internal class QueuedWatchNotice(private val show: () -> Unit) {
    private val shown = atomic(false)

    fun onQueued() {
        if (shown.compareAndSet(expect = false, update = true)) show()
    }
}

package com.nuvio.app.features.tracking.outbox

import co.touchlab.kermit.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class OutboxPayload(val version: Int, val entries: List<OutboxEntry> = emptyList())

/**
 * A persisted queue of finished watches that could not be delivered to a tracker, each of which is
 * delivered once.
 *
 * - An entry is marked in flight on disk before it is sent and removed only after the tracker accepted it.
 * - An entry whose earlier request may have been recorded (lost answer, server error, the app stopping
 *   mid-request) is sent again only after the tracker's history shows that the watch is missing. The
 *   history is read no sooner than [SettleDelayMs] after such a request, and while it cannot be read
 *   the entry waits.
 * - A watch that is already queued, for the same title less than [SameWatchWindowMs] away, is not queued again.
 * - An entry is only sent while the account it was queued under is connected for its profile. Until then
 *   it is kept, not sent and not dropped.
 * - Every entry is removed by the first flush or enqueue [MaxAgeMs] after it was queued, whatever its
 *   state, and the queue holds at most [MaxEntries] entries.
 *
 * Nothing here talks to the network: that is the [TrackerOutboxAdapter]'s job.
 */
internal class TrackerOutbox(
    private val store: OutboxPayloadStore,
    private val adapter: TrackerOutboxAdapter,
    private val nowEpochMs: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    private val random: () -> Double = { Random.nextDouble() },
) {
    private val log = Logger.withTag("TrackerOutbox")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Held for one send and its bookkeeping, so a cancellation never races a request that is under way. */
    private val sendMutex = Mutex()
    private val stateLock = SynchronizedObject()
    private var entries: List<OutboxEntry> = emptyList()
    private var loaded = false
    private var lastSendAtEpochMs: Long? = null

    fun entries(): List<OutboxEntry> = locked { entries }

    /** Returns true when the queue holds the watch afterwards, whether it was added now or was already there. */
    fun enqueue(watch: OutboxWatch): Boolean = locked {
        if (!watch.media.isValid || watch.accountKey.isBlank() || watch.watchedAtEpochMs <= 0L) return@locked false
        val now = nowEpochMs()
        var current = withoutExpired(now)

        val existing = current.firstOrNull { it.isSameWatch(watch) }
        if (existing != null) {
            if (watch.maybeDelivered && !existing.verifyBeforeSend && existing.state != OutboxState.DEAD) {
                current = current.map {
                    if (it.id != existing.id) return@map it
                    it.copy(verifyBeforeSend = true, nextAttemptAtEpochMs = maxOf(it.nextAttemptAtEpochMs, now + SettleDelayMs))
                }
            }
            replaceLocked(current)
            return@locked true
        }

        if (current.size >= MaxEntries) {
            current.firstOrNull { it.state == OutboxState.DEAD }?.let { dead -> current = current - dead }
        }
        if (current.size >= MaxEntries) {
            replaceLocked(current)
            log.w { "Tracker outbox is full ($MaxEntries entries): a watch was not queued" }
            return@locked false
        }

        val backoffEndsAt = current
            .filter { it.isSendable(watch.profileId, watch.accountKey) && it.attempts > 0 }
            .minOfOrNull { it.nextAttemptAtEpochMs } ?: 0L
        replaceLocked(
            current + OutboxEntry(
                id = newEntryId(current, now),
                profileId = watch.profileId,
                accountKey = watch.accountKey,
                media = watch.media,
                watchedAtEpochMs = watch.watchedAtEpochMs,
                createdAtEpochMs = now,
                nextAttemptAtEpochMs = maxOf(
                    watch.notBeforeEpochMs.coerceAtMost(now + MaxRetryAfterMs),
                    if (watch.maybeDelivered) now + SettleDelayMs else 0L,
                    backoffEndsAt,
                ),
                verifyBeforeSend = watch.maybeDelivered,
            ),
        )
        true
    }

    /**
     * Removes every queued watch of [media] for [profileId], for example because the title was un-watched.
     * Waits for a send that is under way, so the caller's own removal request reaches the tracker after it.
     */
    suspend fun cancel(profileId: Int, media: Collection<OutboxMedia>): Int = sendMutex.withLock {
        val before = locked { entries.size }
        update { all -> all.filterNot { entry -> entry.profileId == profileId && media.any(entry.media::isSameItem) } }
        before - locked { entries.size }
    }

    fun hasEntries(profileId: Int): Boolean = locked { entries.any { it.profileId == profileId } }

    /** Sends what is due for [profileId], oldest first. Safe to call from several places at once. */
    suspend fun flush(profileId: Int) {
        locked { replaceLocked(withoutExpired(nowEpochMs())) }
        while (sendMutex.withLock { sendNext(profileId) }) Unit
    }

    /** When the next entry that could be sent for [profileId] becomes due, or null when none is waiting. */
    fun nextWakeAtEpochMs(profileId: Int): Long? {
        val account = adapter.connectedAccountKey(profileId) ?: return null
        return locked {
            entries.filter { it.isSendable(profileId, account) }.minOfOrNull { it.nextAttemptAtEpochMs }
        }
    }

    /** Sends one batch. Returns false when nothing more can be sent for now. */
    private suspend fun sendNext(profileId: Int): Boolean {
        val account = adapter.connectedAccountKey(profileId) ?: return false
        val now = nowEpochMs()
        val due = locked {
            entries.filter { it.isSendable(profileId, account) && it.nextAttemptAtEpochMs <= now }
        }
        val head = due.firstOrNull() ?: return false

        val candidates = if (head.verifyBeforeSend) {
            when (isRecorded(head)) {
                true -> {
                    log.i { "Dropping ${head.id}: the tracker already holds this watch" }
                    update { it.filterNot { entry -> entry.id == head.id } }
                    return true
                }
                null -> {
                    postpone(profileId, account, setOf(head.id), retryAfterMs = null, maybeDelivered = false)
                    return false
                }
                false -> listOf(head)
            }
        } else {
            due.filterNot { it.verifyBeforeSend }.take(MaxBatch)
        }

        lastSendAtEpochMs?.let { last ->
            val wait = MinSendIntervalMs - (nowEpochMs() - last)
            if (wait > 0) sleep(wait)
        }

        val candidateIds = candidates.map { it.id }.toSet()
        val batch = locked {
            val ready = entries.filter {
                it.id in candidateIds && it.state == OutboxState.PENDING && it.verifyBeforeSend == head.verifyBeforeSend
            }
            val readyIds = ready.map { it.id }.toSet()
            replaceLocked(entries.map { if (it.id in readyIds) it.copy(state = OutboxState.IN_FLIGHT) else it })
            ready
        }
        if (batch.isEmpty()) return true
        val ids = batch.map { it.id }.toSet()

        lastSendAtEpochMs = nowEpochMs()
        val outcome = try {
            adapter.send(batch)
        } catch (error: CancellationException) {
            val settledAt = nowEpochMs() + SettleDelayMs
            update { all -> all.map { if (it.id in ids) it.afterDoubtfulRequest(settledAt) else it } }
            throw error
        } catch (error: Throwable) {
            OutboxSendOutcome.Retry(retryAfterMs = null, maybeDelivered = true, reason = error.message ?: "send failed")
        }
        lastSendAtEpochMs = nowEpochMs()

        return when (outcome) {
            OutboxSendOutcome.Delivered -> {
                update { all -> all.filterNot { it.id in ids } }
                true
            }
            is OutboxSendOutcome.Rejected -> {
                if (batch.size == 1) {
                    log.w { "Tracker rejected ${ids.first()}: ${outcome.reason}" }
                    update { all -> all.map { if (it.id in ids) it.copy(state = OutboxState.DEAD) else it } }
                } else {
                    update { all -> all.map { if (it.id in ids) it.copy(state = OutboxState.PENDING, verifyBeforeSend = true) else it } }
                }
                true
            }
            is OutboxSendOutcome.Retry -> {
                log.i { "Tracker outbox will retry ${ids.size} watch(es): ${outcome.reason}" }
                postpone(profileId, account, ids, outcome.retryAfterMs, outcome.maybeDelivered)
                false
            }
        }
    }

    private suspend fun isRecorded(entry: OutboxEntry): Boolean? =
        try {
            adapter.isRecorded(entry)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        }

    /**
     * Backs off the entries in [ids] and holds everything else that could be sent for the same account
     * until the first of them is due again, so one failure is not followed by a request per waiting entry.
     */
    private fun postpone(profileId: Int, account: String, ids: Set<String>, retryAfterMs: Long?, maybeDelivered: Boolean) {
        val now = nowEpochMs()
        update { all ->
            val backedOff = all.map { entry ->
                if (entry.id !in ids) return@map entry
                val attempts = entry.attempts + 1
                val delayMs = maxOf(
                    trackerOutboxBackoffMs(attempts, jitter = random() * 2 - 1),
                    (retryAfterMs ?: 0L).coerceIn(0L, MaxRetryAfterMs),
                    if (maybeDelivered) SettleDelayMs else 0L,
                )
                entry.copy(
                    state = OutboxState.PENDING,
                    attempts = attempts,
                    nextAttemptAtEpochMs = now + delayMs,
                    verifyBeforeSend = entry.verifyBeforeSend || maybeDelivered,
                )
            }
            val resumeAt = backedOff.filter { it.id in ids }.minOfOrNull { it.nextAttemptAtEpochMs } ?: return@update backedOff
            backedOff.map { entry ->
                if (entry.id !in ids && entry.isSendable(profileId, account) && entry.nextAttemptAtEpochMs < resumeAt) {
                    entry.copy(nextAttemptAtEpochMs = resumeAt)
                } else {
                    entry
                }
            }
        }
    }

    private fun OutboxEntry.afterDoubtfulRequest(settledAtEpochMs: Long): OutboxEntry = copy(
        state = OutboxState.PENDING,
        verifyBeforeSend = true,
        nextAttemptAtEpochMs = maxOf(nextAttemptAtEpochMs, settledAtEpochMs),
    )

    private fun OutboxEntry.isSendable(profileId: Int, account: String): Boolean =
        state == OutboxState.PENDING && this.profileId == profileId && accountKey == account

    private fun withoutExpired(now: Long): List<OutboxEntry> {
        val (expired, kept) = entries.partition { now - it.createdAtEpochMs > MaxAgeMs }
        val undelivered = expired.count { it.state != OutboxState.DEAD }
        if (undelivered > 0) log.w { "Dropping $undelivered queued watch(es) that could not be delivered within the retention period" }
        return kept
    }

    private fun newEntryId(current: List<OutboxEntry>, now: Long): String {
        var sequence = 0
        while (current.any { it.id == "$now-$sequence" }) sequence++
        return "$now-$sequence"
    }

    /**
     * Bookkeeping after a request or a cancellation. It is adopted in memory even when it cannot be
     * written: the disk then still says in flight, which a restart treats as a doubtful request.
     */
    private inline fun update(transform: (List<OutboxEntry>) -> List<OutboxEntry>) {
        locked {
            val updated = transform(entries)
            try {
                replaceLocked(updated)
            } finally {
                entries = updated
            }
        }
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(stateLock) {
        loadLocked()
        block()
    }

    /** Written before it is adopted, so nothing is reported as queued or sent as in flight unless the disk says so. */
    private fun replaceLocked(updated: List<OutboxEntry>) {
        if (updated == entries) return
        store.save(json.encodeToString(OutboxPayload(version = PayloadVersion, entries = updated)))
        entries = updated
    }

    /**
     * A payload that cannot be read is kept apart before anything is written, so it is never replaced by
     * an empty queue. An entry that was in flight when the app stopped may have been delivered.
     */
    private fun loadLocked() {
        if (loaded) return
        val raw = store.load()?.takeIf { it.isNotBlank() }
        val payload = raw?.let { runCatching { json.decodeFromString<OutboxPayload>(it) }.getOrNull() }
            ?.takeIf { it.version == PayloadVersion }
        if (raw != null && payload == null) {
            store.saveUnreadable(raw, nowEpochMs())
            store.save(json.encodeToString(OutboxPayload(version = PayloadVersion)))
            log.w { "Stored tracker outbox could not be read and was kept aside" }
        }
        val settledAt = nowEpochMs() + SettleDelayMs
        entries = payload?.entries.orEmpty().map { entry ->
            when {
                !entry.media.isValid || entry.accountKey.isBlank() -> entry.copy(state = OutboxState.DEAD)
                entry.state == OutboxState.IN_FLIGHT -> entry.afterDoubtfulRequest(settledAt)
                else -> entry
            }
        }
        loaded = true
    }

    companion object {
        const val PayloadVersion = 1
        const val MinSendIntervalMs = 1_200L
        const val MaxBatch = 100
        const val MaxEntries = 500
        const val MaxAgeMs = 30L * 24 * 3_600_000L
        const val MaxRetryAfterMs = 3_600_000L

        /** How long the tracker is given to finish a request that may still be under way before its history is read. */
        const val SettleDelayMs = 30_000L
    }
}

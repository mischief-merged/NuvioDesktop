package com.nuvio.app.features.tracking.outbox

import kotlinx.serialization.Serializable
import kotlin.math.abs

internal enum class OutboxMediaKind { MOVIE, EPISODE }

internal enum class OutboxState { PENDING, IN_FLIGHT, DEAD }

/** The resolved identity of a watched title. For an episode the ids are those of its show. */
@Serializable
internal data class OutboxMedia(
    val kind: OutboxMediaKind,
    val imdb: String? = null,
    val tmdb: Int? = null,
    val trakt: Int? = null,
    val season: Int? = null,
    val number: Int? = null,
) {
    /** Every id that is present is well formed, at least one is present, and only an episode has a position. */
    val isValid: Boolean
        get() {
            val idsValid = (imdb == null || ImdbIdPattern.matches(imdb)) &&
                (tmdb == null || tmdb > 0) &&
                (trakt == null || trakt > 0) &&
                (imdb != null || tmdb != null || trakt != null)
            val positionValid = when (kind) {
                OutboxMediaKind.MOVIE -> season == null && number == null
                OutboxMediaKind.EPISODE -> season != null && season >= 0 && number != null && number >= 0
            }
            return idsValid && positionValid
        }

    /** Two references name the same title when they share any id, whatever other ids each one carries. */
    fun isSameItem(other: OutboxMedia): Boolean =
        kind == other.kind && season == other.season && number == other.number && sharesAnyId(other.imdb, other.tmdb, other.trakt)

    fun sharesAnyId(imdb: String?, tmdb: Int?, trakt: Int?): Boolean =
        (this.imdb != null && this.imdb == imdb) ||
            (this.tmdb != null && this.tmdb == tmdb) ||
            (this.trakt != null && this.trakt == trakt)

    companion object {
        private val ImdbIdPattern = Regex("^tt\\d{1,12}$")

        /** Malformed ids are discarded. Returns null when no usable id is left. */
        fun movie(imdb: String?, tmdb: Int?, trakt: Int?): OutboxMedia? =
            create(OutboxMediaKind.MOVIE, imdb, tmdb, trakt, season = null, number = null)

        fun episode(imdb: String?, tmdb: Int?, trakt: Int?, season: Int, number: Int): OutboxMedia? =
            create(OutboxMediaKind.EPISODE, imdb, tmdb, trakt, season, number)

        private fun create(kind: OutboxMediaKind, imdb: String?, tmdb: Int?, trakt: Int?, season: Int?, number: Int?): OutboxMedia? =
            OutboxMedia(
                kind = kind,
                imdb = imdb?.trim()?.takeIf(ImdbIdPattern::matches),
                tmdb = tmdb?.takeIf { it > 0 },
                trakt = trakt?.takeIf { it > 0 },
                season = season,
                number = number,
            ).takeIf { it.isValid }
    }
}

/** A finished watch that could not be delivered. */
internal data class OutboxWatch(
    val profileId: Int,
    /** The tracker account that was connected when the watch happened. */
    val accountKey: String,
    val media: OutboxMedia,
    val watchedAtEpochMs: Long,
    /** The failed request may have been recorded by the tracker all the same. */
    val maybeDelivered: Boolean = false,
    val notBeforeEpochMs: Long = 0L,
)

/** One queued watch. Holds no credentials. */
@Serializable
internal data class OutboxEntry(
    val id: String,
    val profileId: Int,
    val accountKey: String,
    val media: OutboxMedia,
    val watchedAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val attempts: Int = 0,
    val nextAttemptAtEpochMs: Long = 0L,
    val state: OutboxState = OutboxState.PENDING,
    /** The tracker may already hold this watch, so its history is read before the entry is sent. */
    val verifyBeforeSend: Boolean = false,
) {
    /** A refused watch can be sent again only when it names a usable title and an account. */
    val canRetry: Boolean
        get() = state == OutboxState.DEAD && media.isValid && accountKey.isNotBlank()

    fun isSameWatch(watch: OutboxWatch): Boolean =
        profileId == watch.profileId &&
            accountKey == watch.accountKey &&
            media.isSameItem(watch.media) &&
            abs(watchedAtEpochMs - watch.watchedAtEpochMs) < SameWatchWindowMs
}

/**
 * Two completions of one title less than this far apart are one watch: the same playback reported
 * twice, or a queued watch the tracker already holds. From this distance on they are separate plays.
 */
internal const val SameWatchWindowMs = 5 * 60_000L

/** What the user can be told about the queue of one profile and, when given, one account. */
internal data class OutboxCounts(
    /** Watches queued or being sent, which will be delivered without further action. */
    val waiting: Int = 0,
    /** Watches the tracker refused. They are never sent again unless the user retries. */
    val undeliverable: Int = 0,
    /** The part of [undeliverable] that can be retried. */
    val retryable: Int = 0,
    /** Watches removed after [TrackerOutbox.MaxAgeMs] without reaching the tracker, until dismissed. */
    val droppedForAge: Int = 0,
) {
    val isEmpty: Boolean get() = waiting == 0 && undeliverable == 0 && droppedForAge == 0
}

/** The whole queue at one moment, as observable state. */
internal data class OutboxSnapshot(
    val entries: List<OutboxEntry> = emptyList(),
    val droppedForAge: Map<Int, Int> = emptyMap(),
) {
    /** [accountKey] null counts the entries of every account of the profile. */
    fun countsFor(profileId: Int, accountKey: String?): OutboxCounts {
        val own = entries.filter { it.profileId == profileId && (accountKey == null || it.accountKey == accountKey) }
        val dead = own.filter { it.state == OutboxState.DEAD }
        return OutboxCounts(
            waiting = own.size - dead.size,
            undeliverable = dead.size,
            retryable = dead.count { it.canRetry },
            droppedForAge = droppedForAge[profileId] ?: 0,
        )
    }
}

internal sealed interface OutboxSendOutcome {
    data object Delivered : OutboxSendOutcome

    /** Nothing is consumed. [maybeDelivered] is set when the tracker may have recorded the batch anyway. */
    data class Retry(
        val retryAfterMs: Long?,
        val maybeDelivered: Boolean,
        val reason: String,
    ) : OutboxSendOutcome

    /** The tracker recorded nothing and will never accept this batch as it is. */
    data class Rejected(val reason: String) : OutboxSendOutcome
}

internal interface TrackerOutboxAdapter {
    /** The account [profileId] can deliver to right now, or null when nothing can be sent for that profile. */
    fun connectedAccountKey(profileId: Int): String?

    /** Sends [batch], whose entries all belong to one profile and one account. */
    suspend fun send(batch: List<OutboxEntry>): OutboxSendOutcome

    /** True when the tracker already holds this watch, false when it does not, null when that cannot be told now. */
    suspend fun isRecorded(entry: OutboxEntry): Boolean?
}

internal interface OutboxPayloadStore {
    fun load(): String?
    fun save(payload: String)

    /** Keeps a payload that could not be read, apart from the live one. */
    fun saveUnreadable(payload: String, savedAtEpochMs: Long)
}

/** 30 s, 1, 2, 5, 15, 30 min, then hourly. [jitter] in -1.0..1.0 moves the delay by up to 20 percent. */
internal fun trackerOutboxBackoffMs(attempts: Int, jitter: Double): Long {
    val base = BackoffStepsMs[(attempts - 1).coerceIn(0, BackoffStepsMs.lastIndex)]
    return (base * (1.0 + 0.2 * jitter.coerceIn(-1.0, 1.0))).toLong()
}

private val BackoffStepsMs = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L, 900_000L, 1_800_000L, 3_600_000L)

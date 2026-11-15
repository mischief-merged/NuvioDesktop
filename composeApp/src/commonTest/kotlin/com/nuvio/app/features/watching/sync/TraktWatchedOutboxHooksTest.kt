package com.nuvio.app.features.watching.sync

import com.nuvio.app.features.tracking.outbox.OutboxMedia
import com.nuvio.app.features.tracking.outbox.OutboxMediaKind
import com.nuvio.app.features.trakt.TraktUndelivered
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktWatchedOutboxHooksTest {
    private data class Queued(val profileId: Int, val media: OutboxMedia, val watchedAtEpochMs: Long, val undelivered: TraktUndelivered)

    private val markedAtEpochMs = 1_794_484_800_000L
    private val movie = WatchedItem(id = "tt0000001", type = "movie", name = "Movie", markedAtEpochMs = markedAtEpochMs)
    private val episode = WatchedItem(
        id = "tt0000009", type = "series", name = "Show", season = 2, episode = 5, markedAtEpochMs = markedAtEpochMs + 1_000L,
    )
    private val wholeShow = WatchedItem(id = "tt0000009", type = "series", name = "Show", markedAtEpochMs = markedAtEpochMs)

    @Test
    fun marks_that_did_not_reach_trakt_are_queued_with_the_time_they_were_made_and_the_delivery_doubt() = runTest {
        val queued = mutableListOf<Queued>()
        val lostAnswer = TraktUndelivered(maybeDelivered = true, retryAfterMs = 9_000L)

        val held = TraktWatchedSyncAdapter.queueHistoryAddsForLater(
            profileId = 3,
            items = listOf(movie, episode, wholeShow),
            undelivered = lostAnswer,
            isActive = true,
            enqueue = { profileId, media, watchedAt, undelivered -> queued.add(Queued(profileId, media, watchedAt, undelivered)) },
        )

        assertTrue(held)
        assertEquals(
            listOf(
                Queued(3, OutboxMedia(kind = OutboxMediaKind.MOVIE, imdb = "tt0000001"), markedAtEpochMs, lostAnswer),
                Queued(
                    3,
                    OutboxMedia(kind = OutboxMediaKind.EPISODE, imdb = "tt0000009", season = 2, number = 5),
                    markedAtEpochMs + 1_000L,
                    lostAnswer,
                ),
            ),
            queued,
        )
    }

    @Test
    fun a_mark_without_a_usable_time_is_queued_with_the_current_time() = runTest {
        val queued = mutableListOf<Long>()
        val before = com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs()

        TraktWatchedSyncAdapter.queueHistoryAddsForLater(
            profileId = 1,
            items = listOf(movie.copy(markedAtEpochMs = 0L), movie.copy(id = "tt0000002", markedAtEpochMs = 42L)),
            undelivered = TraktUndelivered(maybeDelivered = false),
            isActive = true,
            enqueue = { _, _, watchedAt, _ -> queued.add(watchedAt) },
        )

        assertEquals(2, queued.size)
        assertTrue(queued.all { it >= before })
    }

    @Test
    fun the_push_is_handed_to_the_queue_once_any_mark_is_held_and_not_when_none_is() = runTest {
        var asked = 0
        val refusedAll = TraktWatchedSyncAdapter.queueHistoryAddsForLater(
            profileId = 1,
            items = listOf(movie, episode),
            undelivered = TraktUndelivered(maybeDelivered = false),
            isActive = true,
            enqueue = { _, _, _, _ -> false },
        )
        val refusedOne = TraktWatchedSyncAdapter.queueHistoryAddsForLater(
            profileId = 1,
            items = listOf(movie, episode),
            undelivered = TraktUndelivered(maybeDelivered = false),
            isActive = true,
            enqueue = { _, media, _, _ -> media.kind == OutboxMediaKind.MOVIE },
        )
        val inactive = TraktWatchedSyncAdapter.queueHistoryAddsForLater(
            profileId = 1,
            items = listOf(movie),
            undelivered = TraktUndelivered(maybeDelivered = false),
            isActive = false,
            enqueue = { _, _, _, _ ->
                asked++
                true
            },
        )
        val nothingToQueue = TraktWatchedSyncAdapter.queueHistoryAddsForLater(
            profileId = 1,
            items = listOf(wholeShow),
            undelivered = TraktUndelivered(maybeDelivered = false),
            isActive = true,
            enqueue = { _, _, _, _ -> true },
        )

        assertFalse(refusedAll)
        assertTrue(refusedOne, "the remap retry would send a queued episode a second time")
        assertFalse(inactive)
        assertEquals(0, asked)
        assertFalse(nothingToQueue)
    }

    @Test
    fun an_un_watch_cancels_the_queued_watch_in_the_addon_numbering_and_in_the_trakt_numbering() = runTest {
        val cancelled = mutableListOf<Pair<Int, List<OutboxMedia>>>()

        TraktWatchedSyncAdapter.cancelQueuedAdds(
            profileId = 3,
            items = listOf(movie, episode, wholeShow),
            hasQueued = { true },
            mapEpisode = { item -> if (item.season == 2) 1 to 17 else null },
            cancel = { profileId, media -> cancelled += profileId to media.toList() },
        )

        assertEquals(
            listOf(
                3 to listOf(
                    OutboxMedia(kind = OutboxMediaKind.MOVIE, imdb = "tt0000001"),
                    OutboxMedia(kind = OutboxMediaKind.EPISODE, imdb = "tt0000009", season = 2, number = 5),
                    OutboxMedia(kind = OutboxMediaKind.EPISODE, imdb = "tt0000009", season = 1, number = 17),
                ),
            ),
            cancelled,
        )
    }

    @Test
    fun an_un_watch_does_no_extra_work_when_nothing_is_queued_for_the_profile() = runTest {
        var mapped = 0
        var cancelled = 0

        TraktWatchedSyncAdapter.cancelQueuedAdds(
            profileId = 3,
            items = listOf(episode),
            hasQueued = { false },
            mapEpisode = { mapped++; null },
            cancel = { _, _ -> cancelled++ },
        )

        assertEquals(0, mapped)
        assertEquals(0, cancelled)
    }
}

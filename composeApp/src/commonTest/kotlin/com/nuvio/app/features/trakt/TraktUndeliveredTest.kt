package com.nuvio.app.features.trakt

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.tracking.outbox.OutboxMedia
import com.nuvio.app.features.tracking.outbox.OutboxMediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraktUndeliveredTest {
    private data class Queued(val profileId: Int, val media: OutboxMedia, val watchedAtEpochMs: Long, val undelivered: TraktUndelivered)

    private val movie = TraktScrobbleItem.Movie(title = "Movie", year = 2020, ids = TraktExternalIds(trakt = 7, imdb = "tt0000001", tmdb = 55))
    private val episode = TraktScrobbleItem.Episode(
        showTitle = "Show", showYear = 2021, showIds = TraktExternalIds(imdb = "tt0000009"), season = 2, number = 5, episodeTitle = null,
    )
    private val notDelivered = TraktUndelivered(maybeDelivered = false)

    private fun queue(
        action: String,
        progressPercent: Float,
        item: TraktScrobbleItem = movie,
        undelivered: TraktUndelivered = notDelivered,
        queued: MutableList<Queued> = mutableListOf(),
    ): Boolean = TraktScrobbleRepository.queueCompletionForLater(
        profileId = 4,
        action = action,
        item = item,
        progressPercent = progressPercent,
        watchedAtEpochMs = 1_794_484_800_000L,
        undelivered = undelivered,
        enqueue = { profileId, media, watchedAt, reason -> queued.add(Queued(profileId, media, watchedAt, reason)) },
    )

    @Test
    fun a_failed_write_is_kept_only_when_a_later_attempt_could_succeed() {
        assertEquals(TraktUndelivered(maybeDelivered = true), traktUndeliveredOrNull(response = null))
        assertEquals(TraktUndelivered(maybeDelivered = false), traktUndeliveredOrNull(response(429)))
        for (status in listOf(408, 500, 502, 503, 504, 599)) {
            assertEquals(TraktUndelivered(maybeDelivered = true), traktUndeliveredOrNull(response(status)), "HTTP $status")
        }
        for (status in listOf(200, 201, 400, 401, 403, 404, 409, 422)) {
            assertNull(traktUndeliveredOrNull(response(status)), "HTTP $status")
        }
    }

    @Test
    fun retry_after_is_read_in_seconds_whatever_the_header_case_and_ignored_when_it_is_not_a_delay() {
        assertEquals(120_000L, traktUndeliveredOrNull(response(429, mapOf("Retry-After" to "120")))?.retryAfterMs)
        assertEquals(3_000L, traktUndeliveredOrNull(response(503, mapOf("retry-after" to " 3 ")))?.retryAfterMs)
        assertNull(traktUndeliveredOrNull(response(429, mapOf("retry-after" to "-5")))?.retryAfterMs)
        assertNull(traktUndeliveredOrNull(response(429, mapOf("retry-after" to "Thu, 12 Nov 2026 08:00:00 GMT")))?.retryAfterMs)
        assertNull(traktUndeliveredOrNull(response(429, mapOf("retry-after" to "99999999999999999")))?.retryAfterMs)
    }

    @Test
    fun a_stop_at_the_watched_threshold_is_queued_and_one_just_below_it_is_not() {
        assertTrue(queue(action = "stop", progressPercent = 80f))
        assertTrue(queue(action = "stop", progressPercent = 100f))
        assertFalse(queue(action = "stop", progressPercent = 79.99f))
    }

    @Test
    fun a_start_or_a_pause_is_never_queued() {
        val queued = mutableListOf<Queued>()

        assertFalse(queue(action = "start", progressPercent = 100f, queued = queued))
        assertFalse(queue(action = "pause", progressPercent = 95f, queued = queued))
        assertTrue(queued.isEmpty())
    }

    @Test
    fun the_queued_watch_carries_the_profile_the_ids_the_watch_time_and_the_delivery_doubt() {
        val queued = mutableListOf<Queued>()
        val lostAnswer = TraktUndelivered(maybeDelivered = true, retryAfterMs = 5_000L)

        queue(action = "stop", progressPercent = 92f, item = movie, undelivered = lostAnswer, queued = queued)
        queue(action = "stop", progressPercent = 92f, item = episode, queued = queued)

        assertEquals(
            listOf(
                Queued(4, OutboxMedia(kind = OutboxMediaKind.MOVIE, imdb = "tt0000001", tmdb = 55, trakt = 7), 1_794_484_800_000L, lostAnswer),
                Queued(
                    4,
                    OutboxMedia(kind = OutboxMediaKind.EPISODE, imdb = "tt0000009", season = 2, number = 5),
                    1_794_484_800_000L,
                    notDelivered,
                ),
            ),
            queued,
        )
    }

    @Test
    fun a_watch_without_a_well_formed_id_is_not_queued() {
        val queued = mutableListOf<Queued>()
        val malformed = TraktScrobbleItem.Movie(title = "Movie", year = null, ids = TraktExternalIds(imdb = "tt1/../../x"))

        assertFalse(queue(action = "stop", progressPercent = 100f, item = malformed, queued = queued))
        assertTrue(queued.isEmpty())
    }

    @Test
    fun the_queue_decides_whether_the_watch_is_held() {
        val refused = TraktScrobbleRepository.queueCompletionForLater(
            profileId = 4,
            action = "stop",
            item = movie,
            progressPercent = 100f,
            watchedAtEpochMs = 1_794_484_800_000L,
            undelivered = notDelivered,
            enqueue = { _, _, _, _ -> false },
        )

        assertFalse(refused)
    }

    private fun response(status: Int, headers: Map<String, String> = emptyMap()) =
        RawHttpResponse(status = status, statusText = "", url = "", body = "", headers = headers)
}

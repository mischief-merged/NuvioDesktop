package com.nuvio.app.features.watching.sync

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.tracking.outbox.OutboxEntry
import com.nuvio.app.features.tracking.outbox.OutboxMedia
import com.nuvio.app.features.tracking.outbox.OutboxMediaKind
import com.nuvio.app.features.tracking.outbox.OutboxSendOutcome
import com.nuvio.app.features.tracking.outbox.SameWatchWindowMs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WatchedAtEpochMs = 1_794_484_800_000L
private const val WatchedAtIso = "2026-11-12T12:00:00.000Z"

class TraktOutboxAdapterTest {
    private class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String)

    private class Fixture(
        var account: String? = "account-a",
        var activeProfileId: Int = 1,
        var online: Boolean = true,
        var headers: Map<String, String>? = mapOf("Authorization" to "Bearer test-token"),
        var respond: (Call) -> RawHttpResponse = { response(201, """{"added":{"movies":1,"episodes":2}}""") },
    ) {
        val calls = mutableListOf<Call>()
        var accountAfterTokenRefresh: String? = null

        val adapter = TraktOutboxAdapter(
            isOnline = { online },
            activeProfileId = { activeProfileId },
            accountKey = { account },
            authorizedHeaders = {
                accountAfterTokenRefresh?.let { account = it }
                headers
            },
            request = { method, url, requestHeaders, body ->
                val call = Call(method, url, requestHeaders, body)
                calls += call
                respond(call)
            },
        )
    }

    @Test
    fun a_batch_is_posted_to_sync_history_with_the_true_watch_times() = runTest {
        val fixture = Fixture()

        val outcome = fixture.adapter.send(
            listOf(
                entry("1", movie),
                entry("2", episode(season = 2, number = 5), watchedAtEpochMs = WatchedAtEpochMs + 60_000L),
                entry("3", episode(season = 2, number = 6), watchedAtEpochMs = WatchedAtEpochMs + 120_000L),
            ),
        )

        assertEquals(OutboxSendOutcome.Delivered, outcome)
        val call = fixture.calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://api.trakt.tv/sync/history", call.url)
        assertEquals("Bearer test-token", call.headers["Authorization"])
        assertEquals(
            """{"movies":[{"ids":{"imdb":"tt0000001","tmdb":55},"watched_at":"$WatchedAtIso"}],""" +
                """"shows":[{"ids":{"imdb":"tt0000009"},"seasons":[{"number":2,"episodes":[""" +
                """{"number":5,"watched_at":"2026-11-12T12:01:00.000Z"},""" +
                """{"number":6,"watched_at":"2026-11-12T12:02:00.000Z"}]}]}]}""",
            call.body,
        )
    }

    @Test
    fun each_answer_is_classified_by_whether_trakt_may_have_recorded_the_batch() {
        val adapter = Fixture().adapter
        fun classify(status: Int) =
            adapter.classify(response(status, """{"added":{"movies":1}}"""), batchSize = 1)

        assertEquals(OutboxSendOutcome.Delivered, classify(201))
        assertEquals(OutboxSendOutcome.Delivered, classify(200))
        for (status in listOf(408, 500, 502, 503, 504, 520)) {
            val outcome = assertIs<OutboxSendOutcome.Retry>(classify(status), "HTTP $status")
            assertTrue(outcome.maybeDelivered, "HTTP $status may have been recorded")
        }
        for (status in listOf(401, 403, 409, 420, 423, 429)) {
            val outcome = assertIs<OutboxSendOutcome.Retry>(classify(status), "HTTP $status")
            assertFalse(outcome.maybeDelivered, "HTTP $status was not recorded")
        }
        for (status in listOf(400, 404, 422)) {
            assertIs<OutboxSendOutcome.Rejected>(classify(status), "HTTP $status")
        }
    }

    @Test
    fun retry_after_is_passed_on_in_milliseconds() {
        val adapter = Fixture().adapter

        val limited = adapter.classify(response(429, "", mapOf("Retry-After" to "17")), batchSize = 3)
        val overloaded = adapter.classify(response(503, "", mapOf("retry-after" to " 4 ")), batchSize = 3)
        val unparsable = adapter.classify(response(429, "", mapOf("retry-after" to "Thu, 12 Nov 2026 08:00:00 GMT")), batchSize = 3)

        assertEquals(17_000L, assertIs<OutboxSendOutcome.Retry>(limited).retryAfterMs)
        assertEquals(4_000L, assertIs<OutboxSendOutcome.Retry>(overloaded).retryAfterMs)
        assertNull(assertIs<OutboxSendOutcome.Retry>(unparsable).retryAfterMs)
    }

    @Test
    fun a_lost_answer_may_have_been_delivered() = runTest {
        val fixture = Fixture(respond = { throw IllegalStateException("connection reset") })

        val outcome = fixture.adapter.send(listOf(entry("1", movie)))

        assertTrue(assertIs<OutboxSendOutcome.Retry>(outcome).maybeDelivered)
    }

    @Test
    fun a_single_watch_trakt_cannot_match_is_rejected_and_a_matched_one_is_delivered() {
        val adapter = Fixture().adapter
        val notFound = """{"added":{"movies":0,"episodes":0},"not_found":{"movies":[{"ids":{"imdb":"tt0000001"}}],"shows":[]}}"""
        val added = """{"added":{"movies":1,"episodes":0},"not_found":{"movies":[],"shows":[],"seasons":[],"episodes":[]}}"""

        assertIs<OutboxSendOutcome.Rejected>(adapter.classify(response(201, notFound), batchSize = 1))
        assertEquals(OutboxSendOutcome.Delivered, adapter.classify(response(201, added), batchSize = 1))
    }

    @Test
    fun an_accepted_answer_that_does_not_confirm_every_watch_is_checked_against_history() {
        val adapter = Fixture().adapter
        val unconfirmed = listOf(
            "<html>" to 1,
            "{}" to 1,
            """{"added":{"movies":0,"episodes":0}}""" to 1,
            """{"added":{"movies":1,"episodes":1}}""" to 3,
        )

        for ((body, batchSize) in unconfirmed) {
            val outcome = assertIs<OutboxSendOutcome.Retry>(adapter.classify(response(201, body), batchSize), body)
            assertTrue(outcome.maybeDelivered, body)
        }
        assertEquals(
            OutboxSendOutcome.Delivered,
            adapter.classify(response(201, """{"added":{"movies":1,"episodes":2}}"""), batchSize = 3),
        )
    }

    @Test
    fun a_partly_matched_batch_is_retried_through_the_history_check_instead_of_guessing() {
        val adapter = Fixture().adapter
        val partial = """{"added":{"movies":1,"episodes":0},"not_found":{"movies":[{"ids":{"imdb":"tt0000002"}}]}}"""

        val outcome = adapter.classify(response(201, partial), batchSize = 2)

        assertTrue(assertIs<OutboxSendOutcome.Retry>(outcome).maybeDelivered)
    }

    @Test
    fun nothing_is_requested_while_the_token_cannot_be_refreshed() = runTest {
        val fixture = Fixture(headers = null)

        val outcome = fixture.adapter.send(listOf(entry("1", movie)))

        assertFalse(assertIs<OutboxSendOutcome.Retry>(outcome).maybeDelivered)
        assertNull(fixture.adapter.isRecorded(entry("1", movie)))
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun nothing_is_requested_for_another_account_or_an_inactive_profile_or_while_offline() = runTest {
        val otherAccount = Fixture(account = "account-b")
        val otherProfile = Fixture(activeProfileId = 2)
        val switchedDuringRefresh = Fixture().apply { accountAfterTokenRefresh = "account-b" }
        val offline = Fixture(online = false)
        assertNull(offline.adapter.connectedAccountKey(profileId = 1))

        for (fixture in listOf(otherAccount, otherProfile, switchedDuringRefresh, offline)) {
            assertIs<OutboxSendOutcome.Retry>(fixture.adapter.send(listOf(entry("1", movie))))
            assertNull(fixture.adapter.isRecorded(entry("1", movie)))
            assertTrue(fixture.calls.isEmpty(), "a request was made with the wrong login")
        }
        assertNull(otherProfile.adapter.connectedAccountKey(profileId = 1))
        assertEquals("account-a", otherProfile.adapter.connectedAccountKey(profileId = 2))
    }

    @Test
    fun the_history_check_reads_a_time_window_and_puts_no_id_in_the_url() = runTest {
        val fixture = Fixture(respond = { response(200, "[]") })

        assertEquals(false, fixture.adapter.isRecorded(entry("1", movie)))
        assertEquals(false, fixture.adapter.isRecorded(entry("2", episode(season = 1, number = 1))))

        assertEquals(
            listOf(
                "https://api.trakt.tv/sync/history/movies?start_at=2026-11-12T11:55:00.000Z&end_at=2026-11-12T12:05:01.000Z&page=1&limit=100",
                "https://api.trakt.tv/sync/history/episodes?start_at=2026-11-12T11:55:00.000Z&end_at=2026-11-12T12:05:01.000Z&page=1&limit=100",
            ),
            fixture.calls.map { it.url },
        )
        assertTrue(fixture.calls.all { it.method == "GET" })
    }

    @Test
    fun a_play_just_inside_the_window_is_this_watch_and_one_at_the_edge_is_another_play() = runTest {
        fun historyAt(offsetMs: Long) = Fixture(
            respond = {
                response(
                    200,
                    """[{"watched_at":"${TraktWatchedSyncAdapter.epochMsToIso(WatchedAtEpochMs + offsetMs)}",""" +
                        """"movie":{"ids":{"trakt":7,"imdb":"tt0000001"}}}]""",
                )
            },
        )

        assertEquals(true, historyAt(0L).adapter.isRecorded(entry("1", movie)))
        assertEquals(true, historyAt(SameWatchWindowMs - 1_000L).adapter.isRecorded(entry("1", movie)))
        assertEquals(true, historyAt(-(SameWatchWindowMs - 1_000L)).adapter.isRecorded(entry("1", movie)))
        assertEquals(false, historyAt(SameWatchWindowMs).adapter.isRecorded(entry("1", movie)))
        assertEquals(false, historyAt(-SameWatchWindowMs).adapter.isRecorded(entry("1", movie)))
    }

    @Test
    fun only_the_same_title_and_episode_counts_as_recorded() = runTest {
        val history = """[
            {"watched_at":"$WatchedAtIso","movie":{"ids":{"imdb":"tt0000002","tmdb":56}}},
            {"watched_at":"$WatchedAtIso","episode":{"season":2,"number":6},"show":{"ids":{"imdb":"tt0000009"}}},
            {"watched_at":"$WatchedAtIso","episode":{"season":2,"number":5},"show":{"ids":{"imdb":"tt0000010"}}}
        ]"""
        val fixture = Fixture(respond = { response(200, history) })

        assertEquals(false, fixture.adapter.isRecorded(entry("1", movie)))
        assertEquals(false, fixture.adapter.isRecorded(entry("2", episode(season = 2, number = 5))))
        assertEquals(true, fixture.adapter.isRecorded(entry("3", episode(season = 2, number = 6))))
        assertEquals(true, fixture.adapter.isRecorded(entry("4", OutboxMedia(kind = OutboxMediaKind.MOVIE, tmdb = 56))))
    }

    @Test
    fun the_history_check_does_not_guess_when_trakt_cannot_be_read() = runTest {
        val failing = Fixture(respond = { response(503, "") })
        val garbled = Fixture(respond = { response(200, "<html>") })
        val unreachable = Fixture(respond = { throw IllegalStateException("no route to host") })

        assertNull(failing.adapter.isRecorded(entry("1", movie)))
        assertNull(garbled.adapter.isRecorded(entry("1", movie)))
        assertNull(unreachable.adapter.isRecorded(entry("1", movie)))
    }

    @Test
    fun the_history_check_reads_further_pages_and_gives_up_without_an_answer_when_there_are_too_many() = runTest {
        val otherPlay = """{"watched_at":"$WatchedAtIso","movie":{"ids":{"imdb":"tt0000002"}}}"""
        val thisPlay = """{"watched_at":"$WatchedAtIso","movie":{"ids":{"imdb":"tt0000001"}}}"""
        val fullPage = List(100) { otherPlay }.joinToString(prefix = "[", postfix = "]", separator = ",")

        val onSecondPage = Fixture(respond = { call -> response(200, if ("page=1&" in call.url) fullPage else "[$thisPlay]") })
        assertEquals(true, onSecondPage.adapter.isRecorded(entry("1", movie)))
        assertEquals(2, onSecondPage.calls.size)

        val endless = Fixture(respond = { response(200, fullPage) })
        assertNull(endless.adapter.isRecorded(entry("1", movie)))
        assertEquals(10, endless.calls.size)
    }
}

private val movie = OutboxMedia(kind = OutboxMediaKind.MOVIE, imdb = "tt0000001", tmdb = 55)

private fun episode(season: Int, number: Int) =
    OutboxMedia(kind = OutboxMediaKind.EPISODE, imdb = "tt0000009", season = season, number = number)

private fun entry(id: String, media: OutboxMedia, watchedAtEpochMs: Long = WatchedAtEpochMs) = OutboxEntry(
    id = id,
    profileId = 1,
    accountKey = "account-a",
    media = media,
    watchedAtEpochMs = watchedAtEpochMs,
    createdAtEpochMs = watchedAtEpochMs,
)

private fun response(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
    RawHttpResponse(status = status, statusText = "", url = "", body = body, headers = headers)

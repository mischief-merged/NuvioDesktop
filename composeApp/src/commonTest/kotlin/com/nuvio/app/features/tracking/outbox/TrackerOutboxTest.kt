package com.nuvio.app.features.tracking.outbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerOutboxTest {
    @Test
    fun a_queued_watch_is_sent_once_with_its_watch_time_and_then_removed() = runTest {
        val fixture = OutboxFixture()
        val watchedAt = TestStartEpochMs - 3_600_000L

        assertTrue(fixture.outbox.enqueue(watch(watchedAtEpochMs = watchedAt)))
        fixture.outbox.flush(profileId = 1)
        fixture.outbox.flush(profileId = 1)

        assertEquals(1, fixture.adapter.sent.size)
        assertEquals(watchedAt, fixture.adapter.sent.single().single().watchedAtEpochMs)
        assertTrue(fixture.outbox.entries().isEmpty())
        assertTrue(fixture.newOutbox().entries().isEmpty(), "the delivered watch is still on disk")
    }

    @Test
    fun the_same_watch_reported_twice_is_queued_once_even_with_different_ids() = runTest {
        val fixture = OutboxFixture()
        val fromPlayer = OutboxMedia(kind = OutboxMediaKind.MOVIE, imdb = "tt0000001", tmdb = 55, trakt = 900)
        val fromWatchedList = OutboxMedia(kind = OutboxMediaKind.MOVIE, tmdb = 55)

        assertTrue(fixture.outbox.enqueue(watch(media = fromPlayer)))
        assertTrue(fixture.outbox.enqueue(watch(media = fromWatchedList, watchedAtEpochMs = TestStartEpochMs + 20_000L)))

        assertEquals(1, fixture.outbox.entries().size)
    }

    @Test
    fun a_watch_one_millisecond_inside_the_window_is_the_same_watch_and_at_the_edge_it_is_a_rewatch() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = episode(1, 2)))

        fixture.outbox.enqueue(watch(media = episode(1, 2), watchedAtEpochMs = TestStartEpochMs + SameWatchWindowMs - 1))
        assertEquals(1, fixture.outbox.entries().size, "a report inside the window was queued as a second play")

        fixture.outbox.enqueue(watch(media = episode(1, 2), watchedAtEpochMs = TestStartEpochMs + SameWatchWindowMs))
        assertEquals(2, fixture.outbox.entries().size, "a rewatch at the edge of the window was swallowed")

        fixture.outbox.flush(profileId = 1)
        assertEquals(2, fixture.adapter.sent.single().size)
    }

    @Test
    fun a_different_episode_or_account_or_profile_is_never_treated_as_the_same_watch() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = episode(1, 2)))
        fixture.outbox.enqueue(watch(media = episode(1, 3)))
        fixture.outbox.enqueue(watch(media = episode(1, 2), accountKey = "account-b"))
        fixture.outbox.enqueue(watch(media = episode(1, 2), profileId = 2))

        assertEquals(4, fixture.outbox.entries().size)
    }

    @Test
    fun a_second_report_that_may_have_been_delivered_makes_the_queued_watch_check_history_first() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        fixture.outbox.enqueue(watch(watchedAtEpochMs = TestStartEpochMs + 5_000L, maybeDelivered = true))
        fixture.adapter.recorded = { true }

        fixture.now += TrackerOutbox.SettleDelayMs
        fixture.outbox.flush(profileId = 1)

        assertEquals(1, fixture.adapter.checked.size)
        assertTrue(fixture.adapter.sent.isEmpty(), "a watch Trakt already holds was sent again")
        assertTrue(fixture.outbox.entries().isEmpty())
    }

    @Test
    fun a_watch_whose_answer_was_lost_is_dropped_when_history_already_has_it() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        fixture.adapter.outcomes += OutboxSendOutcome.Retry(retryAfterMs = null, maybeDelivered = true, reason = "HTTP 502")
        fixture.outbox.flush(profileId = 1)
        assertEquals(1, fixture.outbox.entries().size, "the watch was dropped although no success was seen")

        fixture.adapter.recorded = { true }
        fixture.now += 3_600_000L
        fixture.outbox.flush(profileId = 1)

        assertEquals(1, fixture.adapter.sent.size, "the watch was posted a second time")
        assertTrue(fixture.outbox.entries().isEmpty())
    }

    @Test
    fun a_watch_whose_answer_was_lost_is_sent_again_when_history_does_not_have_it() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        fixture.adapter.outcomes += OutboxSendOutcome.Retry(retryAfterMs = null, maybeDelivered = true, reason = "timeout")
        fixture.outbox.flush(profileId = 1)

        fixture.now += 3_600_000L
        fixture.outbox.flush(profileId = 1)

        assertEquals(1, fixture.adapter.checked.size)
        assertEquals(2, fixture.adapter.sent.size)
        assertTrue(fixture.outbox.entries().isEmpty())
    }

    @Test
    fun a_watch_that_may_have_been_delivered_waits_while_history_cannot_be_read() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(maybeDelivered = true))
        fixture.adapter.recorded = { null }

        fixture.now += TrackerOutbox.SettleDelayMs
        fixture.outbox.flush(profileId = 1)
        fixture.outbox.flush(profileId = 1)

        assertTrue(fixture.adapter.sent.isEmpty(), "the watch was sent without knowing whether Trakt has it")
        assertEquals(1, fixture.adapter.checked.size, "history was asked again before the backoff ended")
        val entry = fixture.outbox.entries().single()
        assertEquals(fixture.now + 30_000L, entry.nextAttemptAtEpochMs)

        fixture.adapter.recorded = { false }
        fixture.now += 30_000L
        fixture.outbox.flush(profileId = 1)
        assertEquals(1, fixture.adapter.sent.size)
    }

    @Test
    fun a_failing_history_check_counts_as_not_knowing() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(maybeDelivered = true))
        fixture.adapter.recorded = { error("connection reset") }

        fixture.now += TrackerOutbox.SettleDelayMs
        fixture.outbox.flush(profileId = 1)

        assertEquals(1, fixture.adapter.checked.size)
        assertTrue(fixture.adapter.sent.isEmpty())
        assertEquals(1, fixture.outbox.entries().size)
    }

    @Test
    fun a_watch_that_may_still_be_arriving_is_not_checked_before_the_settle_delay() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(maybeDelivered = true))

        fixture.now += TrackerOutbox.SettleDelayMs - 1L
        fixture.outbox.flush(profileId = 1)
        assertTrue(fixture.adapter.checked.isEmpty(), "history was read while the first request could still be landing")
        assertTrue(fixture.adapter.sent.isEmpty())

        fixture.now += 1L
        fixture.outbox.flush(profileId = 1)
        assertEquals(1, fixture.adapter.checked.size)
        assertEquals(1, fixture.adapter.sent.size)
    }

    @Test
    fun an_entry_that_was_in_flight_when_the_app_stopped_is_checked_before_it_is_sent_again() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        fixture.adapter.beforeAnswer = { throw CancellationException("app is closing") }
        assertFailsWith<CancellationException> { fixture.outbox.flush(profileId = 1) }
        val storedWhileInFlight = fixture.store.payload!!.replace("\"PENDING\"", "\"IN_FLIGHT\"")
        assertTrue("IN_FLIGHT" in storedWhileInFlight)

        val restarted = OutboxFixture(store = MemoryOutboxStore(storedWhileInFlight))
        restarted.adapter.recorded = { true }
        restarted.outbox.flush(profileId = 1)
        assertTrue(restarted.adapter.checked.isEmpty(), "history was read before the interrupted request could settle")
        assertTrue(restarted.adapter.sent.isEmpty(), "a watch that may have been delivered was sent blind")

        restarted.now += TrackerOutbox.SettleDelayMs
        restarted.outbox.flush(profileId = 1)

        assertEquals(1, restarted.adapter.checked.size)
        assertTrue(restarted.adapter.sent.isEmpty(), "a watch that may have been delivered was sent blind")
        assertTrue(restarted.outbox.entries().isEmpty())
    }

    @Test
    fun the_entry_is_marked_in_flight_on_disk_while_the_request_is_under_way() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        var storedDuringSend: String? = null
        fixture.adapter.beforeAnswer = { storedDuringSend = fixture.store.payload }

        fixture.outbox.flush(profileId = 1)

        assertTrue("IN_FLIGHT" in storedDuringSend.orEmpty())
    }

    @Test
    fun a_cancelled_send_leaves_the_watch_queued_and_checked_before_the_next_attempt() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        fixture.adapter.beforeAnswer = { throw CancellationException("scope closed") }
        assertFailsWith<CancellationException> { fixture.outbox.flush(profileId = 1) }

        val entry = fixture.outbox.entries().single()
        assertEquals(OutboxState.PENDING, entry.state)
        assertTrue(entry.verifyBeforeSend)

        fixture.adapter.beforeAnswer = {}
        fixture.now += TrackerOutbox.SettleDelayMs - 1L
        fixture.outbox.flush(profileId = 1)
        assertTrue(fixture.adapter.checked.isEmpty(), "history was read before the cancelled request could settle")

        fixture.now += 1L
        fixture.outbox.flush(profileId = 1)
        assertEquals(1, fixture.adapter.checked.size)
        assertEquals(2, fixture.adapter.sent.size)
    }

    @Test
    fun unreadable_stored_data_is_kept_aside_and_never_replaced_by_an_empty_queue() = runTest {
        val fixture = OutboxFixture(store = MemoryOutboxStore("{\"version\":1,\"entries\":[{\"id\":"))

        assertTrue(fixture.outbox.entries().isEmpty())
        assertEquals(listOf("{\"version\":1,\"entries\":[{\"id\":"), fixture.store.unreadable)

        fixture.outbox.enqueue(watch())
        assertEquals(1, fixture.newOutbox().entries().size)
        assertEquals(1, fixture.store.unreadable.size, "the copy kept aside was written again")
    }

    @Test
    fun stored_data_is_left_untouched_when_it_cannot_be_kept_aside() = runTest {
        val store = MemoryOutboxStore("not json").apply { failUnreadableSaves = true }
        val fixture = OutboxFixture(store = store)

        assertFailsWith<IllegalStateException> { fixture.outbox.enqueue(watch()) }

        assertEquals("not json", store.payload)
        store.failUnreadableSaves = false
        assertTrue(fixture.outbox.enqueue(watch()))
        assertEquals(listOf("not json"), store.unreadable)
    }

    @Test
    fun data_written_by_a_newer_version_is_kept_aside_instead_of_being_reinterpreted() = runTest {
        val newer = "{\"version\":2,\"entries\":[]}"
        val fixture = OutboxFixture(store = MemoryOutboxStore(newer))

        fixture.outbox.enqueue(watch())

        assertEquals(listOf(newer), fixture.store.unreadable)
    }

    @Test
    fun a_watch_queued_under_another_account_is_held_and_sent_when_that_account_is_back() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(accountKey = "account-a"))

        fixture.adapter.account = "account-b"
        fixture.outbox.flush(profileId = 1)
        assertTrue(fixture.adapter.sent.isEmpty(), "a watch was sent to a different Trakt account")
        assertNull(fixture.outbox.nextWakeAtEpochMs(profileId = 1))
        assertEquals(1, fixture.outbox.entries().size, "the held watch was dropped")

        fixture.adapter.account = null
        fixture.outbox.flush(profileId = 1)
        assertTrue(fixture.adapter.sent.isEmpty(), "a watch was sent while Trakt was disconnected")

        fixture.adapter.account = "account-a"
        fixture.outbox.flush(profileId = 1)
        assertEquals(1, fixture.adapter.sent.size)
    }

    @Test
    fun a_watch_of_another_profile_is_neither_sent_nor_scheduled() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(profileId = 1))
        fixture.adapter.activeProfileId = 2

        fixture.outbox.flush(profileId = 2)
        fixture.outbox.flush(profileId = 1)

        assertTrue(fixture.adapter.sent.isEmpty())
        assertNull(fixture.outbox.nextWakeAtEpochMs(profileId = 2))
        assertNull(fixture.outbox.nextWakeAtEpochMs(profileId = 1))
        assertEquals(1, fixture.outbox.entries().size)
    }

    @Test
    fun un_watching_a_title_removes_its_queued_watches_whatever_ids_name_it() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = OutboxMedia(kind = OutboxMediaKind.MOVIE, imdb = "tt0000001", tmdb = 55)))
        fixture.outbox.enqueue(watch(media = movieB, maybeDelivered = true))
        fixture.outbox.enqueue(watch(media = episode(1, 2)))
        fixture.outbox.enqueue(watch(media = episode(1, 3)))

        val removed = fixture.outbox.cancel(
            profileId = 1,
            media = listOf(OutboxMedia(kind = OutboxMediaKind.MOVIE, tmdb = 55), movieB, episode(1, 2)),
        )

        assertEquals(3, removed)
        assertEquals(listOf(episode(1, 3)), fixture.outbox.entries().map { it.media })
        fixture.outbox.flush(profileId = 1)
        assertEquals(listOf(episode(1, 3)), fixture.adapter.sent.flatten().map { it.media })
    }

    @Test
    fun un_watching_does_not_touch_another_profile() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(profileId = 2))

        assertEquals(0, fixture.outbox.cancel(profileId = 1, media = listOf(movieA)))
        assertEquals(1, fixture.outbox.entries().size)
    }

    @Test
    fun un_watching_waits_for_a_send_that_is_under_way() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        val answer = CompletableDeferred<Unit>()
        fixture.adapter.beforeAnswer = { answer.await() }

        val flush = launch { fixture.outbox.flush(profileId = 1) }
        runCurrent()
        val cancel = async { fixture.outbox.cancel(profileId = 1, media = listOf(movieA)) }
        runCurrent()
        assertFalse(cancel.isCompleted, "the un-watch overtook the request that was adding the watch")

        answer.complete(Unit)
        flush.join()
        assertEquals(0, cancel.await())
        assertTrue(fixture.outbox.entries().isEmpty())
    }

    @Test
    fun a_rejected_watch_is_kept_as_dead_and_never_sent_again() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        fixture.adapter.outcomes += OutboxSendOutcome.Rejected("HTTP 404")

        fixture.outbox.flush(profileId = 1)
        fixture.now += 3_600_000L
        fixture.outbox.flush(profileId = 1)

        assertEquals(1, fixture.adapter.sent.size)
        assertEquals(OutboxState.DEAD, fixture.outbox.entries().single().state)
        assertNull(fixture.outbox.nextWakeAtEpochMs(profileId = 1))
    }

    @Test
    fun a_rejected_batch_is_sent_again_one_watch_at_a_time_so_one_bad_title_does_not_sink_the_rest() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA))
        fixture.outbox.enqueue(watch(media = movieB))
        fixture.adapter.outcomes += listOf(
            OutboxSendOutcome.Rejected("HTTP 422"),
            OutboxSendOutcome.Rejected("HTTP 422"),
            OutboxSendOutcome.Delivered,
        )

        fixture.outbox.flush(profileId = 1)

        assertEquals(listOf(2, 1, 1), fixture.adapter.sent.map { it.size })
        val left = fixture.outbox.entries().single()
        assertEquals(movieA, left.media)
        assertEquals(OutboxState.DEAD, left.state)
    }

    @Test
    fun a_retry_after_delay_is_honoured_and_an_absurd_one_is_capped() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        fixture.adapter.outcomes += OutboxSendOutcome.Retry(retryAfterMs = 120_000L, maybeDelivered = false, reason = "HTTP 429")
        fixture.outbox.flush(profileId = 1)
        assertEquals(TestStartEpochMs + 120_000L, fixture.outbox.entries().single().nextAttemptAtEpochMs)

        fixture.now += 119_999L
        fixture.outbox.flush(profileId = 1)
        assertEquals(1, fixture.adapter.sent.size, "the watch was retried before Retry-After had passed")

        fixture.now += 1L
        fixture.adapter.outcomes += OutboxSendOutcome.Retry(retryAfterMs = Long.MAX_VALUE, maybeDelivered = false, reason = "HTTP 429")
        fixture.outbox.flush(profileId = 1)
        assertEquals(fixture.now + TrackerOutbox.MaxRetryAfterMs, fixture.outbox.entries().single().nextAttemptAtEpochMs)
    }

    @Test
    fun a_not_before_time_delays_the_first_attempt() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(notBeforeEpochMs = TestStartEpochMs + 60_000L))

        fixture.outbox.flush(profileId = 1)
        assertTrue(fixture.adapter.sent.isEmpty())
        assertEquals(TestStartEpochMs + 60_000L, fixture.outbox.nextWakeAtEpochMs(profileId = 1))

        fixture.now += 60_000L
        fixture.outbox.flush(profileId = 1)
        assertEquals(1, fixture.adapter.sent.size)
    }

    @Test
    fun after_a_failure_the_other_due_watches_wait_for_the_same_backoff() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA, maybeDelivered = true))
        fixture.outbox.enqueue(watch(media = movieB))
        fixture.outbox.enqueue(watch(media = episode(1, 1)))
        fixture.adapter.recorded = { null }

        fixture.now += TrackerOutbox.SettleDelayMs
        fixture.outbox.flush(profileId = 1)
        fixture.outbox.flush(profileId = 1)

        assertTrue(fixture.adapter.sent.isEmpty())
        assertEquals(1, fixture.adapter.checked.size)
        assertEquals(fixture.now + 30_000L, fixture.outbox.nextWakeAtEpochMs(profileId = 1))
        assertTrue(fixture.outbox.entries().all { it.nextAttemptAtEpochMs == fixture.now + 30_000L })
    }

    @Test
    fun writes_to_the_tracker_are_spaced_apart() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA, maybeDelivered = true))
        fixture.outbox.enqueue(watch(media = movieB, maybeDelivered = true))

        fixture.now += TrackerOutbox.SettleDelayMs
        fixture.outbox.flush(profileId = 1)

        assertEquals(2, fixture.adapter.sent.size)
        assertEquals(listOf(TrackerOutbox.MinSendIntervalMs), fixture.slept)
    }

    @Test
    fun the_queue_is_bounded_and_makes_room_by_dropping_a_dead_entry_first() = runTest {
        val fixture = OutboxFixture()
        repeat(TrackerOutbox.MaxEntries) { index ->
            assertTrue(fixture.outbox.enqueue(watch(media = episode(1, index))))
        }
        assertFalse(fixture.outbox.enqueue(watch(media = movieA)), "the queue grew past its limit")
        assertEquals(TrackerOutbox.MaxEntries, fixture.outbox.entries().size)

        val bounded = OutboxFixture()
        bounded.outbox.enqueue(watch(media = movieB))
        bounded.adapter.outcomes += OutboxSendOutcome.Rejected("HTTP 404")
        bounded.outbox.flush(profileId = 1)
        repeat(TrackerOutbox.MaxEntries - 1) { index -> bounded.outbox.enqueue(watch(media = episode(1, index))) }

        assertTrue(bounded.outbox.enqueue(watch(media = movieA)))
        assertEquals(TrackerOutbox.MaxEntries, bounded.outbox.entries().size)
        assertTrue(bounded.outbox.entries().none { it.state == OutboxState.DEAD })
    }

    @Test
    fun entries_are_kept_up_to_the_retention_age_and_purged_just_after_it() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA, accountKey = "account-b"))
        fixture.outbox.enqueue(watch(media = movieB))
        fixture.adapter.outcomes += OutboxSendOutcome.Rejected("HTTP 404")
        fixture.outbox.flush(profileId = 1)
        assertEquals(2, fixture.outbox.entries().size)

        fixture.adapter.account = null
        fixture.now = TestStartEpochMs + TrackerOutbox.MaxAgeMs
        fixture.outbox.flush(profileId = 1)
        assertEquals(2, fixture.outbox.entries().size, "entries were purged before the retention age")

        fixture.now += 1L
        fixture.outbox.flush(profileId = 1)
        assertTrue(fixture.outbox.entries().isEmpty(), "held and dead entries outlived the retention age while disconnected")
    }

    @Test
    fun a_watch_with_a_malformed_id_is_never_queued() = runTest {
        val fixture = OutboxFixture()

        assertNull(OutboxMedia.movie(imdb = "tt1/../../users/settings", tmdb = null, trakt = null))
        assertNull(OutboxMedia.movie(imdb = "0000001", tmdb = 0, trakt = -4))
        assertEquals(
            OutboxMedia(kind = OutboxMediaKind.MOVIE, tmdb = 55),
            OutboxMedia.movie(imdb = "tt1?x=1", tmdb = 55, trakt = null),
        )
        assertNull(OutboxMedia.episode(imdb = "tt0000009", tmdb = null, trakt = null, season = -1, number = 1))

        assertFalse(fixture.outbox.enqueue(watch(media = OutboxMedia(kind = OutboxMediaKind.MOVIE, imdb = "tt1/../x"))))
        assertFalse(fixture.outbox.enqueue(watch(media = OutboxMedia(kind = OutboxMediaKind.MOVIE))))
        assertFalse(fixture.outbox.enqueue(watch(accountKey = " ")))
        assertTrue(fixture.outbox.entries().isEmpty())
    }

    @Test
    fun a_stored_entry_with_a_malformed_id_is_never_sent() = runTest {
        val seed = OutboxFixture()
        seed.outbox.enqueue(watch())
        val tampered = seed.store.payload!!.replace("tt0000001", "tt1/../../x")

        val fixture = OutboxFixture(store = MemoryOutboxStore(tampered))
        fixture.outbox.flush(profileId = 1)

        assertTrue(fixture.adapter.sent.isEmpty())
        assertTrue(fixture.adapter.checked.isEmpty())
        assertEquals(OutboxState.DEAD, fixture.outbox.entries().single().state)
    }

    @Test
    fun nothing_is_sent_when_the_in_flight_mark_cannot_be_written() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        fixture.store.failSaves = true

        assertFailsWith<IllegalStateException> { fixture.outbox.flush(profileId = 1) }

        assertTrue(fixture.adapter.sent.isEmpty())
        assertEquals(OutboxState.PENDING, fixture.outbox.entries().single().state)
    }

    @Test
    fun watches_queued_from_many_threads_while_the_queue_is_still_loading_all_land_once() = runTest {
        val seed = OutboxFixture()
        seed.outbox.enqueue(watch(media = movieA))
        val fixture = OutboxFixture(store = MemoryOutboxStore(seed.store.payload))

        withContext(Dispatchers.Default) {
            (0 until 8).map { worker ->
                async {
                    repeat(25) { index -> fixture.outbox.enqueue(watch(media = episode(worker, index))) }
                }
            }.awaitAll()
        }

        val entries = fixture.outbox.entries()
        assertEquals(201, entries.size)
        assertEquals(201, entries.map { it.id }.toSet().size)
        assertEquals(201, fixture.newOutbox().entries().size)
    }

    @Test
    fun a_doubtful_answer_is_never_checked_sooner_than_the_settle_delay_even_with_the_shortest_backoff() = runTest {
        val fixture = OutboxFixture()
        val outbox = TrackerOutbox(fixture.store, fixture.adapter, nowEpochMs = { fixture.now }, sleep = {}, random = { 0.0 })
        outbox.enqueue(watch())
        fixture.adapter.outcomes += OutboxSendOutcome.Retry(retryAfterMs = null, maybeDelivered = true, reason = "HTTP 502")

        outbox.flush(profileId = 1)

        assertEquals(TestStartEpochMs + TrackerOutbox.SettleDelayMs, outbox.entries().single().nextAttemptAtEpochMs)
    }

    @Test
    fun a_watch_queued_during_a_backoff_waits_for_it_instead_of_causing_another_request() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA))
        fixture.adapter.outcomes += OutboxSendOutcome.Retry(retryAfterMs = 600_000L, maybeDelivered = false, reason = "HTTP 429")
        fixture.outbox.flush(profileId = 1)

        fixture.now += 10_000L
        fixture.outbox.enqueue(watch(media = movieB, watchedAtEpochMs = fixture.now))
        fixture.outbox.enqueue(watch(media = movieB, watchedAtEpochMs = fixture.now, accountKey = "account-b"))
        fixture.outbox.flush(profileId = 1)

        assertEquals(1, fixture.adapter.sent.size, "a new watch was sent while Trakt had asked to wait")
        assertEquals(TestStartEpochMs + 600_000L, fixture.outbox.nextWakeAtEpochMs(profileId = 1))
        assertEquals(0L, fixture.outbox.entries().single { it.accountKey == "account-b" }.nextAttemptAtEpochMs)
    }

    @Test
    fun a_delivered_watch_is_not_sent_again_in_this_session_when_its_removal_cannot_be_written() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        fixture.adapter.beforeAnswer = { fixture.store.failSaves = true }

        assertFailsWith<IllegalStateException> { fixture.outbox.flush(profileId = 1) }
        fixture.store.failSaves = false
        fixture.outbox.flush(profileId = 1)

        assertEquals(1, fixture.adapter.sent.size)
        assertTrue(fixture.outbox.entries().isEmpty())
        assertTrue("IN_FLIGHT" in fixture.store.payload.orEmpty(), "the disk no longer shows the request as doubtful")
    }

    @Test
    fun backoff_grows_from_thirty_seconds_to_an_hour_with_bounded_jitter() {
        assertEquals(30_000L, trackerOutboxBackoffMs(attempts = 1, jitter = 0.0))
        assertEquals(3_600_000L, trackerOutboxBackoffMs(attempts = 7, jitter = 0.0))
        assertEquals(3_600_000L, trackerOutboxBackoffMs(attempts = 50, jitter = 0.0))
        assertEquals(24_000L, trackerOutboxBackoffMs(attempts = 1, jitter = -5.0))
        assertEquals(36_000L, trackerOutboxBackoffMs(attempts = 1, jitter = 1.0))
    }
}

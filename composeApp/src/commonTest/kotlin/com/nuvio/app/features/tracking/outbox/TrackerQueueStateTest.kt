package com.nuvio.app.features.tracking.outbox

import com.nuvio.app.features.settings.TrackingDisconnectPrompt
import com.nuvio.app.features.settings.trackingDisconnectPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerQueueStateTest {
    private fun OutboxFixture.counts(profileId: Int = 1, account: String? = "account-a") =
        outbox.counts(profileId, account)

    // The read API of the outbox

    @Test
    fun an_empty_queue_has_nothing_to_report() {
        val fixture = OutboxFixture()

        assertEquals(OutboxCounts(), fixture.counts())
        assertTrue(fixture.counts().isEmpty)
        assertTrue(fixture.outbox.snapshot.value.entries.isEmpty())
    }

    @Test
    fun queued_watches_are_counted_as_waiting_and_the_snapshot_follows_every_change() = runTest {
        val fixture = OutboxFixture()
        val snapshot = fixture.outbox.snapshot

        fixture.outbox.enqueue(watch(media = movieA))
        fixture.outbox.enqueue(watch(media = movieB))

        assertEquals(2, fixture.counts().waiting)
        assertEquals(2, snapshot.value.countsFor(1, "account-a").waiting)

        fixture.outbox.flush(profileId = 1)

        assertEquals(OutboxCounts(), fixture.counts())
        assertEquals(OutboxCounts(), snapshot.value.countsFor(1, "account-a"))
    }

    @Test
    fun a_watch_the_tracker_refused_is_counted_as_undeliverable_and_not_as_waiting() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA))
        fixture.outbox.enqueue(watch(media = movieB, watchedAtEpochMs = TestStartEpochMs + SameWatchWindowMs * 2))
        repeat(3) { fixture.adapter.outcomes += OutboxSendOutcome.Rejected("HTTP 404") }

        fixture.outbox.flush(profileId = 1)

        assertEquals(OutboxCounts(waiting = 0, undeliverable = 2, retryable = 2), fixture.counts())
    }

    @Test
    fun a_dead_entry_that_names_no_usable_title_is_counted_but_cannot_be_retried() {
        val bad = OutboxEntry(
            id = "x",
            profileId = 1,
            accountKey = "account-a",
            media = OutboxMedia(kind = OutboxMediaKind.MOVIE, imdb = "not-an-id"),
            watchedAtEpochMs = TestStartEpochMs,
            createdAtEpochMs = TestStartEpochMs,
            state = OutboxState.DEAD,
        )
        val counts = OutboxSnapshot(entries = listOf(bad)).countsFor(1, "account-a")

        assertEquals(OutboxCounts(undeliverable = 1, retryable = 0), counts)
    }

    @Test
    fun counts_belong_to_one_profile_and_one_account() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA))
        fixture.outbox.enqueue(watch(media = movieB, accountKey = "account-b"))
        fixture.outbox.enqueue(watch(media = movieA, profileId = 2))

        assertEquals(1, fixture.counts(profileId = 1, account = "account-a").waiting)
        assertEquals(1, fixture.counts(profileId = 1, account = "account-b").waiting)
        assertEquals(2, fixture.counts(profileId = 1, account = null).waiting)
        assertEquals(1, fixture.counts(profileId = 2, account = "account-a").waiting)
        assertEquals(0, fixture.counts(profileId = 3, account = null).waiting)
    }

    @Test
    fun a_watch_removed_for_age_is_counted_as_dropped_until_it_is_dismissed() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA))
        fixture.outbox.enqueue(watch(media = movieB, profileId = 2))
        fixture.adapter.account = null

        fixture.now = TestStartEpochMs + TrackerOutbox.MaxAgeMs
        fixture.outbox.flush(profileId = 1)
        assertEquals(0, fixture.counts().droppedForAge, "a watch was reported dropped before the retention age")

        fixture.now += 1L
        fixture.outbox.flush(profileId = 1)

        assertEquals(OutboxCounts(droppedForAge = 1), fixture.counts(profileId = 1))
        assertEquals(OutboxCounts(droppedForAge = 1), fixture.counts(profileId = 2))
        assertEquals(OutboxCounts(), fixture.counts(profileId = 3), "an unrelated profile was told about it")
        assertEquals(0, fixture.outbox.counts(profileId = 1, accountKey = "account-a").waiting)
    }

    @Test
    fun the_dropped_count_survives_a_restart_and_a_dismissal_clears_it_for_good() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA))
        fixture.now = TestStartEpochMs + TrackerOutbox.MaxAgeMs + 1L
        fixture.outbox.flush(profileId = 1)

        assertEquals(1, fixture.newOutbox().counts(1, "account-a").droppedForAge, "the count was not stored")

        fixture.outbox.dismissDropped(profileId = 1)

        assertEquals(0, fixture.counts().droppedForAge)
        assertEquals(0, fixture.newOutbox().counts(1, "account-a").droppedForAge, "the dismissal was not stored")
    }

    @Test
    fun a_refused_watch_that_ages_out_is_not_reported_as_dropped() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA))
        fixture.adapter.outcomes += OutboxSendOutcome.Rejected("HTTP 404")
        fixture.outbox.flush(profileId = 1)

        fixture.now = TestStartEpochMs + TrackerOutbox.MaxAgeMs + 1L
        fixture.outbox.flush(profileId = 1)

        assertEquals(OutboxCounts(), fixture.counts())
    }

    @Test
    fun a_queue_stored_before_the_dropped_count_existed_still_loads() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch())
        val stored = fixture.store.payload!!
        assertTrue("droppedForAge" in stored)

        val withoutField = stored.replace(Regex(",\"droppedForAge\":\\{[^}]*}"), "")
        assertFalse("droppedForAge" in withoutField)
        val reopened = OutboxFixture(store = MemoryOutboxStore(withoutField))

        assertEquals(1, reopened.outbox.entries().size)
        assertEquals(0, reopened.counts().droppedForAge)
        assertTrue(reopened.store.unreadable.isEmpty())
    }

    @Test
    fun dismissing_removes_only_the_refused_watches_of_that_account() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA))
        fixture.adapter.outcomes += OutboxSendOutcome.Rejected("HTTP 404")
        fixture.outbox.flush(profileId = 1)
        fixture.outbox.enqueue(watch(media = movieB, watchedAtEpochMs = TestStartEpochMs + SameWatchWindowMs * 2))
        assertEquals(OutboxCounts(waiting = 1, undeliverable = 1, retryable = 1), fixture.counts())

        assertEquals(0, fixture.outbox.dismissUndeliverable(profileId = 1, accountKey = "account-b"))
        assertEquals(1, fixture.outbox.dismissUndeliverable(profileId = 1, accountKey = "account-a"))

        assertEquals(OutboxCounts(waiting = 1), fixture.counts())
        assertEquals(OutboxCounts(waiting = 1), fixture.newOutbox().counts(1, "account-a"), "the dismissal was not stored")
    }

    @Test
    fun retrying_puts_refused_watches_back_in_the_queue_with_their_age_unchanged() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA))
        fixture.adapter.outcomes += OutboxSendOutcome.Rejected("HTTP 404")
        fixture.outbox.flush(profileId = 1)
        val createdAt = fixture.outbox.entries().single().createdAtEpochMs
        fixture.now += 1_000L

        assertEquals(1, fixture.outbox.retryUndeliverable(profileId = 1, accountKey = "account-a"))
        assertEquals(OutboxCounts(waiting = 1), fixture.counts())
        assertEquals(createdAt, fixture.outbox.entries().single().createdAtEpochMs)

        fixture.outbox.flush(profileId = 1)

        assertEquals(2, fixture.adapter.sent.size)
        assertEquals(OutboxCounts(), fixture.counts())
    }

    @Test
    fun a_retried_watch_that_is_refused_again_is_dead_again_and_is_not_sent_in_a_loop() = runTest {
        val fixture = OutboxFixture()
        fixture.outbox.enqueue(watch(media = movieA))
        fixture.adapter.outcomes += OutboxSendOutcome.Rejected("HTTP 404")
        fixture.adapter.outcomes += OutboxSendOutcome.Rejected("HTTP 404")
        fixture.outbox.flush(profileId = 1)

        fixture.outbox.retryUndeliverable(profileId = 1, accountKey = "account-a")
        fixture.outbox.flush(profileId = 1)
        fixture.outbox.flush(profileId = 1)

        assertEquals(2, fixture.adapter.sent.size)
        assertEquals(OutboxCounts(undeliverable = 1, retryable = 1), fixture.counts())
    }

    @Test
    fun retrying_leaves_a_dead_entry_that_cannot_be_sent_alone() {
        val bad = OutboxEntry(
            id = "x",
            profileId = 1,
            accountKey = "account-a",
            media = OutboxMedia(kind = OutboxMediaKind.MOVIE, imdb = "not-an-id"),
            watchedAtEpochMs = TestStartEpochMs,
            createdAtEpochMs = TestStartEpochMs,
            state = OutboxState.DEAD,
        )
        val fixture = OutboxFixture(
            store = MemoryOutboxStore(
                """{"version":1,"entries":[{"id":"x","profileId":1,"accountKey":"account-a","media":{"kind":"MOVIE","imdb":"not-an-id"},"watchedAtEpochMs":$TestStartEpochMs,"createdAtEpochMs":$TestStartEpochMs,"state":"DEAD"}]}""",
            ),
        )

        assertEquals(0, fixture.outbox.retryUndeliverable(profileId = 1, accountKey = bad.accountKey))
        assertEquals(OutboxState.DEAD, fixture.outbox.entries().single().state)
    }

    // The result of a sync

    @Test
    fun a_sync_result_counts_what_was_sent_what_still_waits_and_what_was_refused() {
        val before = OutboxCounts(waiting = 5)

        val result = queueSyncResult(before, OutboxCounts(waiting = 1, undeliverable = 1, retryable = 1), offline = false, retryInMs = 90_000L)

        assertEquals(QueueSyncResult(sent = 3, stillWaiting = 1, newlyRefused = 1, offline = false, retryInMs = 90_000L), result)
    }

    @Test
    fun a_sync_result_never_reports_a_negative_count_or_a_past_retry_time() {
        val result = queueSyncResult(OutboxCounts(waiting = 1), OutboxCounts(waiting = 2), offline = true, retryInMs = -5L)

        assertEquals(QueueSyncResult(sent = 0, stillWaiting = 2, newlyRefused = 0, offline = true, retryInMs = null), result)
    }

    // The controller the settings screen uses

    private class Harness(val fixture: OutboxFixture = OutboxFixture()) {
        val identity = MutableStateFlow(QueueIdentity(profileId = 1, accountKey = "account-a"))
        var online = true
    }

    private fun TestScopeController(harness: Harness, scope: kotlinx.coroutines.CoroutineScope) = OutboxQueueController(
        outbox = harness.fixture.outbox,
        scope = scope,
        identity = harness.identity,
        isOnline = { harness.online },
        nowEpochMs = { harness.fixture.now },
    )

    @Test
    fun sync_now_sends_what_is_waiting_and_reports_it() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness()
        harness.fixture.outbox.enqueue(watch(media = movieA))
        harness.fixture.outbox.enqueue(watch(media = movieB))
        val controller = TestScopeController(harness, backgroundScope)
        assertEquals(2, controller.counts.value.waiting)

        controller.syncNow()

        val done = assertIs<QueueSyncState.Done>(controller.syncState.value)
        assertEquals(QueueSyncResult(sent = 2, stillWaiting = 0, newlyRefused = 0, offline = false, retryInMs = null), done.result)
        assertEquals(OutboxCounts(), controller.counts.value)

        controller.clearResult()
        assertEquals(QueueSyncState.Idle, controller.syncState.value)
    }

    @Test
    fun sync_now_shows_that_it_is_sending_and_ignores_a_second_press_meanwhile() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness()
        harness.fixture.outbox.enqueue(watch(media = movieA))
        val answer = CompletableDeferred<Unit>()
        harness.fixture.adapter.beforeAnswer = { answer.await() }
        val controller = TestScopeController(harness, backgroundScope)

        controller.syncNow()
        assertEquals(QueueSyncState.Syncing, controller.syncState.value)
        controller.syncNow()
        answer.complete(Unit)

        assertEquals(1, harness.fixture.adapter.sent.size)
        assertIs<QueueSyncState.Done>(controller.syncState.value)
    }

    @Test
    fun sync_now_while_offline_says_so_and_keeps_every_watch() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness()
        harness.fixture.outbox.enqueue(watch(media = movieA))
        harness.fixture.adapter.account = null
        harness.online = false
        val controller = TestScopeController(harness, backgroundScope)

        controller.syncNow()

        val result = assertIs<QueueSyncState.Done>(controller.syncState.value).result
        assertTrue(result.offline)
        assertEquals(1, result.stillWaiting)
        assertEquals(0, result.sent)
        assertTrue(harness.fixture.adapter.sent.isEmpty())
        assertEquals(1, controller.counts.value.waiting)
    }

    @Test
    fun sync_now_does_not_skip_a_backoff_and_says_when_the_next_attempt_is_due() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness()
        harness.fixture.outbox.enqueue(watch(media = movieA))
        harness.fixture.adapter.outcomes += OutboxSendOutcome.Retry(retryAfterMs = null, maybeDelivered = false, reason = "HTTP 503")
        val controller = TestScopeController(harness, backgroundScope)

        controller.syncNow()
        val first = assertIs<QueueSyncState.Done>(controller.syncState.value).result
        assertEquals(1, first.stillWaiting)
        assertTrue((first.retryInMs ?: 0L) > 0L, "the next attempt time was not reported")

        controller.syncNow()

        assertEquals(1, harness.fixture.adapter.sent.size, "a second press bypassed the backoff")
        assertEquals(1, assertIs<QueueSyncState.Done>(controller.syncState.value).result.stillWaiting)
    }

    @Test
    fun a_flush_that_fails_leaves_the_queue_as_it_was_and_the_screen_alive() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness()
        harness.fixture.outbox.enqueue(watch(media = movieA))
        val controller = OutboxQueueController(
            outbox = harness.fixture.outbox,
            scope = backgroundScope,
            identity = harness.identity,
            isOnline = { true },
            nowEpochMs = { harness.fixture.now },
            flush = { error("disk full") },
        )

        controller.syncNow()

        assertEquals(1, assertIs<QueueSyncState.Done>(controller.syncState.value).result.stillWaiting)
        controller.syncNow()
        assertIs<QueueSyncState.Done>(controller.syncState.value)
    }

    @Test
    fun retry_and_dismiss_act_on_the_identity_on_screen_and_the_counts_follow() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness()
        harness.fixture.outbox.enqueue(watch(media = movieA))
        harness.fixture.adapter.outcomes += OutboxSendOutcome.Rejected("HTTP 404")
        harness.fixture.outbox.flush(profileId = 1)
        val controller = TestScopeController(harness, backgroundScope)
        assertEquals(OutboxCounts(undeliverable = 1, retryable = 1), controller.counts.value)

        controller.retryUndeliverable()

        assertEquals(2, harness.fixture.adapter.sent.size)
        assertEquals(OutboxCounts(), controller.counts.value)

        harness.fixture.outbox.enqueue(watch(media = movieB))
        harness.fixture.adapter.outcomes += OutboxSendOutcome.Rejected("HTTP 404")
        harness.fixture.outbox.flush(profileId = 1)
        assertEquals(1, controller.counts.value.undeliverable)
        controller.dismissUndeliverable()
        assertEquals(OutboxCounts(), controller.counts.value)
    }

    @Test
    fun the_dropped_count_is_dismissed_through_the_controller() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness()
        harness.fixture.outbox.enqueue(watch(media = movieA))
        harness.fixture.now = TestStartEpochMs + TrackerOutbox.MaxAgeMs + 1L
        harness.fixture.outbox.flush(profileId = 1)
        val controller = TestScopeController(harness, backgroundScope)
        assertEquals(1, controller.counts.value.droppedForAge)

        controller.dismissDropped()

        assertEquals(0, controller.counts.value.droppedForAge)
    }

    @Test
    fun the_counts_follow_a_change_of_account() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness()
        harness.fixture.outbox.enqueue(watch(media = movieA))
        val controller = TestScopeController(harness, backgroundScope)
        assertEquals(1, controller.counts.value.waiting)

        harness.identity.value = QueueIdentity(profileId = 1, accountKey = "account-b")
        assertEquals(0, controller.counts.value.waiting)

        harness.identity.value = QueueIdentity(profileId = 1, accountKey = null)
        harness.fixture.adapter.account = null
        assertEquals(0, controller.counts.value.waiting)
        controller.syncNow()
        assertEquals(0, assertIs<QueueSyncState.Done>(controller.syncState.value).result.sent)
        assertEquals(1, harness.fixture.outbox.counts(1, "account-a").waiting, "the watches of the disconnected account were touched")
    }

    // The notice

    @Test
    fun the_notice_is_shown_the_first_time_only() {
        var shown = 0
        val notice = QueuedWatchNotice { shown++ }

        notice.onQueued()
        notice.onQueued()
        notice.onQueued()

        assertEquals(1, shown)
    }

    // The disconnect decision

    @Test
    fun disconnecting_asks_for_a_choice_only_when_watches_are_waiting() {
        assertEquals(TrackingDisconnectPrompt.Confirm, trackingDisconnectPrompt(0))
        assertEquals(TrackingDisconnectPrompt.PendingWatches(1), trackingDisconnectPrompt(1))
        assertEquals(TrackingDisconnectPrompt.PendingWatches(7), trackingDisconnectPrompt(7))
        assertEquals(TrackingDisconnectPrompt.Confirm, trackingDisconnectPrompt(-1))
    }
}

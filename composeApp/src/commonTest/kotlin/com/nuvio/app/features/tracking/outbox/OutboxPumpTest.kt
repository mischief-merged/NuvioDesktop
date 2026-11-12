package com.nuvio.app.features.tracking.outbox

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxPumpTest {
    private fun TestScope.pump(flush: suspend () -> Unit, nextWakeAtEpochMs: () -> Long?) = OutboxPump(
        scope = backgroundScope,
        nowEpochMs = { testScheduler.currentTime },
        flush = flush,
        nextWakeAtEpochMs = nextWakeAtEpochMs,
    )

    @Test
    fun it_flushes_on_start_and_then_sleeps_until_the_next_entry_is_due() = runTest {
        var flushes = 0
        val pump = pump(flush = { flushes++ }, nextWakeAtEpochMs = { if (flushes == 1) 600_000L else null })

        pump.start()
        pump.start()
        runCurrent()
        assertEquals(1, flushes)

        advanceTimeBy(599_999L)
        assertEquals(1, flushes, "the pump flushed while nothing was due")

        advanceTimeBy(2L)
        assertEquals(2, flushes)

        advanceTimeBy(3_600_000L)
        assertEquals(2, flushes, "the pump kept running with an empty queue")
    }

    @Test
    fun a_wake_up_flushes_at_once_and_several_wake_ups_are_merged() = runTest {
        var flushes = 0
        val pump = pump(flush = { flushes++ }, nextWakeAtEpochMs = { null })
        pump.start()
        runCurrent()

        repeat(20) { pump.wake() }
        runCurrent()

        assertTrue(flushes in 2..3, "20 wake-ups caused ${flushes - 1} flushes")
    }

    @Test
    fun an_entry_that_stays_due_does_not_turn_the_pump_into_a_busy_loop() = runTest {
        var flushes = 0
        val pump = pump(flush = { flushes++ }, nextWakeAtEpochMs = { testScheduler.currentTime - 1L })

        pump.start()
        advanceTimeBy(60_000L)

        assertTrue(flushes <= 13, "flushed $flushes times in one minute")
    }

    @Test
    fun a_failing_flush_is_retried_later_instead_of_stopping_or_spinning() = runTest {
        var flushes = 0
        val pump = pump(
            flush = {
                flushes++
                if (flushes == 1) error("disk full")
            },
            nextWakeAtEpochMs = { null },
        )

        pump.start()
        advanceTimeBy(OutboxPump.FailureRetryDelayMs - 1L)
        assertEquals(1, flushes)

        advanceTimeBy(2L)
        assertEquals(2, flushes)
    }

    @Test
    fun a_watch_waiting_for_its_history_check_is_not_asked_about_again_until_its_backoff_ends() = runTest {
        val fixture = OutboxFixture(now = 0L)
        val outbox = TrackerOutbox(
            store = fixture.store,
            adapter = fixture.adapter,
            nowEpochMs = { testScheduler.currentTime },
            sleep = { kotlinx.coroutines.delay(it) },
            random = { 0.5 },
        )
        outbox.enqueue(watch(media = movieA, watchedAtEpochMs = 1_000L, maybeDelivered = true))
        outbox.enqueue(watch(media = movieB, watchedAtEpochMs = 1_000L, notBeforeEpochMs = TrackerOutbox.SettleDelayMs))
        outbox.enqueue(watch(media = movieB, watchedAtEpochMs = 1_000L, profileId = 2))
        fixture.adapter.recorded = { null }
        val pump = pump(flush = { outbox.flush(profileId = 1) }, nextWakeAtEpochMs = { outbox.nextWakeAtEpochMs(profileId = 1) })

        pump.start()
        advanceTimeBy(TrackerOutbox.SettleDelayMs + 29_999L)
        assertEquals(1, fixture.adapter.checked.size, "the paused head of the queue was polled")
        assertTrue(fixture.adapter.sent.isEmpty(), "a watch behind the paused head was sent although the tracker is failing")

        fixture.adapter.recorded = { false }
        advanceTimeBy(2L)
        assertEquals(2, fixture.adapter.checked.size)
        advanceTimeBy(10_000L)
        assertEquals(listOf(movieA, movieB), fixture.adapter.sent.flatten().map { it.media })
        assertEquals(listOf(2), outbox.entries().map { it.profileId })
    }
}

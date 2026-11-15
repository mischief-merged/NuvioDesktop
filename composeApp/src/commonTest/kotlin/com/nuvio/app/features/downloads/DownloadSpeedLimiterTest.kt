package com.nuvio.app.features.downloads

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSpeedLimiterTest {
    @Test
    fun unlimited_reads_do_not_consult_the_clock_or_sleeper() = runTest {
        val limiter = DownloadSpeedLimiter(
            nanoTime = { error("Unlimited reads must not read the clock") },
            sleep = { error("Unlimited reads must not sleep") },
        )
        assertNull(limiter.bytesPerSecond)
        limiter.throttle(Int.MAX_VALUE)
        limiter.bytesPerSecond = -1L
        assertNull(limiter.bytesPerSecond)
        limiter.throttle(1)
        limiter.bytesPerSecond = 0L
        assertNull(limiter.bytesPerSecond)
    }

    @Test
    fun concurrent_readers_share_one_budget() = runTest {
        val limiter = DownloadSpeedLimiter({ testScheduler.currentTime * 1_000_000L }) { delay(it) }
        limiter.bytesPerSecond = 1000L
        val finished = mutableListOf<Long>()
        val jobs = List(3) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                limiter.throttle(1000)
                finished += testScheduler.currentTime
            }
        }
        jobs.forEach { it.join() }
        // Whoever holds the limiter pays off every byte reported so far, so nobody reads on early.
        assertEquals(listOf(3000L, 3000L, 3000L), finished)
    }

    @Test
    fun idle_time_does_not_bank_a_burst() = runTest {
        val limiter = DownloadSpeedLimiter({ testScheduler.currentTime * 1_000_000L }) { delay(it) }
        limiter.bytesPerSecond = 1000L
        limiter.throttle(1000)
        delay(60_000)
        limiter.throttle(1000)
        assertEquals(62_000L, testScheduler.currentTime)
    }

    @Test
    fun small_reads_retain_fractional_millisecond_debt() = runTest {
        val limiter = DownloadSpeedLimiter({ testScheduler.currentTime * 1_000_000L }) { delay(it) }
        limiter.bytesPerSecond = 1_000_000L
        repeat(10_000) { limiter.throttle(1) }
        assertEquals(10L, testScheduler.currentTime)
    }

    @Test
    fun empty_reads_do_not_consume_bandwidth() = runTest {
        val limiter = DownloadSpeedLimiter({ error("Empty read") }) { error("Empty read") }
        limiter.bytesPerSecond = 1L
        limiter.throttle(0)
        limiter.throttle(-1)
    }

    @Test
    fun rate_changes_apply_during_an_existing_wait() = runTest {
        val requested = mutableListOf<Long>()
        lateinit var limiter: DownloadSpeedLimiter
        limiter = DownloadSpeedLimiter({ testScheduler.currentTime * 1_000_000L }) {
            requested += it
            delay(it)
            if (requested.size == 1) limiter.bytesPerSecond = 2000L
        }
        limiter.bytesPerSecond = 1000L
        limiter.throttle(1000)
        assertEquals(525L, testScheduler.currentTime)
        assertTrue(requested.all { it in 1L..50L })
    }

    @Test
    fun lowering_the_rate_reprices_the_remaining_bytes() = runTest {
        var firstWait = true
        lateinit var limiter: DownloadSpeedLimiter
        limiter = DownloadSpeedLimiter({ testScheduler.currentTime * 1_000_000L }) {
            delay(it)
            if (firstWait) {
                firstWait = false
                limiter.bytesPerSecond = 500L
            }
        }
        limiter.bytesPerSecond = 1000L
        limiter.throttle(1000)
        assertEquals(1950L, testScheduler.currentTime)
    }

    @Test
    fun switching_to_unlimited_releases_an_existing_wait() = runTest {
        lateinit var limiter: DownloadSpeedLimiter
        limiter = DownloadSpeedLimiter({ testScheduler.currentTime * 1_000_000L }) {
            delay(it)
            limiter.bytesPerSecond = null
        }
        limiter.bytesPerSecond = 1L
        limiter.throttle(Int.MAX_VALUE)
        assertEquals(50L, testScheduler.currentTime)
        limiter.throttle(Int.MAX_VALUE)
        assertEquals(50L, testScheduler.currentTime)
    }

    @Test
    fun pausing_during_a_throttle_wait_does_not_forgive_the_bytes_already_transferred() = runTest {
        val limiter = DownloadSpeedLimiter({ testScheduler.currentTime * 1_000_000L }) { delay(it) }
        limiter.bytesPerSecond = 1000L
        val reader = launch { limiter.throttle(1000) }
        delay(120)
        reader.cancelAndJoin()
        limiter.throttle(1000)
        assertEquals(2000L, testScheduler.currentTime)
    }

    @Test
    fun pausing_and_resuming_repeatedly_cannot_outrun_the_limit() = runTest {
        val limiter = DownloadSpeedLimiter({ testScheduler.currentTime * 1_000_000L }) { delay(it) }
        limiter.bytesPerSecond = 1000L
        repeat(5) {
            val reader = launch { limiter.throttle(1000) }
            delay(10)
            reader.cancelAndJoin()
        }
        limiter.throttle(1000)
        assertEquals(6000L, testScheduler.currentTime)
    }

    @Test
    fun a_reader_cancelled_while_queued_still_has_its_bytes_counted() = runTest {
        val limiter = DownloadSpeedLimiter({ testScheduler.currentTime * 1_000_000L }) { delay(it) }
        limiter.bytesPerSecond = 1000L
        val first = launch(start = CoroutineStart.UNDISPATCHED) { limiter.throttle(1000) }
        val queued = launch(start = CoroutineStart.UNDISPATCHED) { limiter.throttle(1000) }
        queued.cancelAndJoin()
        first.join()
        assertEquals(2000L, testScheduler.currentTime)
    }

    @Test
    fun debt_left_by_a_paused_reader_is_paid_off_by_elapsed_time() = runTest {
        val limiter = DownloadSpeedLimiter({ testScheduler.currentTime * 1_000_000L }) { delay(it) }
        limiter.bytesPerSecond = 1000L
        val reader = launch { limiter.throttle(1000) }
        delay(120)
        reader.cancelAndJoin()
        delay(5000)
        limiter.throttle(100)
        assertEquals(5220L, testScheduler.currentTime)
    }

    @Test
    fun maximum_read_sizes_do_not_overflow_the_delay_calculation() = runTest {
        var now = 0L
        var slept = 0L
        val limiter = DownloadSpeedLimiter({ now }) {
            slept += it
            now += it * 1_000_000L
        }
        limiter.bytesPerSecond = Int.MAX_VALUE.toLong()
        limiter.throttle(Int.MAX_VALUE)
        assertEquals(1000L, slept)
    }
}

package com.nuvio.app.features.downloads

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadRetriesTest {
    private var offline = false
    private var connectivityChecks = 0

    private fun retries() = DownloadRetries(
        isOffline = {
            connectivityChecks++
            offline
        },
        wait = { delay(it) },
    )

    /** How many attempts are allowed before the download is given up, at most [limit]. */
    private suspend fun DownloadRetries.attemptsUntilGivenUp(limit: Int = 100): Int {
        repeat(limit) { attempt -> if (!awaitNextAttempt(madeProgress = false)) return attempt }
        return limit
    }

    @Test
    fun a_source_that_keeps_failing_while_the_internet_is_there_is_given_up_after_ten_attempts() = runTest {
        assertEquals(10, retries().attemptsUntilGivenUp())
    }

    @Test
    fun the_wait_between_attempts_doubles_up_to_thirty_seconds() = runTest {
        val retries = retries()
        val waits = (1..7).map {
            val before = currentTime
            retries.awaitNextAttempt(madeProgress = false)
            currentTime - before
        }

        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), waits)
    }

    @Test
    fun an_attempt_that_received_something_gives_the_source_its_attempts_back() = runTest {
        val retries = retries()
        repeat(9) { retries.awaitNextAttempt(madeProgress = false) }

        retries.awaitNextAttempt(madeProgress = true)

        assertEquals(9, retries.attemptsUntilGivenUp())
    }

    @Test
    fun a_download_is_not_given_up_while_the_internet_cannot_be_reached() = runTest {
        offline = true

        assertEquals(500, retries().attemptsUntilGivenUp(limit = 500))
    }

    @Test
    fun while_the_internet_is_gone_the_download_is_tried_again_every_thirty_seconds() = runTest {
        offline = true
        val retries = retries()
        repeat(10) { retries.awaitNextAttempt(madeProgress = false) }
        val before = currentTime

        repeat(4) { retries.awaitNextAttempt(madeProgress = false) }

        assertEquals(120_000L, currentTime - before)
    }

    @Test
    fun the_internet_is_only_asked_about_once_the_source_has_used_its_attempts() = runTest {
        val retries = retries()

        repeat(10) { retries.awaitNextAttempt(madeProgress = false) }

        assertEquals(0, connectivityChecks)
    }

    @Test
    fun once_the_internet_is_back_the_source_gets_its_attempts_anew_and_is_then_given_up_if_it_still_fails() = runTest {
        offline = true
        val retries = retries()
        repeat(40) { retries.awaitNextAttempt(madeProgress = false) }

        offline = false

        assertEquals(10, retries.attemptsUntilGivenUp())
    }
}

package com.nuvio.app.features.player.skip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipLookupReportTest {
    @Test
    fun a_request_that_got_no_answer_is_reported_from_a_child_coroutine() = runBlocking {
        val report = SkipLookupReport()
        withContext(report) {
            coroutineScope { async { reportSkipLookupFailure(IOException("no route")) }.await() }
            assertTrue(skipLookupHadUnansweredRequest())
        }
        assertTrue(report.hadUnansweredRequest)
    }

    @Test
    fun a_service_that_answered_with_an_error_status_or_unreadable_text_is_not_a_failure() = runBlocking {
        val report = SkipLookupReport()
        withContext(report) {
            reportSkipLookupFailure(IllegalStateException("HTTP 404"))
            reportSkipLookupFailure(IllegalArgumentException("not json"))
        }
        assertFalse(report.hadUnansweredRequest)
    }

    @Test
    fun without_a_listener_nothing_is_recorded_and_playback_lookups_are_unchanged() = runBlocking {
        reportSkipLookupFailure(IOException("no route"))
        assertFalse(skipLookupHadUnansweredRequest())
    }

    @Test
    fun cancellation_is_passed_on() {
        assertFailsWith<CancellationException> { runBlocking { reportSkipLookupFailure(CancellationException("stop")) } }
    }

    @Test
    fun a_report_belongs_to_one_lookup() = runBlocking {
        val first = SkipLookupReport()
        val second = SkipLookupReport()
        withContext(first) { reportSkipLookupFailure(IOException("x")) }
        withContext(second) { }
        assertEquals(listOf(true, false), listOf(first.hadUnansweredRequest, second.hadUnansweredRequest))
    }
}

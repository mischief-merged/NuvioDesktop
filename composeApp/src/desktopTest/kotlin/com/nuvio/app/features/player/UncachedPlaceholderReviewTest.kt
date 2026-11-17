package com.nuvio.app.features.player

import com.nuvio.app.features.downloads.PlaceholderEvidence
import com.nuvio.app.features.downloads.PlaceholderVerdict
import com.nuvio.app.features.downloads.placeholderVerdict
import com.nuvio.app.features.streams.sourceLabelSaysNotCached
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A not-cached label never outweighs a length that matches the known running time. */
class NotCachedLabelAgainstRuntimeTest {
    private fun confirmed(durationMs: Long, runtime: Int?) =
        isPlaceholderConfirmed(suspect = true, durationMs = durationMs, expectedRuntimeMinutes = runtime, resumeMs = 0L)

    @Test
    fun a_real_three_minute_episode_from_a_source_labelled_not_cached_plays() {
        assertFalse(confirmed(3 * 60_000L, runtime = 3), "a full length file was skipped")
        assertFalse(confirmed(150_000L, runtime = 3), "two and a half minutes of a title listed at three")
        assertTrue(confirmed(8_000L, runtime = 3), "the notice is still a notice")
        assertTrue(confirmed(60_000L, runtime = 3), "a third of the running time does not match it")
        assertTrue(confirmed(3 * 60_000L, runtime = null), "without a running time the label still decides")
    }

    @Test
    fun a_downloaded_three_minute_episode_from_such_a_source_is_not_flagged() {
        val verdict = placeholderVerdict(
            PlaceholderEvidence(fileBytes = 120_000_000L, claimedBytes = null, durationMs = 3 * 60_000L, runtimeMinutes = 3, sourceSaysNotCached = true),
        )
        assertFalse(verdict is PlaceholderVerdict.NotTheRealVideo, "flagged: $verdict")
        val notice = placeholderVerdict(
            PlaceholderEvidence(fileBytes = 400_000L, claimedBytes = null, durationMs = 8_000L, runtimeMinutes = 3, sourceSaysNotCached = true),
        )
        assertTrue(notice is PlaceholderVerdict.NotTheRealVideo)
    }
}

/** An explicit negation beside the word is not a not-cached marker. */
class NotCachedNegationTest {
    @Test
    fun negated_status_text_does_not_match() {
        listOf(
            "Provider (uncached: false)",
            "Provider uncached=no",
            "Provider [uncached: 0]",
            "Provider uncached = off",
            "Provider not uncached",
            "Provider not cached: false",
        ).forEach { assertFalse(sourceLabelSaysNotCached(it), it) }
    }

    @Test
    fun plain_and_affirmed_status_text_still_matches() {
        listOf("Provider (uncached)", "Provider uncached: true", "Provider uncached=yes", "Provider not cached").forEach {
            assertTrue(sourceLabelSaysNotCached(it), it)
        }
    }
}

package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaceholderVerdictTest {
    private val mb = 1_000_000L
    private val gb = 1_000_000_000L
    private val minute = 60_000L

    private fun verdict(
        file: Long? = null,
        claim: Long? = null,
        duration: Long? = null,
        runtime: Int? = null,
        frames: DownloadedFramesProblem? = null,
    ) = placeholderVerdict(PlaceholderEvidence(file, claim, duration, runtime, frames))

    private fun flagged(v: PlaceholderVerdict) = v is PlaceholderVerdict.NotTheRealVideo

    // The real case

    @Test
    fun the_real_case_is_not_the_real_video_by_size_alone() {
        // 8,509,628 bytes arrived; the source's text said 9.53 GB; 300.9 seconds; the film runs about 2 hours.
        val v = verdict(file = 8_509_628L, claim = 9_530_000_000L)
        val reason = assertIs<PlaceholderReason.SizeBelowClaim>(assertIs<PlaceholderVerdict.NotTheRealVideo>(v).reason)
        assertEquals(8_509_628L, reason.fileBytes)
        assertEquals(9_530_000_000L, reason.claimedBytes)
    }

    @Test
    fun the_real_case_with_everything_known_is_flagged_for_its_size() {
        assertTrue(flagged(verdict(file = 8_509_628L, claim = 9_530_000_000L, duration = 300_900L, runtime = 120)))
    }

    // Bitrate against the running time: no claim needed

    @Test
    fun the_real_file_against_a_film_of_two_hours_is_far_too_small_with_no_claim_at_all() {
        val v = verdict(file = 8_509_628L, runtime = 120)
        val reason = assertIs<PlaceholderReason.TooSmallForRuntime>(assertIs<PlaceholderVerdict.NotTheRealVideo>(v).reason)
        assertEquals(8_509_628L, reason.fileBytes)
        assertEquals(120, reason.runtimeMinutes)
        // About 9 kbit/s.
        assertTrue(8_509_628L * 8 / (120 * 60) in 9_000L..10_000L)
    }

    @Test
    fun the_bitrate_floor_is_strict() {
        // 100 kbit/s over 100 minutes is 75,000,000 bytes.
        assertFalse(flagged(verdict(file = 75_000_000L, runtime = 100)))
        assertTrue(flagged(verdict(file = 74_999_999L, runtime = 100)))
        assertTrue(isTooSmallForRuntime(8_509_628L, 120))
        assertFalse(isTooSmallForRuntime(8_509_628L, null))
        assertFalse(isTooSmallForRuntime(8_509_628L, 0))
        assertFalse(isTooSmallForRuntime(null, 120))
    }

    @Test
    fun an_unknown_runtime_never_flags_by_bitrate() {
        assertFalse(flagged(verdict(file = 8_509_628L, runtime = null)))
        assertFalse(flagged(verdict(file = 1L, runtime = 0)))
    }

    @Test
    fun a_genuinely_short_title_is_not_flagged_by_bitrate() {
        // An 8.5 MB trailer of three minutes is about 380 kbit/s; a 12 minute short of 8.5 MB is 94 kbit/s... but 20 MB is fine.
        assertFalse(flagged(verdict(file = 8_509_628L, runtime = 3)))
        assertFalse(flagged(verdict(file = 20 * mb, runtime = 12)))
    }

    @Test
    fun a_very_low_quality_or_audio_only_file_above_the_floor_is_not_flagged() {
        // 128 kbit/s audio for a film of two hours is 115 MB; 150 kbit/s video is 135 MB.
        assertFalse(flagged(verdict(file = 115 * mb, runtime = 120)))
        assertFalse(flagged(verdict(file = 135 * mb, runtime = 120)))
    }

    // Size

    @Test
    fun a_size_just_under_a_tenth_of_the_claim_is_flagged_and_a_tenth_is_not() {
        assertTrue(flagged(verdict(file = 999_999_999L, claim = 10 * gb)))
        assertFalse(flagged(verdict(file = 1_000_000_000L, claim = 10 * gb)))
    }

    @Test
    fun a_claim_below_two_hundred_megabytes_does_not_decide() {
        assertFalse(flagged(verdict(file = 1 * mb, claim = 199 * mb)))
        assertTrue(flagged(verdict(file = 1 * mb, claim = 200 * mb)))
    }

    @Test
    fun a_small_file_of_low_quality_is_not_flagged_when_it_is_in_line_with_its_claim() {
        // A 700 MB release that is 650 MB.
        assertFalse(flagged(verdict(file = 650 * mb, claim = 700 * mb, duration = 100 * minute, runtime = 100)))
        // A source whose claim was a little optimistic.
        assertFalse(flagged(verdict(file = 4 * gb, claim = 9 * gb)))
        assertFalse(flagged(verdict(file = 1 * gb, claim = 9 * gb)))
    }

    @Test
    fun a_file_larger_than_its_claim_is_not_flagged() {
        assertFalse(flagged(verdict(file = 12 * gb, claim = 9 * gb)))
    }

    // Running time

    @Test
    fun a_short_run_alone_is_one_weak_signal_and_is_not_enough() {
        // A file big enough for its runtime, so that only the short run speaks.
        val v = verdict(file = 200 * mb, claim = null, duration = 300_900L, runtime = 120)
        assertEquals(PlaceholderVerdict.CouldNotCheck, v)
    }

    @Test
    fun a_short_run_and_a_still_picture_together_are_flagged() {
        val v = verdict(duration = 300_900L, runtime = 120, frames = DownloadedFramesProblem.StillPicture)
        val reason = assertIs<PlaceholderReason.ShortForRuntime>(assertIs<PlaceholderVerdict.NotTheRealVideo>(v).reason)
        assertEquals(300_900L, reason.durationMs)
        assertEquals(120, reason.runtimeMinutes)
        assertTrue(flagged(verdict(duration = 300_900L, runtime = 120, frames = DownloadedFramesProblem.Blank)))
    }

    @Test
    fun a_still_picture_alone_is_not_enough() {
        assertEquals(PlaceholderVerdict.CouldNotCheck, verdict(frames = DownloadedFramesProblem.StillPicture))
        assertEquals(PlaceholderVerdict.CouldNotCheck, verdict(frames = DownloadedFramesProblem.Blank))
    }

    @Test
    fun a_still_picture_on_a_file_of_full_length_is_not_flagged() {
        assertFalse(flagged(verdict(duration = 100 * minute, runtime = 100, frames = DownloadedFramesProblem.StillPicture)))
    }

    @Test
    fun a_short_film_is_not_flagged_because_its_own_runtime_is_short() {
        // A 12 minute short with a runtime of 12, as a still picture: not under 20% of its runtime.
        assertFalse(flagged(verdict(duration = 12 * minute, runtime = 12, frames = DownloadedFramesProblem.StillPicture)))
        assertFalse(flagged(verdict(duration = 8 * minute, runtime = 9, frames = DownloadedFramesProblem.StillPicture)))
    }

    @Test
    fun a_trailer_length_extra_with_its_own_runtime_is_not_flagged() {
        assertFalse(flagged(verdict(file = 40 * mb, claim = 45 * mb, duration = 3 * minute, runtime = 3, frames = DownloadedFramesProblem.StillPicture)))
    }

    @Test
    fun a_short_episode_of_a_short_show_is_not_flagged() {
        assertFalse(flagged(verdict(duration = 4 * minute, runtime = 5, frames = DownloadedFramesProblem.StillPicture)))
        assertFalse(flagged(verdict(duration = 9 * minute, runtime = 11, frames = DownloadedFramesProblem.Blank)))
    }

    @Test
    fun a_cartoon_of_eleven_minutes_that_runs_eleven_minutes_is_in_line() {
        assertEquals(PlaceholderVerdict.LooksRight, verdict(duration = 11 * minute, runtime = 11))
    }

    @Test
    fun the_running_time_boundaries_are_strict() {
        // Exactly ten minutes against a film of two hours is not under ten minutes; one millisecond less is.
        assertFalse(flagged(verdict(duration = 10 * minute, runtime = 120, frames = DownloadedFramesProblem.StillPicture)))
        assertTrue(flagged(verdict(duration = 10 * minute - 1, runtime = 120, frames = DownloadedFramesProblem.StillPicture)))
        // Exactly a fifth of ten minutes (two minutes) is not under a fifth.
        assertFalse(flagged(verdict(duration = 2 * minute, runtime = 10, frames = DownloadedFramesProblem.StillPicture)))
        assertTrue(flagged(verdict(duration = 2 * minute - 1, runtime = 10, frames = DownloadedFramesProblem.StillPicture)))
    }

    // Missing evidence

    @Test
    fun with_nothing_to_compare_it_could_not_be_checked() {
        assertEquals(PlaceholderVerdict.CouldNotCheck, verdict())
        assertEquals(PlaceholderVerdict.CouldNotCheck, verdict(file = 8 * mb))
        // The title's running time without the length of the file, and the other way round.
        assertEquals(PlaceholderVerdict.CouldNotCheck, verdict(runtime = 120))
        assertEquals(PlaceholderVerdict.CouldNotCheck, verdict(file = 8 * mb, duration = 5 * minute))
    }

    @Test
    fun an_unknown_or_zero_runtime_never_flags() {
        assertFalse(flagged(verdict(duration = 30_000L, runtime = null, frames = DownloadedFramesProblem.StillPicture)))
        assertFalse(flagged(verdict(duration = 30_000L, runtime = 0, frames = DownloadedFramesProblem.StillPicture)))
    }

    @Test
    fun a_claim_that_is_too_small_to_judge_by_is_not_evidence_either() {
        assertEquals(PlaceholderVerdict.CouldNotCheck, verdict(file = 1 * mb, claim = 100 * mb))
    }

    @Test
    fun a_size_in_line_with_a_real_claim_is_a_comparison_that_passed() {
        assertEquals(PlaceholderVerdict.LooksRight, verdict(file = 9 * gb, claim = 9_530_000_000L))
    }

    // Announced size at the start of a transfer

    @Test
    fun an_announced_total_far_below_a_big_claim_is_caught_at_the_start() {
        assertTrue(announcedSizeIsFarBelowClaim(8_509_628L, 9_530_000_000L))
        assertFalse(announcedSizeIsFarBelowClaim(null, 9_530_000_000L))
        assertFalse(announcedSizeIsFarBelowClaim(0L, 9_530_000_000L))
        assertFalse(announcedSizeIsFarBelowClaim(8_509_628L, null))
        assertFalse(announcedSizeIsFarBelowClaim(8_509_628L, 150 * mb))
        assertFalse(announcedSizeIsFarBelowClaim(5 * gb, 9_530_000_000L))
    }

    // Words

    @Test
    fun the_length_of_a_run_is_written_in_a_few_plain_words() {
        assertEquals("2 hours", formatRunLength(120))
        assertEquals("1.5 hours", formatRunLength(90))
        assertEquals("1.7 hours", formatRunLength(100))
        assertEquals("45 minutes", formatRunLength(45))
        assertEquals("1 minute", formatRunLength(1))
        assertEquals("5 minutes", formatFileLength(300_900L))
        assertEquals("45 seconds", formatFileLength(45_000L))
        assertEquals("1 second", formatFileLength(1_000L))
    }

    @Test
    fun a_complete_sixty_minute_video_of_forty_megabytes_is_not_flagged() {
        // About 89 kbit/s, but its own length matches the title.
        assertFalse(flagged(verdict(file = 40 * mb, duration = 60 * minute, runtime = 60)))
        assertFalse(flagged(verdict(file = 1 * mb, duration = 60 * minute, runtime = 60)))
    }

    @Test
    fun with_the_length_unknown_a_bitrate_between_the_floors_is_one_weak_signal() {
        assertEquals(PlaceholderVerdict.CouldNotCheck, verdict(file = 40 * mb, runtime = 60))
        assertTrue(flagged(verdict(file = 40 * mb, runtime = 60, frames = DownloadedFramesProblem.StillPicture)))
    }

    @Test
    fun the_real_case_is_decisive_by_both_conditions() {
        assertTrue(flagged(verdict(file = 8_509_628L, duration = 300_900L, runtime = 120)))
        assertTrue(flagged(verdict(file = 8_509_628L, runtime = 120)))
    }

    @Test
    fun the_floors_are_strict() {
        // 20 kbit/s over 100 minutes is 15,000,000 bytes: below it is absurd with no length known.
        assertTrue(flagged(verdict(file = 14_999_999L, runtime = 100)))
        assertFalse(flagged(verdict(file = 15_000_000L, runtime = 100)))
        // 100 kbit/s over 100 minutes is 75,000,000 bytes, judged with a length far short of the runtime.
        assertTrue(flagged(verdict(file = 74_999_999L, duration = 15 * minute, runtime = 100)))
        assertFalse(flagged(verdict(file = 75_000_000L, duration = 15 * minute, runtime = 100)))
        // A length of exactly a fifth of the runtime is not far short of it.
        assertFalse(flagged(verdict(file = 30 * mb, duration = 20 * minute, runtime = 100)))
        assertTrue(flagged(verdict(file = 30 * mb, duration = 20 * minute - 1, runtime = 100)))
    }

    @Test
    fun an_absurd_bitrate_does_not_flag_a_file_whose_length_matches_the_title() {
        assertFalse(flagged(verdict(file = 1 * mb, duration = 100 * minute, runtime = 100)))
    }
}

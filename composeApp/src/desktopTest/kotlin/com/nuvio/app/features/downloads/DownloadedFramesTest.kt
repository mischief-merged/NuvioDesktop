package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadedFramesTest {
    private val windows = FrameSamplePositions.map { it.second }

    /** A busy picture; a different [seed] gives a picture that differs clearly from the others. */
    private fun picture(seed: Int) = ByteArray(FramePixelCount) { ((it * 3 + seed * 40) % 256).toByte() }

    private fun flat(value: Int) = ByteArray(FramePixelCount) { value.toByte() }

    /** An end-credit frame: black with a few bright letters. Mean about 4, variance about 180. */
    private fun credits() = ByteArray(FramePixelCount) { (if (it % 51 == 0) 100 else 2).toByte() }

    private fun samples(title: String? = null, pixels: (index: Int, window: FrameWindow) -> ByteArray?) = FrameSamples(
        durationMs = 60_000,
        samples = FrameSamplePositions.mapIndexed { index, (fraction, window) ->
            FrameSample((60_000 * fraction).toLong(), window, pixels(index, window))
        },
        containerTitle = title,
    )

    private fun healthy(title: String? = null) = samples(title) { index, _ -> picture(index) }

    @Test
    fun there_are_three_samples_at_the_start_one_in_the_middle_and_three_at_the_end() {
        assertEquals(
            listOf(0.02, 0.06, 0.12, 0.50, 0.88, 0.94, 0.98),
            FrameSamplePositions.map { it.first },
        )
        assertEquals(
            listOf(FrameWindow.Start, FrameWindow.Start, FrameWindow.Start, FrameWindow.Middle, FrameWindow.End, FrameWindow.End, FrameWindow.End),
            windows,
        )
    }

    @Test
    fun frames_that_differ_and_have_content_are_healthy() {
        assertNull(healthy().problem())
    }

    @Test
    fun a_file_of_which_no_frame_decodes_is_undecodable() {
        assertEquals(DownloadedFramesProblem.Undecodable, samples { _, _ -> null }.problem())
    }

    @Test
    fun a_file_that_decodes_at_the_start_but_not_at_the_end_is_broken_at_the_end() {
        val result = samples { index, window -> if (window == FrameWindow.End) null else picture(index) }
        assertEquals(DownloadedFramesProblem.BrokenEnd, result.problem())
    }

    @Test
    fun one_frame_at_the_end_is_enough_for_the_end_to_count_as_decoded() {
        val result = samples { index, window -> if (window == FrameWindow.End && index != 6) null else picture(index) }
        assertNull(result.problem())
    }

    @Test
    fun a_file_that_decodes_at_the_end_but_not_at_the_start_is_broken_at_the_start() {
        val result = samples { index, window -> if (window == FrameWindow.Start) null else picture(index) }
        assertEquals(DownloadedFramesProblem.BrokenStart, result.problem())
    }

    @Test
    fun a_picture_of_the_wrong_size_is_not_a_decoded_frame() {
        val result = samples { _, _ -> ByteArray(10) }
        assertEquals(DownloadedFramesProblem.Undecodable, result.problem())
    }

    @Test
    fun a_file_whose_decoded_frames_are_all_flat_is_blank() {
        assertEquals(DownloadedFramesProblem.Blank, samples { index, _ -> flat(index) }.problem())
        assertEquals(DownloadedFramesProblem.Blank, samples { _, _ -> flat(0) }.problem())
    }

    @Test
    fun flat_credit_frames_at_the_end_alone_are_healthy() {
        val result = samples { index, window -> if (window == FrameWindow.End) flat(1) else picture(index) }
        assertNull(result.problem())
    }

    @Test
    fun end_credits_with_letters_on_black_are_healthy() {
        val result = samples { index, window -> if (window == FrameWindow.End) credits() else picture(index) }
        assertNull(result.problem())
    }

    @Test
    fun fewer_than_four_decoded_frames_are_not_judged_blank_or_still() {
        val three = FrameSamples(
            durationMs = 60_000,
            samples = listOf(
                FrameSample(1, FrameWindow.Start, flat(0)),
                FrameSample(2, FrameWindow.Start, flat(0)),
                FrameSample(3, FrameWindow.End, flat(0)),
            ),
        )
        assertNull(three.problem())
    }

    @Test
    fun the_same_picture_the_whole_time_is_a_still_picture() {
        assertEquals(DownloadedFramesProblem.StillPicture, samples { _, _ -> picture(1) }.problem())
    }

    @Test
    fun a_picture_with_a_little_noise_is_still_a_still_picture() {
        val result = samples { index, _ -> picture(1).also { it[index] = (it[index] + 1).toByte() } }
        assertEquals(DownloadedFramesProblem.StillPicture, result.problem())
    }

    @Test
    fun a_picture_that_changes_as_little_as_real_episodes_do_is_not_still() {
        // Real episodes differed by 28 at the least; here two frames differ by 30.
        fun stripes(first: Int) = ByteArray(FramePixelCount) { (if (it % 2 == first) 130 else 100).toByte() }
        val result = samples { index, _ -> stripes(index % 2) }
        assertNull(result.problem())
    }

    private fun another(title: String?, season: Int = 1, episode: Int = 1) =
        healthy(title).titleNamesAnotherEpisode(expectedSeason = season, expectedEpisode = episode)

    @Test
    fun a_title_with_the_marker_of_the_wanted_episode_does_not_name_another() {
        assertFalse(another("Pantheon.S01E01.1080p.WEBRip"))
        assertFalse(another("pantheon.s01e01"))
        assertFalse(another("Show S001E010", episode = 10))
    }

    @Test
    fun a_title_with_the_marker_of_another_episode_names_another() {
        assertTrue(another("Pantheon.S01E02.1080p"))
        assertTrue(another("Pantheon.S02E01.1080p"))
        assertTrue(another("show s1e3"))
    }

    @Test
    fun lists_and_ranges_of_episodes_name_every_episode_in_them() {
        assertFalse(another("Show.S01E01E02", episode = 2))
        assertFalse(another("Show.S01E01-E03", episode = 2))
        assertFalse(another("Show.S01E01-E03", episode = 3))
        assertFalse(another("Show.S01E05-06.1080p", episode = 6))
        assertFalse(another("Show.S01E01.S01E02", episode = 2))
        assertTrue(another("Show.S01E01-E03", episode = 4))
        assertTrue(another("Show.S01E01E02", episode = 3))
        // A resolution after the marker is not an episode.
        assertTrue(another("Show.S01E02-720p", episode = 720))
        assertTrue(another("Show.S01E02-1080p", episode = 108))
    }

    @Test
    fun a_title_without_a_marker_or_without_any_title_proves_nothing() {
        assertFalse(another("www.1TamilMV.capital"))
        assertFalse(another(null))
        assertFalse(another("Serie1E2x"))
        // Absolute numbering is not a marker.
        assertFalse(another("Show E1071"))
        assertFalse(another("Show S01E1071"))
    }

    @Test
    fun a_movie_has_no_episode_to_differ_from() {
        assertFalse(healthy("Pantheon.S01E02.1080p").titleNamesAnotherEpisode(expectedSeason = null, expectedEpisode = null))
    }

    private val blankSamples get() = samples { _, _ -> flat(0) }
    private val stillSamples get() = samples { _, _ -> picture(1) }
    private fun more(pixels: (Int) -> ByteArray?) = FrameSamples(60_000, ConfirmingFramePositions.mapIndexed { index, fraction ->
        FrameSample((60_000 * fraction).toLong(), FrameWindow.Middle, pixels(index))
    })

    @Test
    fun healthy_samples_need_no_more_frames() {
        var asked = false
        val verdict = confirmedFramesProblem(healthy()) { asked = true; null }

        assertNull(verdict.problem)
        assertTrue(verdict.conclusive)
        assertFalse(asked)
    }

    @Test
    fun a_damaged_file_is_rejected_on_the_first_samples_without_more_frames() {
        var asked = false
        val verdict = confirmedFramesProblem(cutOff()) { asked = true; null }

        assertEquals(DownloadedFramesProblem.BrokenEnd, verdict.problem)
        assertFalse(asked)
    }

    private fun cutOff() = samples { index, window -> if (window == FrameWindow.End) null else picture(index) }

    @Test
    fun a_blank_looking_file_is_rejected_only_when_more_frames_are_blank_too() {
        assertEquals(DownloadedFramesProblem.Blank, confirmedFramesProblem(blankSamples) { more { flat(0) } }.problem)
        assertEquals(DownloadedFramesProblem.Blank, confirmedFramesProblem(blankSamples) { more { flat(3) } }.problem)
    }

    @Test
    fun a_blank_looking_file_with_picture_in_the_more_frames_is_kept() {
        val verdict = confirmedFramesProblem(blankSamples) { more { index -> if (index == 2) picture(1) else flat(0) } }

        assertNull(verdict.problem)
        assertTrue(verdict.conclusive)
    }

    @Test
    fun a_still_looking_file_is_rejected_only_when_more_frames_show_the_same_picture() {
        assertEquals(DownloadedFramesProblem.StillPicture, confirmedFramesProblem(stillSamples) { more { picture(1) } }.problem)
        assertNull(confirmedFramesProblem(stillSamples) { more { index -> picture(index + 2) } }.problem)
    }

    @Test
    fun a_file_that_looks_blank_is_kept_but_not_conclusive_when_no_more_frames_can_be_taken() {
        val verdict = confirmedFramesProblem(blankSamples) { null }

        assertNull(verdict.problem)
        assertFalse(verdict.conclusive)
    }
}

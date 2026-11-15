package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonDownloadSelectionTest {
    private val runtimeMinutes = 50

    private fun source(label: String, megabitsPerSecond: Double? = null, direct: Boolean = true) = StreamItem(
        name = label,
        url = if (direct) "http://x/${label.replace(' ', '-')}.mkv" else null,
        addonName = "A",
        addonId = "addon:A",
        behaviorHints = StreamBehaviorHints(videoSize = megabitsPerSecond?.let { sizeFor(it, runtimeMinutes) }),
        clientResolve = if (direct) null else StreamClientResolve(type = "debrid", service = "realdebrid", isCached = true),
    )

    private fun sizeFor(megabitsPerSecond: Double, minutes: Int): Long =
        (megabitsPerSecond * 1_000_000 * minutes * 60 / 8).toLong()

    private fun ranked(
        sources: List<StreamItem>,
        quality: BatchQuality = BatchQuality.BestAvailable,
        maxBitrateMbps: Int? = null,
        audioLanguage: DebridStreamLanguage? = null,
        runtime: Int? = runtimeMinutes,
    ): List<String?> = rankBatchCandidates(sources, quality, maxBitrateMbps, audioLanguage, runtime).map { it.name }

    @Test
    fun the_bitrate_cap_skips_sources_above_it_and_keeps_one_exactly_at_the_limit() {
        val sources = listOf(
            source("1080p above", 10.01),
            source("1080p at limit", 10.0),
            source("1080p below", 6.0),
        )
        assertEquals(listOf("1080p at limit", "1080p below"), ranked(sources, maxBitrateMbps = 10))
        assertEquals(listOf("1080p below"), ranked(sources, maxBitrateMbps = 8))
        assertEquals(emptyList(), ranked(sources, maxBitrateMbps = 4))
    }

    @Test
    fun with_neutral_choices_the_order_does_not_depend_on_size_or_bitrate() {
        val sources = listOf(
            source("720p small", 2.0),
            source("1080p large resolve", 30.0, direct = false),
            source("1080p small direct", 4.0),
            source("2160p huge resolve", 80.0, direct = false),
            source("1080p large direct", 25.0),
        )
        val expected = listOf(
            "2160p huge resolve",
            "1080p small direct",
            "1080p large direct",
            "1080p large resolve",
            "720p small",
        )
        assertEquals(expected, ranked(sources))
        assertEquals(expected, rankBatchCandidates(sources, BatchQuality.BestAvailable).map { it.name })
    }

    @Test
    fun a_language_choice_alone_does_not_reorder_sources_by_bitrate() {
        val sources = listOf(source("1080p ENGLISH low", 4.0), source("1080p ENGLISH high", 40.0))
        assertEquals(
            listOf("1080p ENGLISH low", "1080p ENGLISH high"),
            ranked(sources, audioLanguage = DebridStreamLanguage.EN),
        )
    }

    @Test
    fun under_a_cap_the_sources_that_fit_are_tried_from_the_highest_bitrate_down() {
        val sources = listOf(source("1080p low", 4.0), source("1080p high", 9.0), source("1080p mid", 6.0))
        assertEquals(listOf("1080p high", "1080p mid", "1080p low"), ranked(sources, maxBitrateMbps = 10))
    }

    @Test
    fun a_source_of_unknown_size_is_kept_under_a_cap_and_tried_after_the_ones_known_to_fit() {
        val sources = listOf(source("2160p unknown size"), source("1080p fits", 8.0), source("1080p too big", 30.0))
        assertEquals(listOf("1080p fits", "2160p unknown size"), ranked(sources, maxBitrateMbps = 10))
    }

    @Test
    fun without_a_cap_a_source_of_unknown_size_keeps_its_place_by_resolution() {
        val sources = listOf(source("1080p known", 8.0), source("2160p unknown size"), source("1080p unknown size"))
        assertEquals(listOf("2160p unknown size", "1080p known", "1080p unknown size"), ranked(sources))
    }

    @Test
    fun an_episode_of_unknown_runtime_keeps_every_source_in_place_because_no_bitrate_can_be_computed() {
        val sources = listOf(source("1080p small", 4.0), source("1080p big", 40.0))

        assertEquals(listOf("1080p small"), ranked(sources, maxBitrateMbps = 4))
        assertEquals(listOf("1080p small", "1080p big"), ranked(sources, maxBitrateMbps = 4, runtime = null))
        assertEquals(listOf("1080p small", "1080p big"), ranked(sources, maxBitrateMbps = 4, runtime = 0))
    }

    @Test
    fun a_language_choice_skips_sources_tagged_only_with_other_languages() {
        val sources = listOf(
            source("1080p FRENCH"),
            source("1080p ENGLISH"),
            source("1080p ITA GER"),
        )
        assertEquals(listOf("1080p ENGLISH"), ranked(sources, audioLanguage = DebridStreamLanguage.EN))
        assertEquals(listOf("1080p FRENCH"), ranked(sources, audioLanguage = DebridStreamLanguage.FR))
    }

    @Test
    fun untagged_and_multi_language_sources_are_kept_after_ones_tagged_with_the_chosen_language() {
        val sources = listOf(
            source("1080p WEB-DL"),
            source("1080p MULTI"),
            source("1080p ITA ENG"),
            source("1080p ENGLISH"),
        )
        assertEquals(
            listOf("1080p ITA ENG", "1080p ENGLISH", "1080p WEB-DL", "1080p MULTI"),
            ranked(sources, audioLanguage = DebridStreamLanguage.EN),
        )
    }

    @Test
    fun any_language_keeps_every_source_in_place() {
        val sources = listOf(source("1080p FRENCH"), source("1080p WEB-DL"), source("1080p ENGLISH"))
        assertEquals(listOf("1080p FRENCH", "1080p WEB-DL", "1080p ENGLISH"), ranked(sources))
    }

    @Test
    fun a_language_match_outranks_bitrate_and_bitrate_outranks_a_ready_direct_link() {
        val sources = listOf(
            source("1080p untagged high", 9.0),
            source("1080p ENGLISH low direct", 4.0),
            source("1080p ENGLISH high resolve", 8.0, direct = false),
            source("1080p ENGLISH high direct", 8.0),
        )
        assertEquals(
            listOf(
                "1080p ENGLISH high direct",
                "1080p ENGLISH high resolve",
                "1080p ENGLISH low direct",
                "1080p untagged high",
            ),
            ranked(sources, maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN),
        )
    }

    @Test
    fun a_chosen_resolution_is_applied_together_with_the_cap_and_the_language() {
        val sources = listOf(
            source("2160p ENGLISH", 8.0),
            source("1080p ENGLISH", 25.0),
            source("1080p FRENCH", 8.0),
            source("1080p ENGLISH fits", 8.0),
            source("720p ENGLISH", 3.0),
        )
        assertEquals(
            listOf("1080p ENGLISH fits"),
            ranked(sources, quality = BatchQuality.P1080, maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN),
        )
    }

    @Test
    fun best_available_still_prefers_the_highest_resolution_that_passes_the_cap() {
        val sources = listOf(
            source("720p", 3.0),
            source("2160p", 30.0),
            source("1080p", 8.0),
        )
        assertEquals(listOf("1080p", "720p"), ranked(sources, maxBitrateMbps = 10))
    }
}

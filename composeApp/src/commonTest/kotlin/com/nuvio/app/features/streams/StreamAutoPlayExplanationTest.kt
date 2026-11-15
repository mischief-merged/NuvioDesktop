package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamAutoPlayExplanationTest {
    private val hd = stream("AddonA", "1080p ENGLISH", size = 3_000_000_000L)
    private val group = AddonStreamGroup(addonName = "AddonA", addonId = "addon:AddonA", streams = listOf(hd), isLoading = false)

    private fun stream(addon: String, name: String, size: Long? = null) = StreamItem(
        name = name,
        url = "https://example.com/$name.mkv",
        addonName = addon,
        addonId = "addon:$addon",
        behaviorHints = StreamBehaviorHints(videoSize = size),
    )

    @Test
    fun a_bitrate_limit_counts_as_applied_only_when_the_running_time_is_known() {
        val preferences = SeasonDownloadDefaults(BatchQuality.P1080, maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN)

        val known = autoPlayContextFor(preferences, runtimeMinutes = 50)
        val unknown = autoPlayContextFor(preferences, runtimeMinutes = null)

        assertEquals(10, known.appliedMaxBitrateMbps)
        assertFalse(known.bitrateLimitSkipped)
        assertNull(unknown.appliedMaxBitrateMbps)
        assertTrue(unknown.bitrateLimitSkipped)
        assertNull(autoPlayContextFor(preferences, runtimeMinutes = 0).appliedMaxBitrateMbps)
    }

    @Test
    fun only_the_preferences_that_were_applied_are_named() {
        val preferences = SeasonDownloadDefaults(BatchQuality.P1080, maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN)

        assertEquals(
            listOf(
                AutoPlayPreferencePart.Quality(DebridStreamResolution.P1080),
                AutoPlayPreferencePart.MaxBitrate(10),
                AutoPlayPreferencePart.Language(DebridStreamLanguage.EN),
            ),
            autoPlayContextFor(preferences, 50).preferenceParts(),
        )
        assertEquals(
            listOf(
                AutoPlayPreferencePart.Quality(DebridStreamResolution.P1080),
                AutoPlayPreferencePart.Language(DebridStreamLanguage.EN),
            ),
            autoPlayContextFor(preferences, null).preferenceParts(),
        )
        assertTrue(autoPlayContextFor(SeasonDownloadDefaults(), 50).preferenceParts().isEmpty())
        assertFalse(autoPlayContextFor(SeasonDownloadDefaults(maxBitrateMbps = 10), null).hasAppliedPreferences)
    }

    @Test
    fun the_chosen_source_is_described_by_addon_quality_and_language() {
        val context = autoPlayContextFor(SeasonDownloadDefaults(maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN), 50)

        val choice = describeAutoPlayChoice(hd, context)

        assertEquals("AddonA", choice.sourceName)
        assertEquals(DebridStreamResolution.P1080, choice.resolution)
        assertEquals(DebridStreamLanguage.EN, choice.language)
        assertEquals(10, choice.withinBitrateMbps)
    }

    @Test
    fun the_chosen_source_does_not_claim_a_bitrate_limit_that_was_not_applied() {
        val noRuntime = autoPlayContextFor(SeasonDownloadDefaults(maxBitrateMbps = 10), runtimeMinutes = null)
        val unknownSize = stream("AddonA", "1080p")

        assertNull(describeAutoPlayChoice(hd, noRuntime).withinBitrateMbps)
        assertNull(describeAutoPlayChoice(unknownSize, autoPlayContextFor(SeasonDownloadDefaults(maxBitrateMbps = 10), 50)).withinBitrateMbps)
        assertNull(describeAutoPlayChoice(hd, null).withinBitrateMbps)
    }

    @Test
    fun what_is_not_known_about_the_chosen_source_is_left_out() {
        val choice = describeAutoPlayChoice(stream("AddonA", "Some release"), null)

        assertNull(choice.resolution)
        assertNull(choice.language)
        assertEquals("AddonA", choice.sourceName)
    }

    @Test
    fun the_banner_is_shown_only_when_best_match_found_nothing_and_there_are_sources() {
        val context = autoPlayContextFor(SeasonDownloadDefaults(BatchQuality.P2160), 50)
        val noMatch = StreamsUiState(groups = listOf(group), autoPlayContext = context, autoPlayFoundNoMatch = true)

        assertEquals(context, noMatch.autoPlayNoMatchBanner())
        assertNull(noMatch.copy(groups = emptyList()).autoPlayNoMatchBanner())
        assertNull(noMatch.copy(autoPlayFoundNoMatch = false).autoPlayNoMatchBanner())
        assertNull(noMatch.copy(autoPlayContext = null).autoPlayNoMatchBanner())
        assertNull(noMatch.copy(autoPlayStream = hd).autoPlayNoMatchBanner())
    }

    @Test
    fun the_chosen_source_follows_the_current_candidate() {
        val other = stream("AddonB", "720p")
        val state = StreamsUiState(autoPlayStream = hd, autoPlayContext = null)

        assertEquals("AddonA", state.autoPlayChoiceOrNull()?.sourceName)
        assertEquals("AddonB", state.copy(autoPlayStream = other).autoPlayChoiceOrNull()?.sourceName)
        assertNull(StreamsUiState().autoPlayChoiceOrNull())
    }
}

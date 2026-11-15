package com.nuvio.app.features.player

import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** The order of the sources a failed one is replaced from follows the user's mode. */
class SourceFailoverRankingTest {

    private fun source(name: String, url: String? = "https://example.com/${name.replace(' ', '-')}.mkv") =
        StreamItem(name = name, url = url, addonName = "Test", addonId = "test")

    private val s720 = source("Movie 720p")
    private val s1080a = source("Movie 1080p A")
    private val s1080b = source("Movie 1080p B")
    private val s2160 = source("Movie 2160p")
    private val all = listOf(s720, s1080a, s2160, s1080b)

    private fun ranked(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        preferences: SeasonDownloadDefaults = SeasonDownloadDefaults(),
        failedInfoHash: String? = null,
    ) = rankedSourceFailoverCandidates(
        streams = streams,
        settings = PlayerSettingsUiState(streamAutoPlayMode = mode),
        failedAddonId = "test",
        failedInfoHash = failedInfoHash,
        preferences = preferences,
        installedAddonNames = setOf("Test"),
        debridEnabled = false,
        activeResolverProviderId = null,
    )

    @Test
    fun firstStreamModeKeepsTheListOrder() {
        assertEquals(all, ranked(all, StreamAutoPlayMode.FIRST_STREAM))
    }

    @Test
    fun manualModeKeepsTheListOrder() {
        assertEquals(all, ranked(all, StreamAutoPlayMode.MANUAL))
    }

    @Test
    fun manualModeLeavesOutSourcesThePlayerCannotStart() {
        val noLink = source("Movie no link", url = null)
        assertEquals(listOf(s720, s1080a), ranked(listOf(s720, noLink, s1080a), StreamAutoPlayMode.MANUAL))
    }

    @Test
    fun preferredModeOrdersByTheSavedQuality() {
        val result = ranked(all, StreamAutoPlayMode.PREFERRED, SeasonDownloadDefaults(quality = BatchQuality.P1080))
        assertEquals(listOf(s1080a, s1080b), result)
    }

    @Test
    fun preferredModeWithoutAQualityPutsTheHighestResolutionFirst() {
        val result = ranked(all, StreamAutoPlayMode.PREFERRED)
        assertEquals(listOf(s2160, s1080a, s1080b, s720), result)
    }

    @Test
    fun otherCopiesOfTheFailedTorrentAreLeftOut() {
        val sameTorrent = StreamItem(name = "Same 1080p", url = "https://example.com/same.mkv", infoHash = "abc123", addonName = "Test", addonId = "test")
        val result = ranked(listOf(sameTorrent, s720), StreamAutoPlayMode.FIRST_STREAM, failedInfoHash = "ABC123")
        assertEquals(listOf(s720), result)
    }

    @Test
    fun anEmptyListGivesNoCandidates() {
        assertEquals(emptyList(), ranked(emptyList(), StreamAutoPlayMode.PREFERRED))
    }

    @Test
    fun preferredModeMovesASourceOfAnotherEpisodeBehindTheRightOne() {
        fun named(release: String) = source(release).copy(
            behaviorHints = com.nuvio.app.features.streams.StreamBehaviorHints(filename = "$release.mkv"),
        )
        val wrong = named("Show.S01E02.2160p")
        val right = named("Show.S01E03.1080p")
        val wanted = com.nuvio.app.features.streams.WantedContent(title = "Show", season = 1, episode = 3)
        fun rank(wanted: com.nuvio.app.features.streams.WantedContent?) = rankedSourceFailoverCandidates(
            streams = listOf(wrong, right),
            settings = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.PREFERRED),
            failedAddonId = "test",
            failedInfoHash = null,
            wanted = wanted,
            installedAddonNames = setOf("Test"),
            debridEnabled = false,
            activeResolverProviderId = null,
        )
        assertEquals(listOf(right, wrong), rank(wanted))
        assertEquals(listOf(wrong, right), rank(null))
    }
}

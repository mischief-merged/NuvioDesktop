package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.PlaceholderEvidence
import com.nuvio.app.features.downloads.PlaceholderReason
import com.nuvio.app.features.downloads.PlaceholderVerdict
import com.nuvio.app.features.downloads.placeholderVerdict
import com.nuvio.app.features.downloads.rankStreamsByPreferences
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.saysNotCached
import com.nuvio.app.features.streams.sourceLabelSaysNotCached
import com.nuvio.app.features.watching.domain.isProgressComplete
import com.nuvio.app.features.watching.domain.shouldStoreProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val COMET_UNCACHED = "[TB⬇️] Comet 1080p"
private const val COMET_CACHED = "[TB⚡] Comet 1080p"

/** The add-on markers that say a source is not on the debrid service yet, and nothing else. */
class NotCachedMarkerTest {
    @Test
    fun the_markers_add_ons_write_count() {
        assertTrue(sourceLabelSaysNotCached(COMET_UNCACHED))
        assertTrue(sourceLabelSaysNotCached("[RD⬇] Comet 2160p"), "the arrow without the emoji selector")
        assertTrue(sourceLabelSaysNotCached("[RD download] Torrentio\n4k"))
        assertTrue(sourceLabelSaysNotCached("[TB download] Torrentio\n1080p"))
        assertTrue(sourceLabelSaysNotCached("StremThru 1080p (uncached)"))
        assertTrue(sourceLabelSaysNotCached("Debrid: not cached"))
    }

    @Test
    fun cached_markers_and_title_words_do_not() {
        assertFalse(sourceLabelSaysNotCached(COMET_CACHED))
        assertFalse(sourceLabelSaysNotCached("[RD+] Torrentio\n4k"))
        assertFalse(sourceLabelSaysNotCached("[RD+] The Download 2019"))
        assertFalse(sourceLabelSaysNotCached("Downloaded.Life.S01E02.1080p"))
        assertFalse(sourceLabelSaysNotCached("Cached 1080p"))
        assertFalse(sourceLabelSaysNotCached("Uncachedness.2020"), "part of a word")
        assertFalse(sourceLabelSaysNotCached("⬇️ Top pick 1080p"), "an arrow outside a service tag")
        assertFalse(sourceLabelSaysNotCached(null))
    }

    @Test
    fun a_cached_source_with_an_arrow_in_its_release_title_is_not_a_suspect() {
        val cached = stream(COMET_CACHED, "https://c/1", description = "Download.Festival.⬇️.2024.1080p [uncached edition]")
        assertFalse(cached.saysNotCached, "the description holds the release title and is not read")
    }

    @Test
    fun the_apps_own_cache_check_counts_too() {
        val checked = stream("Torrent 1080p", null).copy(
            debridCacheStatus = StreamDebridCacheStatus("torbox", "TorBox", StreamDebridCacheState.NOT_CACHED),
        )
        assertTrue(checked.saysNotCached)
    }
}

/** When a length alone, or a length with the not-cached label, makes a placeholder. */
class PlaceholderFloorTest {
    private fun confirmed(suspect: Boolean, durationMs: Long, runtime: Int? = null) =
        isPlaceholderConfirmed(suspect = suspect, durationMs = durationMs, expectedRuntimeMinutes = runtime, resumeMs = 0L)

    @Test
    fun an_eight_second_clip_with_the_running_time_unknown_is_a_placeholder() {
        assertTrue(confirmed(suspect = false, durationMs = 8_000L))
        assertTrue(confirmed(suspect = true, durationMs = 8_000L))
    }

    @Test
    fun a_real_22_minute_episode_with_the_running_time_unknown_is_not() {
        assertFalse(confirmed(suspect = false, durationMs = 22 * 60_000L))
        assertFalse(confirmed(suspect = true, durationMs = 22 * 60_000L), "not even from a source labelled not cached")
    }

    @Test
    fun the_floor_is_one_minute_and_leaves_short_titles_with_a_known_running_time_alone() {
        assertTrue(isBelowPlaceholderFloor(59_000L, null))
        assertFalse(isBelowPlaceholderFloor(60_000L, null))
        assertFalse(isBelowPlaceholderFloor(90_000L, null), "a 90 second clip needs another signal")
        assertFalse(isBelowPlaceholderFloor(45_000L, 1), "a one minute short says so in its metadata")
        assertFalse(isBelowPlaceholderFloor(45_000L, 3))
        assertTrue(isBelowPlaceholderFloor(45_000L, 24))
        assertFalse(isBelowPlaceholderFloor(0L, null))
    }

    @Test
    fun a_source_labelled_not_cached_is_confirmed_by_a_length_under_five_minutes_without_a_running_time() {
        assertTrue(confirmed(suspect = true, durationMs = 4 * 60_000L))
        assertFalse(confirmed(suspect = false, durationMs = 4 * 60_000L))
    }
}

/** Cached sources are picked before ones the debrid service does not have yet. */
class CachedFirstRankingTest {
    private val uncached = stream(COMET_UNCACHED, "https://c/uncached.mkv")
    private val cached = stream(COMET_CACHED, "https://c/cached.mkv")
    private val torrentioUncached = stream("[RD download] Torrentio\n1080p", "https://t/u.mkv")
    private val torrentioCached = stream("[RD+] Torrentio\n720p", "https://t/c.mkv")

    private fun evaluate(streams: List<StreamItem>, mode: StreamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM) =
        StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = streams,
            mode = mode,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

    @Test
    fun auto_play_never_starts_with_a_not_cached_source_when_a_cached_one_exists() {
        val result = evaluate(listOf(uncached, torrentioUncached, cached, torrentioCached))
        assertEquals(cached, result.stream)
        assertEquals(listOf(cached, torrentioCached, uncached, torrentioUncached), result.readyStreams)
        assertEquals(cached, evaluate(listOf(uncached, cached), StreamAutoPlayMode.PREFERRED).stream)
    }

    @Test
    fun with_only_not_cached_sources_the_first_is_still_picked() {
        assertEquals(uncached, evaluate(listOf(uncached, torrentioUncached)).stream)
    }

    @Test
    fun the_binge_group_prefers_its_cached_copy() {
        val groupUncached = uncached.copy(behaviorHints = uncached.behaviorHints.copy(bingeGroup = "comet|1080p"))
        val groupCached = cached.copy(behaviorHints = cached.behaviorHints.copy(bingeGroup = "comet|1080p"))
        val result = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(groupUncached, groupCached),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "comet|1080p",
            preferBingeGroupInSelection = true,
        )
        assertEquals(groupCached, result.stream)
    }

    @Test
    fun the_download_ranking_puts_cached_sources_first_even_above_a_higher_resolution() {
        val ranked = rankStreamsByPreferences(listOf(torrentioUncached, torrentioCached), BatchQuality.BestAvailable)
        assertEquals(listOf(torrentioCached, torrentioUncached), ranked)
    }

    @Test
    fun moving_on_in_manual_mode_tries_cached_sources_before_not_cached_ones() {
        val ranked = rankedSourceFailoverCandidates(
            streams = listOf(uncached, torrentioUncached, cached),
            settings = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.MANUAL),
            failedAddonId = null,
            failedInfoHash = null,
            installedAddonNames = emptySet(),
            debridEnabled = false,
            activeResolverProviderId = null,
        )
        assertEquals(listOf(cached, uncached, torrentioUncached), ranked)
    }
}

/** Downloads apply the not-cached label the same way. */
class NotCachedDownloadVerdictTest {
    @Test
    fun an_eight_second_file_from_a_source_labelled_not_cached_is_not_the_real_video() {
        val verdict = placeholderVerdict(
            PlaceholderEvidence(fileBytes = 400_000L, claimedBytes = null, durationMs = 8_000L, runtimeMinutes = null, sourceSaysNotCached = true),
        )
        assertEquals(PlaceholderVerdict.NotTheRealVideo(PlaceholderReason.NotCachedClip(8_000L)), verdict)
    }

    @Test
    fun a_full_episode_from_such_a_source_and_a_short_file_without_the_label_are_not_flagged_by_it() {
        val full = placeholderVerdict(
            PlaceholderEvidence(fileBytes = 900_000_000L, claimedBytes = null, durationMs = 22 * 60_000L, runtimeMinutes = null, sourceSaysNotCached = true),
        )
        assertFalse(full is PlaceholderVerdict.NotTheRealVideo)
        val unlabelled = placeholderVerdict(
            PlaceholderEvidence(fileBytes = 400_000L, claimedBytes = null, durationMs = 8_000L, runtimeMinutes = null),
        )
        assertEquals(PlaceholderVerdict.CouldNotCheck, unlabelled)
    }
}

/**
 * The case that was reported: a source picked by hand in Manual mode (the default), labelled not cached,
 * plays an eight second "not cached yet" clip to its end, the running time unknown. The player moves on
 * to the next source by itself, and nothing about the clip counts as watching the episode.
 */
class UncachedClipSequenceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clipUrl = "https://comet.example/playback/uncached/S01E02.mkv"
    private val clipStream = stream(COMET_UNCACHED, clipUrl)
    private val nextStream = stream(COMET_CACHED, "https://comet.example/playback/cached/S01E02.mkv")

    @AfterTest
    fun cleanup() = scope.cancel()

    private class ListSearch(private val streams: List<StreamItem>) : FailoverSourceSearch {
        var started = 0
        override fun start(type: String, videoId: String, season: Int?, episode: Int?) {
            started++
        }
        override fun state() = StreamsUiState(
            groups = listOf(AddonStreamGroup(addonName = "Comet", addonId = "comet", streams = streams, isLoading = false)),
            isAnyLoading = false,
        )
        override fun stop() = Unit
    }

    private fun runtime(search: FailoverSourceSearch) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "LEGO Star Wars: Rebuild the Galaxy",
            sourceUrl = clipUrl,
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Comet",
            streamTitle = COMET_UNCACHED,
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = null,
            episodeThumbnail = null,
            contentType = "series",
            videoId = "tt-lego:1:2",
            parentMetaId = "tt-lego",
            parentMetaType = "series",
            providerAddonId = "comet",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    ).apply {
        scope = this@UncachedClipSequenceTest.scope
        // The defaults on desktop: Manual mode, moving on when a source fails is on.
        playerSettingsUiState = PlayerSettingsUiState()
        activeStream = clipStream
        failoverSourceSearch = search
    }

    @Test
    fun the_defaults_on_desktop_move_on_from_a_placeholder_in_manual_mode() {
        val defaults = PlayerSettingsUiState()
        assertEquals(StreamAutoPlayMode.MANUAL, defaults.streamAutoPlayMode)
        assertTrue(defaults.streamAutoPlayFallbackActive)
        assertFalse(defaults.engineSwitchOnErrorBlocksSourceFailover)
    }

    @Test
    fun a_hand_picked_not_cached_clip_that_plays_to_its_end_moves_on_and_counts_for_nothing() {
        val search = ListSearch(listOf(clipStream, nextStream))
        val runtime = runtime(search)
        assertNull(runtime.autoSelectedSourceUrl, "the source was picked by hand")

        // Handed to the player: its label makes it a suspect.
        runtime.checkForPlaceholderBySourceSignals()
        assertEquals(clipUrl, runtime.placeholderSuspectUrl)

        // It plays: the first frame, then the end at 00:08, before its length could settle.
        runtime.checkForPlaceholderSource(PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, durationMs = 8_000L, positionMs = 0L))
        val ended = PlayerPlaybackSnapshot(isLoading = false, isPlaying = false, isEnded = true, durationMs = 8_000L, positionMs = 8_000L)
        runtime.playbackSnapshot = ended

        // Nothing about the clip counts as watching.
        assertFalse(isProgressComplete(ended.positionMs, ended.durationMs, ended.isEnded), "the clip would mark the episode watched")
        assertFalse(shouldStoreProgress(ended.positionMs, ended.durationMs), "the clip would save watch progress")
        runtime.emitTrackingScrobbleStart()
        assertFalse(runtime.hasRequestedScrobbleStartForCurrentItem, "the clip would start a scrobble")
        assertFalse(runtime.isAtNextEpisodeThreshold(), "the clip's end would offer or start the next episode")

        runtime.checkForPlaceholderSource(ended)
        runtime.shouldPlay = false // the player stops at the end of a file

        val deadline = System.currentTimeMillis() + 10_000
        while (runtime.activeSourceUrl == clipUrl && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertEquals(nextStream.url, runtime.activeSourceUrl, "the next source was not loaded")
        assertEquals(1, search.started)
        assertTrue(runtime.shouldPlay, "the next source would wait paused")
        assertNull(runtime.errorMessage)
        assertFalse(runtime.hasRequestedScrobbleStartForCurrentItem)
    }

    @Test
    fun the_running_time_comes_from_the_details_the_player_loaded_when_the_details_screen_is_not_open() {
        val runtime = runtime(ListSearch(emptyList()))
        assertNull(runtime.expectedRuntimeMinutes("tt-lego:1:2", 1, 2))
        runtime.playerMeta = MetaDetails(
            id = "tt-lego",
            type = "series",
            name = "LEGO Star Wars: Rebuild the Galaxy",
            videos = listOf(MetaVideo(id = "tt-lego:1:2", title = "Episode 2", season = 1, episode = 2, runtime = 22)),
        )
        assertEquals(22, runtime.expectedRuntimeMinutes("tt-lego:1:2", 1, 2))
        // Details of another title say nothing about this one.
        runtime.playerMeta = runtime.playerMeta!!.copy(id = "tt-other")
        assertNull(runtime.expectedRuntimeMinutes("tt-lego:1:2", 1, 2))
    }
}

private fun stream(name: String, url: String?, description: String? = null) = StreamItem(
    name = name,
    description = description,
    url = url,
    addonName = "Comet",
    addonId = "comet",
)

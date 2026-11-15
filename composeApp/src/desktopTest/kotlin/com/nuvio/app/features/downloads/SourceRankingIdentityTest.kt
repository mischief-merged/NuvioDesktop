package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.WantedContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How the shared ranking uses the title, the season and episode, the year and the language a
 * source's release name states. Sources are told apart by their release name (the file name).
 */
class SourceRankingIdentityTest {
    private fun source(release: String) = StreamItem(
        name = "Torz",
        addonName = "A",
        addonId = "addon:A",
        url = "http://x/${release.hashCode()}.mkv",
        behaviorHints = StreamBehaviorHints(filename = release),
    )

    private fun rank(
        sources: List<StreamItem>,
        wanted: WantedContent? = null,
        language: DebridStreamLanguage? = null,
        quality: BatchQuality = BatchQuality.BestAvailable,
    ): List<String?> = rankStreamsByPreferences(
        streams = sources,
        quality = quality,
        audioLanguage = language,
        wanted = wanted,
    ).map { it.behaviorHints.filename }

    private val korra = WantedContent(title = "The Legend of Korra", season = 3, episode = 13)

    // Language ----------------------------------------------------------------------------

    @Test
    fun a_source_tagged_with_the_chosen_language_ranks_ahead_of_an_untagged_one() {
        val untagged = "Show.S01E01.1080p.WEB-DL.x264-GRP"
        val tagged = "Show.S01E01.1080p.WEB-DL.ENG.x264-GRP"
        assertEquals(listOf(tagged, untagged), rank(listOf(source(untagged), source(tagged)), language = DebridStreamLanguage.EN))
    }

    @Test
    fun an_untagged_source_still_counts_as_acceptable_english() {
        val untagged = "Show.S01E01.1080p.WEB-DL.x264-GRP"
        assertEquals(listOf(untagged), rank(listOf(source(untagged)), language = DebridStreamLanguage.EN))
    }

    @Test
    fun an_untagged_source_that_looks_foreign_is_demoted_behind_acceptable_ones_whatever_its_resolution() {
        val subbed = "[SubsPlease] Frieren - 01 (2160p) [ABC123].mkv"
        val plain = "Frieren.S01E01.1080p.WEB-DL-GRP.mkv"
        val unlisted = "Frieren.S01E01.2160p.WEB-DL.HUN-GRP.mkv"
        // Kept in the list, never dropped.
        assertEquals(
            listOf(plain, subbed, unlisted),
            rank(listOf(source(subbed), source(plain), source(unlisted)), language = DebridStreamLanguage.EN),
        )
        // With no language chosen nothing is judged by language.
        assertEquals(
            listOf(subbed, unlisted, plain),
            rank(listOf(source(subbed), source(plain), source(unlisted))),
        )
    }

    @Test
    fun a_language_that_appears_only_as_a_subtitle_language_does_not_count_as_audio() {
        val frenchSubs = "Movie.2024.1080p.WEB-DL.x264.FRENCH.SUB-GRP"
        val frenchAudio = "Movie.2024.1080p.WEB-DL.x264.FRENCH-GRP"
        val sources = listOf(source(frenchSubs), source(frenchAudio))
        // Chosen French: the audio one ranks first, the subtitle one is merely acceptable.
        assertEquals(listOf(frenchAudio, frenchSubs), rank(sources, language = DebridStreamLanguage.FR))
        // Chosen English: French audio is dropped, French subtitles are not.
        assertEquals(listOf(frenchSubs), rank(sources, language = DebridStreamLanguage.EN))
    }

    @Test
    fun multi_audio_counts_as_containing_the_chosen_language() {
        val multi = "Movie.2024.MULTi.ITA.1080p.WEB-DL-GRP"
        val italianOnly = "Movie.2024.ITA.1080p.WEB-DL-GRP"
        assertEquals(listOf(multi), rank(listOf(source(italianOnly), source(multi)), language = DebridStreamLanguage.FR))
    }

    @Test
    fun a_title_that_contains_a_language_word_is_not_read_as_that_language() {
        val wanted = WantedContent(title = "The German Doctor")
        val titled = "The.German.Doctor.2013.1080p.BluRay-GRP"
        assertEquals(listOf(titled), rank(listOf(source(titled)), wanted = wanted, language = DebridStreamLanguage.EN))
    }

    // Identity ----------------------------------------------------------------------------

    @Test
    fun a_source_of_another_title_goes_last_and_stays_in_the_list() {
        val other = "Unrelated.Show.S03E13.2160p.WEB-DL-GRP"
        val right = "The.Legend.of.Korra.S03E13.1080p.WEB-DL-GRP"
        assertEquals(listOf(right, other), rank(listOf(source(other), source(right)), wanted = korra))
        // Without the wanted title the higher resolution wins, as before.
        assertEquals(listOf(other, right), rank(listOf(source(other), source(right))))
    }

    @Test
    fun a_source_of_another_episode_goes_last_and_stays_in_the_list() {
        val wrongEpisode = "The.Legend.of.Korra.S03E12.2160p.WEB-DL-GRP"
        val wrongSeason = "The.Legend.of.Korra.S02E13.2160p.WEB-DL-GRP"
        val right = "The.Legend.of.Korra.S03E13.1080p.WEB-DL-GRP"
        assertEquals(
            listOf(right, wrongEpisode, wrongSeason),
            rank(listOf(source(wrongEpisode), source(wrongSeason), source(right)), wanted = korra),
        )
    }

    @Test
    fun a_pack_that_covers_the_wanted_episode_is_not_demoted() {
        val pack = "The.Legend.of.Korra.S03E01-E13.1080p.BluRay-GRP"
        val wrong = "The.Legend.of.Korra.S03E07.2160p.WEB-DL-GRP"
        assertEquals(listOf(pack, wrong), rank(listOf(source(wrong), source(pack)), wanted = korra))
    }

    @Test
    fun a_film_of_another_year_goes_last_and_stays_in_the_list() {
        val wanted = WantedContent(title = "The Thing", year = 2011)
        val remake = "The.Thing.2011.1080p.BluRay-GRP"
        val original = "The.Thing.1982.2160p.BluRay.REMUX-GRP"
        assertEquals(listOf(remake, original), rank(listOf(source(original), source(remake)), wanted = wanted))
    }

    @Test
    fun the_year_of_a_series_is_never_compared() {
        val wanted = WantedContent(title = "Greys Anatomy", season = 20, episode = 1, year = 2005)
        val current = "Greys.Anatomy.2024.S20E01.2160p.WEB-DL-GRP"
        val older = "Greys.Anatomy.S20E01.1080p.WEB-DL-GRP"
        assertEquals(listOf(current, older), rank(listOf(source(older), source(current)), wanted = wanted))
    }

    @Test
    fun when_no_source_matches_the_title_nothing_is_reordered() {
        val sources = listOf(
            source("Аватар Легенда о Корре S03E13 1080p"),
            source("Another translated title S03E13 2160p"),
        )
        assertEquals(rank(sources), rank(sources, wanted = korra))
    }

    @Test
    fun names_that_carry_no_information_leave_the_existing_order() {
        val sources = listOf(source("1080p WEB-DL"), source("2160p WEB-DL"), source("720p"))
        assertEquals(rank(sources), rank(sources, wanted = korra))
        assertEquals(rank(sources), rank(sources, wanted = WantedContent(title = "Heat", year = 1995)))
    }

    @Test
    fun an_unknown_wanted_title_or_numbering_never_counts_against_a_source() {
        val sources = listOf(source("Unrelated.Show.S09E09.2160p.WEB-DL-GRP"), source("Other.1080p"))
        assertEquals(rank(sources), rank(sources, wanted = WantedContent()))
        assertEquals(rank(sources), rank(sources, wanted = null))
    }

    @Test
    fun a_hard_filter_still_drops_a_source_before_identity_is_judged() {
        val sources = listOf(
            source("The.Legend.of.Korra.S03E13.720p.WEB-DL-GRP"),
            source("Unrelated.Show.S03E13.1080p.WEB-DL-GRP"),
        )
        // The matching source is the wrong resolution and is dropped, so nothing matches the title
        // among the rest and the one left is not demoted for it.
        assertEquals(
            listOf("Unrelated.Show.S03E13.1080p.WEB-DL-GRP"),
            rank(sources, wanted = korra, quality = BatchQuality.P1080),
        )
    }

    @Test
    fun identity_ranks_ahead_of_language_and_language_ahead_of_resolution_only_when_foreign() {
        val wrongEpisodeTagged = "The.Legend.of.Korra.S03E12.1080p.WEB-DL.ENG-GRP"
        val rightUntagged = "The.Legend.of.Korra.S03E13.1080p.WEB-DL-GRP"
        val rightForeign = "The.Legend.of.Korra.S03E13.2160p.WEB-DL.HUN-GRP"
        assertEquals(
            listOf(rightUntagged, rightForeign, wrongEpisodeTagged),
            rank(
                listOf(source(wrongEpisodeTagged), source(rightForeign), source(rightUntagged)),
                wanted = korra,
                language = DebridStreamLanguage.EN,
            ),
        )
    }

    // Callers -----------------------------------------------------------------------------

    @Test
    fun a_batch_request_asks_for_the_show_title_and_the_episode_or_for_the_movie_title() {
        val episode = SeasonBatchEpisode(videoId = "tt1:3:13", seasonNumber = 3, episodeNumber = 13, title = "Ep", thumbnail = null)
        val movie = SeasonBatchEpisode(
            videoId = "tt2", seasonNumber = 1, episodeNumber = 1, title = null, thumbnail = null,
            movie = SeasonBatchMovie(metaId = "tt2", metaType = "movie", title = "Heat"),
        )
        val request = SeasonBatchRequest(
            contentType = "series", parentMetaId = "tt1", parentMetaType = "series", title = "The Legend of Korra",
            logo = null, poster = null, background = null, seasonNumber = 3,
            episodes = listOf(episode, movie), quality = BatchQuality.BestAvailable,
        )
        assertEquals(WantedContent(title = "The Legend of Korra", season = 3, episode = 13), request.wantedContent(episode))
        assertEquals(WantedContent(title = "Heat"), request.wantedContent(movie))
    }

    @Test
    fun a_season_download_tries_the_right_episode_before_a_wrong_one() {
        val episode = SeasonBatchEpisode(videoId = "tt1:3:13", seasonNumber = 3, episodeNumber = 13, title = null, thumbnail = null)
        val request = SeasonBatchRequest(
            contentType = "series", parentMetaId = "tt1", parentMetaType = "series", title = "The Legend of Korra",
            logo = null, poster = null, background = null, seasonNumber = 3,
            episodes = listOf(episode), quality = BatchQuality.BestAvailable,
        )
        val wrong = source("The.Legend.of.Korra.S03E12.2160p.WEB-DL-GRP")
        val right = source("The.Legend.of.Korra.S03E13.1080p.WEB-DL-GRP")
        assertEquals(
            listOf(right, wrong),
            rankBatchCandidates(listOf(wrong, right), request.quality, wanted = request.wantedContent(episode)),
        )
    }

    @Test
    fun best_match_play_picks_the_right_episode_and_never_the_wrong_one_first() {
        val wrong = source("The.Legend.of.Korra.S03E12.2160p.WEB-DL-GRP")
        val right = source("The.Legend.of.Korra.S03E13.1080p.WEB-DL-GRP")
        fun pick(wanted: WantedContent?) = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(wrong, right),
            mode = StreamAutoPlayMode.PREFERRED,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            wanted = wanted,
        )
        assertEquals(right, pick(korra).stream)
        assertEquals(listOf(right, wrong), pick(korra).readyStreams)
        assertEquals(wrong, pick(null).stream)
    }

    @Test
    fun best_match_play_still_ends_with_nothing_when_every_source_is_another_language() {
        val french = source("Show.S01E01.1080p.WEB-DL.FRENCH-GRP")
        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(french),
            mode = StreamAutoPlayMode.PREFERRED,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferences = SeasonDownloadDefaults(audioLanguage = DebridStreamLanguage.EN),
        )
        assertNull(evaluation.stream)
    }
}

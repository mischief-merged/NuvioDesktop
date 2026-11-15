package com.nuvio.app.features.details

import com.nuvio.app.features.streams.WantedContent
import kotlin.test.Test
import kotlin.test.assertEquals

class MetaDetailsWantedContentTest {
    private fun meta(type: String, releaseInfo: String?) =
        MetaDetails(id = "tt1", type = type, name = "Heat", releaseInfo = releaseInfo)

    @Test
    fun a_film_wants_its_title_and_release_year() {
        assertEquals(WantedContent(title = "Heat", year = 1995), meta("movie", "1995").wantedContent(null, null))
        assertEquals(WantedContent(title = "Heat", year = 1995), meta("movie", "1995-").wantedContent(null, null))
    }

    @Test
    fun a_film_with_no_readable_year_wants_only_its_title() {
        assertEquals(WantedContent(title = "Heat"), meta("movie", null).wantedContent(null, null))
        assertEquals(WantedContent(title = "Heat"), meta("movie", "soon").wantedContent(null, null))
    }

    @Test
    fun an_episode_wants_title_season_and_episode_but_never_the_shows_first_air_year() {
        assertEquals(
            WantedContent(title = "Heat", season = 3, episode = 13),
            meta("series", "2012-2014").wantedContent(3, 13),
        )
    }

    @Test
    fun a_blank_name_is_not_a_title() {
        assertEquals(
            WantedContent(),
            MetaDetails(id = "tt1", type = "movie", name = " ").wantedContent(null, null),
        )
    }

    @Test
    fun an_episode_carries_the_alternative_titles_and_the_episode_count_of_its_season() {
        val meta = MetaDetails(
            id = "tt1", type = "series", name = "Spirited", alternativeTitles = listOf("Original"),
            videos = listOf(
                MetaVideo(id = "a", title = "a", season = 1, episode = 1),
                MetaVideo(id = "b", title = "b", season = 1, episode = 2),
                MetaVideo(id = "c", title = "c", season = 2, episode = 1),
            ),
        )
        val wanted = meta.wantedContent(1, 2)
        assertEquals(listOf("Spirited", "Original"), wanted.titles)
        assertEquals(2, wanted.seasonEpisodeCount)
        assertEquals(false, wanted.numberingUnreliable)
    }

    @Test
    fun anime_is_known_by_its_id_source_type_or_genre() {
        assertEquals(true, MetaDetails(id = "kitsu:1", type = "series", name = "A").isAnime())
        assertEquals(true, MetaDetails(id = "mal:1", type = "series", name = "A").isAnime())
        assertEquals(true, MetaDetails(id = "tt1", type = "series", name = "A", genres = listOf("Anime")).isAnime())
        assertEquals(false, MetaDetails(id = "tt1", type = "series", name = "A", genres = listOf("Animation")).isAnime())
        assertEquals(true, MetaDetails(id = "kitsu:1", type = "series", name = "A").wantedContent(1, 1).numberingUnreliable)
    }
}

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
}

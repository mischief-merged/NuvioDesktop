package com.nuvio.app

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.EpisodeDetailRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.TabsRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeNavigationRoutingTest {

    private fun episode(videoId: String, id: String = "tt1", episode: Int = 1) =
        EpisodeDetailRoute(type = "series", id = id, videoId = videoId, season = 1, episode = episode, title = "Show")

    private fun show(id: String = "tt1", season: Int? = null) =
        DetailRoute(type = "series", id = id, title = "Show", season = season)

    private fun stack(vararg routes: NavKey) = NavBackStack<NavKey>(*routes)

    private fun NavBackStack<NavKey>.routes(): List<Any> = toList()

    @Test
    fun anotherEpisodeReplacesTheCurrentEpisodePage() {
        val back = stack(TabsRoute, show(), episode("e1"))
        NuvioNavigator(back).replaceWithEpisode(episode("e2", episode = 2))
        assertEquals(listOf<Any>(TabsRoute, show(), episode("e2", episode = 2)), back.routes())
    }

    @Test
    fun openingTheSameEpisodeAgainChangesNothing() {
        val back = stack(TabsRoute, show(), episode("e1"))
        NuvioNavigator(back).replaceWithEpisode(episode("e1"))
        assertEquals(listOf<Any>(TabsRoute, show(), episode("e1")), back.routes())
    }

    @Test
    fun showFromEpisodeReusesTheShowPageBelow() {
        val back = stack(TabsRoute, show(), episode("e1"))
        NuvioNavigator(back).openShowPage("series", "tt1", "Show")
        assertEquals(listOf<Any>(TabsRoute, show()), back.routes())
    }

    @Test
    fun showFromSeveralEpisodePagesDropsThemAll() {
        val back = stack(TabsRoute, show(), episode("e1"), episode("e2", episode = 2))
        NuvioNavigator(back).openShowPage("series", "tt1", "Show")
        assertEquals(listOf<Any>(TabsRoute, show()), back.routes())
    }

    @Test
    fun seasonFromEpisodeSwapsTheShowPageForOneOnThatSeason() {
        val back = stack(TabsRoute, show(), episode("e1"))
        NuvioNavigator(back).openShowPage("series", "tt1", "Show", season = 2)
        assertEquals(listOf<Any>(TabsRoute, show(season = 2)), back.routes())
    }

    @Test
    fun showFromAnEpisodeReachedFromAnotherPageKeepsThatPageBelow() {
        val back = stack(TabsRoute, show("tt9"), episode("e1"))
        NuvioNavigator(back).openShowPage("series", "tt1", "Show")
        assertEquals(listOf<Any>(TabsRoute, show("tt9"), show()), back.routes())
    }

    @Test
    fun showPageOfAnotherShowIsNotTouched() {
        val back = stack(TabsRoute, show("tt9"), episode("e1"))
        NuvioNavigator(back).openShowPage("series", "tt1", "Show", season = 3)
        assertEquals(show("tt9"), back.routes()[1])
        assertEquals(show(season = 3), back.routes().last())
    }

    @Test
    fun episodePageAfterThePlayerReplacesAnEpisodePageOfTheSameShow() {
        val back = stack(TabsRoute, show(), episode("e1"))
        NuvioNavigator(back).openEpisodePage(episode("e2", episode = 2))
        assertEquals(listOf<Any>(TabsRoute, show(), episode("e2", episode = 2)), back.routes())
    }

    @Test
    fun episodePageAfterThePlayerIsAddedWhenNoEpisodePageIsOnTop() {
        val back = stack(TabsRoute, show())
        NuvioNavigator(back).openEpisodePage(episode("e2", episode = 2))
        assertEquals(listOf<Any>(TabsRoute, show(), episode("e2", episode = 2)), back.routes())
    }
}

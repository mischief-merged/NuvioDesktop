package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeNavigationTest {

    private fun video(id: String, season: Int?, episode: Int?, title: String = id) =
        MetaVideo(id = id, title = title, season = season, episode = episode)

    private fun show(vararg videos: MetaVideo, type: String = "series") =
        MetaDetails(id = "tt1", type = type, name = "Show", videos = videos.toList())

    private val sample = show(
        video("sp1", 0, 1),
        video("s2e1", 2, 1),
        video("s1e2", 1, 2),
        video("s1e1", 1, 1),
        video("s2e2", 2, 2),
        video("sp2", 0, 2),
    )

    @Test
    fun displayOrderPutsSpecialsLast() {
        assertEquals(
            listOf("s1e1", "s1e2", "s2e1", "s2e2", "sp1", "sp2"),
            sample.episodesInDisplayOrder().map { it.id },
        )
    }

    @Test
    fun findsByVideoId() {
        assertEquals("s2e1", sample.findEpisode("s2e1")?.id)
    }

    @Test
    fun findsBySeasonAndEpisodeWhenIdUnknown() {
        assertEquals("s1e2", sample.findEpisode("missing", 1, 2)?.id)
        assertEquals("s1e2", sample.findEpisode(null, 1, 2)?.id)
    }

    @Test
    fun idWinsOverSeasonAndEpisode() {
        assertEquals("s2e2", sample.findEpisode("s2e2", 1, 1)?.id)
    }

    @Test
    fun unknownIdWithoutNumbersIsNull() {
        assertNull(sample.findEpisode("missing"))
        assertNull(sample.findEpisode(null))
        assertNull(sample.findEpisode(""))
    }

    @Test
    fun missingEpisodeNumberDoesNotMatchBySeasonAlone() {
        assertNull(sample.findEpisode("missing", 1, null))
    }

    @Test
    fun seasonAndEpisodeNotInShowIsNull() {
        assertNull(sample.findEpisode(null, 3, 1))
        assertNull(sample.findEpisode(null, 1, 9))
    }

    @Test
    fun nullAndNegativeSeasonsCountAsSpecials() {
        assertEquals("sp2", sample.findEpisode(null, null, 2)?.id)
        assertEquals("sp1", sample.findEpisode(null, -1, 1)?.id)
    }

    @Test
    fun previousAndNextCrossSeasonBoundaries() {
        val s1e2 = sample.findEpisode("s1e2")!!
        val s2e1 = sample.findEpisode("s2e1")!!
        assertEquals("s2e1", sample.nextEpisode(s1e2)?.id)
        assertEquals("s1e2", sample.previousEpisode(s2e1)?.id)
    }

    @Test
    fun lastRegularEpisodeLeadsToSpecialsAndBack() {
        val s2e2 = sample.findEpisode("s2e2")!!
        val sp1 = sample.findEpisode("sp1")!!
        assertEquals("sp1", sample.nextEpisode(s2e2)?.id)
        assertEquals("s2e2", sample.previousEpisode(sp1)?.id)
    }

    @Test
    fun endsOfSeriesHaveNoNeighbour() {
        assertNull(sample.previousEpisode(sample.findEpisode("s1e1")!!))
        assertNull(sample.nextEpisode(sample.findEpisode("sp2")!!))
    }

    @Test
    fun unknownVideoHasNoNeighbours() {
        val stranger = video("other", 1, 1)
        assertNull(sample.previousEpisode(stranger))
        assertNull(sample.nextEpisode(stranger))
    }

    @Test
    fun showWithOnlySpecialsStillNavigates() {
        val only = show(video("a", 0, 1), video("b", 0, 2))
        assertEquals("b", only.nextEpisode(only.findEpisode("a")!!)?.id)
        assertNull(only.previousEpisode(only.findEpisode("a")!!))
    }

    @Test
    fun singleEpisodeShowHasNoNeighbours() {
        val one = show(video("a", 1, 1))
        val a = one.findEpisode("a")!!
        assertNull(one.previousEpisode(a))
        assertNull(one.nextEpisode(a))
    }

    @Test
    fun episodesWithoutNumbersAreIgnoredWhenOthersAreNumbered() {
        val mixed = show(video("n1", 1, 1), MetaVideo(id = "plain", title = "Plain"))
        assertEquals(listOf("n1"), mixed.episodesInDisplayOrder().map { it.id })
        assertNull(mixed.findEpisode("plain"))
    }

    @Test
    fun episodeMissingFromNumberSequenceKeepsOrder() {
        val gap = show(video("e1", 1, 1), video("e3", 1, 3), video("e5", 1, null))
        assertEquals(listOf("e1", "e3", "e5"), gap.episodesInDisplayOrder().map { it.id })
        assertEquals("e3", gap.nextEpisode(gap.findEpisode("e1")!!)?.id)
    }

    @Test
    fun seasonStripListsOnlyTheSameSeason() {
        val s1e1 = sample.findEpisode("s1e1")!!
        assertEquals(listOf("s1e1", "s1e2"), sample.episodesInSeasonOf(s1e1).map { it.id })
        val sp = sample.findEpisode("sp1")!!
        assertEquals(listOf("sp1", "sp2"), sample.episodesInSeasonOf(sp).map { it.id })
    }

    @Test
    fun trailNamesShowSeasonAndEpisode() {
        val v = video("s2e1", 2, 1, "Pilot")
        val trail = show(v).trailFor(v)
        assertEquals(EpisodeTrail("Show", 2, 1, "Pilot"), trail)
    }

    @Test
    fun trailForSpecialUsesSeasonZero() {
        val v = video("sp", null, 4)
        assertEquals(0, show(v).trailFor(v).seasonNumber)
    }
}

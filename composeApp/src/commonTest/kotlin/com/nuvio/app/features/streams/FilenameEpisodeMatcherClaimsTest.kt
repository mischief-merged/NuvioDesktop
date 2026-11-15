package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilenameEpisodeMatcherClaimsTest {
    private fun other(name: String?, season: Int? = 2, episode: Int? = 6) =
        FilenameEpisodeMatcher.namesOtherEpisode(name, season, episode)

    @Test
    fun `a name for another episode or season is told apart`() {
        assertTrue(other("Show.S02E09.1080p.mkv"))
        assertTrue(other("Show.S03E06.1080p.mkv"))
        assertTrue(other("Show 2x09 1080p.mkv"))
        assertFalse(other("Show.S02E06.1080p.mkv"))
        assertFalse(other("Show 2x06 1080p.mkv"))
    }

    @Test
    fun `a pack that covers the wanted episode is not another episode`() {
        assertFalse(other("Show.S02E01-E13.COMPLETE.mkv"))
        assertFalse(other("Show.S02E01-13.mkv"))
        assertFalse(other("Show.S02E05E06E07.mkv"))
        assertFalse(other("Show.S02E05&E06.mkv"))
        assertTrue(other("Show.S02E01-E05.mkv"))
        assertTrue(other("Show.S03E01-E13.mkv"))
    }

    @Test
    fun `a resolution after an episode marker is not read as more episodes`() {
        val claims = FilenameEpisodeMatcher.episodeClaims("Show.S02E06-1080p.WEB.mkv")
        assertEquals(listOf(FilenameEpisodeMatcher.EpisodeClaim(2, setOf(6))), claims)
        assertEquals(emptyList(), FilenameEpisodeMatcher.episodeClaims("Film.1920x1080.x264.mkv"))
    }

    @Test
    fun `a name that proves nothing is never another episode`() {
        assertFalse(other(null))
        assertFalse(other("   "))
        assertFalse(other("Some.Release.2160p.mkv"))
        assertFalse(other("Sorvegliato.Speciale.S02E06.ITA.mkv"))
        assertFalse(other("Show.S05E01.mkv", season = 2, episode = null))
        assertFalse(other("Show.S05E01.mkv", season = null, episode = null))
    }

    @Test
    fun `a three digit episode number is read`() {
        assertEquals(setOf(105), FilenameEpisodeMatcher.episodeClaims("Anime.S01E105.1080p.mkv").single().episodes)
    }

    @Test
    fun `the release year skips resolutions and encoder tags`() {
        assertEquals(2016, FilenameEpisodeMatcher.releaseYear("Show.2016.S02E06.2160p.x265.mkv"))
        assertNull(FilenameEpisodeMatcher.releaseYear("Show.S02E06.2160p.x265.DDP5.1.mkv"))
        assertNull(FilenameEpisodeMatcher.releaseYear("Film.Name.1920x1080.x264.mkv"))
    }

    @Test
    fun `a year more than one off is another year`() {
        assertTrue(FilenameEpisodeMatcher.namesOtherYear("The.Thing.1982.2160p.mkv", 2011))
        assertFalse(FilenameEpisodeMatcher.namesOtherYear("Film.2017.mkv", 2016))
        assertFalse(FilenameEpisodeMatcher.namesOtherYear("Film.2015.mkv", 2016))
        assertFalse(FilenameEpisodeMatcher.namesOtherYear("Film.BluRay.mkv", 2016))
        assertFalse(FilenameEpisodeMatcher.namesOtherYear("Film.2026.mkv", null))
        assertFalse(FilenameEpisodeMatcher.namesOtherYear(null, 2016))
    }
}

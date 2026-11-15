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

    @Test
    fun `NxNN ranges and chains cover every episode they name`() {
        for (name in listOf("Show.2x05-06.2160p.mkv", "Show.2x05-2x06.2160p.mkv", "Show.2x05x06.2160p.mkv", "Show.2x05&06.mkv")) {
            assertFalse(other(name), name)
            assertEquals(setOf(5, 6), FilenameEpisodeMatcher.episodeClaims(name).single().episodes, name)
        }
        assertTrue(other("Show.2x01-04.mkv"))
        assertTrue(other("Show.2x05-3x06.mkv"))
    }

    @Test
    fun `technical tags after a marker are never episodes or years`() {
        for (tag in listOf("1080p", "720p", "480p", "x265", "x264", "H.264", "H.265", "10bit", "5.1", "DDP5.1", "AAC2.0", "HEVC")) {
            for (name in listOf("Show.S02E06-$tag.mkv", "Show.S02E06.$tag.mkv", "Show.2x06-$tag.mkv", "Show 2x06 $tag")) {
                assertEquals(setOf(6), FilenameEpisodeMatcher.episodeClaims(name).single().episodes, name)
            }
        }
        val onlyTags = "Movie.1080p.x265.H.264.10bit.DDP5.1.5.1.2160p.mkv"
        assertEquals(emptyList(), FilenameEpisodeMatcher.episodeClaims(onlyTags))
        assertNull(FilenameEpisodeMatcher.releaseYear(onlyTags))
    }

    @Test
    fun `a number title is not read as the year`() {
        assertFalse(FilenameEpisodeMatcher.namesOtherYear("1917.2019.2160p.mkv", 2019, listOf("1917")))
        assertFalse(FilenameEpisodeMatcher.namesOtherYear("1917.2019.2160p.mkv", 2019))
        assertFalse(FilenameEpisodeMatcher.namesOtherYear("2001.A.Space.Odyssey.1968.mkv", 1968, listOf("2001: A Space Odyssey")))
        assertFalse(FilenameEpisodeMatcher.namesOtherYear("2001.A.Space.Odyssey.1968.mkv", 1968))
        assertFalse(FilenameEpisodeMatcher.namesOtherYear("Blade.Runner.2049.2017.2160p.mkv", 2017, listOf("Blade Runner 2049")))
        assertFalse(FilenameEpisodeMatcher.namesOtherYear("2012.2009.1080p.mkv", 2009, listOf("2012")))
        assertEquals(2017, FilenameEpisodeMatcher.releaseYear("Blade.Runner.2049.2017.2160p.mkv", listOf("Blade Runner 2049")))
        // A real mismatch is still seen once the title is out of the way.
        assertTrue(FilenameEpisodeMatcher.namesOtherYear("1917.1999.2160p.mkv", 2019, listOf("1917")))
    }

    @Test
    fun `several different years in one name are ambiguous and state no year`() {
        assertNull(FilenameEpisodeMatcher.releaseYear("Film.2017.Remaster.2020.mkv"))
        assertNull(FilenameEpisodeMatcher.releaseYear("Blade.Runner.2049.2017.2160p.mkv"))
        assertFalse(FilenameEpisodeMatcher.namesOtherYear("Film.2017.Remaster.2020.mkv", 2017))
    }

    @Test
    fun `a ten thousand character name is read quickly`() {
        val name = "Show.S01E01-E02.".repeat(625)
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        FilenameEpisodeMatcher.episodeClaims(name)
        FilenameEpisodeMatcher.releaseYear(name, listOf("Show"))
        FilenameEpisodeMatcher.namesOtherEpisode(name, 1, 5)
        assertTrue(name.length >= 10_000)
        assertTrue(started.elapsedNow() < kotlin.time.Duration.parse("2s"))
    }
}

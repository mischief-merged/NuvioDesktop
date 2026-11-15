package com.nuvio.app.features.details

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.SavedEpisode
import com.nuvio.app.features.downloads.SavedEpisodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedDetailsTest {
    private fun item(season: Int?, episode: Int?, title: String? = null) = DownloadItem(
        id = "d$season$episode", contentType = if (season == null) "movie" else "series", parentMetaId = "tt1",
        parentMetaType = if (season == null) "movie" else "series", videoId = if (season == null) "tt1" else "tt1:$season:$episode",
        title = "Show", poster = "https://img/p.jpg", seasonNumber = season, episodeNumber = episode, episodeTitle = title,
        episodeThumbnail = if (season == null) null else "https://img/t$episode.jpg", streamTitle = "s", providerName = "p",
        sourceUrl = "https://example.com/a.mkv", localFileUri = "file:/x/$season$episode.mkv", fileName = "$season$episode.mkv",
        status = DownloadStatus.Completed, createdAtEpochMs = 1, updatedAtEpochMs = 2,
    )

    private val list = SavedEpisodes(
        fetchedAtEpochMs = 5, showName = "The Show", parentMetaId = "tt1", parentMetaType = "series", imdbId = "tt1",
        poster = "https://img/list-poster.jpg",
        episodes = listOf(
            SavedEpisode(1, 1, "tt1:1:1", "Pilot", "2026-01-01T00:00:00Z", "https://img/a.jpg", "About"),
            SavedEpisode(1, 2, "tt1:1:2", "Second"),
            SavedEpisode(2, 1, "tt1:2:1", "Return"),
        ),
    )

    @Test
    fun a_title_with_no_downloads_has_no_saved_details() {
        assertNull(buildSavedMeta("series", "tt1", emptyList(), emptyList()))
    }

    @Test
    fun the_saved_list_gives_the_name_ids_poster_and_every_known_episode() {
        val meta = buildSavedMeta("series", "tt1", listOf(item(1, 1)), listOf(list))!!

        assertEquals("The Show", meta.name)
        assertEquals("https://img/list-poster.jpg", meta.poster)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), meta.videos.map { it.season!! to it.episode!! })
        assertEquals("Pilot", meta.videos.first().title)
        assertEquals("About", meta.videos.first().overview)
    }

    @Test
    fun only_downloaded_episodes_are_available_to_play() {
        val meta = buildSavedMeta("series", "tt1", listOf(item(1, 1)), listOf(list))!!

        assertEquals(listOf(true, false, false), meta.videos.map { it.available })
    }

    @Test
    fun a_download_without_a_saved_list_still_gives_its_own_episode_from_the_record() {
        val meta = buildSavedMeta("series", "tt1", listOf(item(3, 4, "Four")), emptyList())!!

        assertEquals("Show", meta.name)
        val video = meta.videos.single()
        assertEquals(Triple("tt1:3:4", "Four", true), Triple(video.id, video.title, video.available))
        assertEquals("https://img/t4.jpg", video.thumbnail)
        assertNull(video.overview)
        assertNull(video.released)
    }

    @Test
    fun a_movie_gives_a_title_and_poster_and_no_episodes() {
        val meta = buildSavedMeta("movie", "tt1", listOf(item(null, null)), emptyList())!!

        assertEquals("Show", meta.name)
        assertEquals("https://img/p.jpg", meta.poster)
        assertTrue(meta.videos.isEmpty())
        assertTrue(meta.trailers.isEmpty())
    }
}

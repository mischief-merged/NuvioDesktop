package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_enqueue_started
import nuvio.composeapp.generated.resources.downloads_movie_already_downloaded
import nuvio.composeapp.generated.resources.downloads_movie_not_found
import nuvio.composeapp.generated.resources.downloads_season_finished
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovieDownloadWiringTest {
    /** Keeps the stored payload in memory and never touches a file or the network. */
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null

        override fun loadPayload(): String? = payload

        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle = object : DownloadsTaskHandle {
            override fun cancel() = Unit
        }

        override fun removeFile(localFileUri: String?): Boolean = true

        override fun removePartialFile(destinationFileName: String): Boolean = true

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }

    private val movie = MetaDetails(id = "wiring-movie", type = "movie", name = "Movie", poster = "poster", background = "background")
    private val request = movie.movieBatchRequest(SeasonDownloadDefaults())
    private val realBackend = DownloadsRepository.backend

    @BeforeTest
    fun use_an_empty_in_memory_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = FakeBackend()
        DownloadsRepository.ensureLoaded()
        NuvioToastController.dismiss()
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    private fun text(resource: StringResource) = runBlocking { getString(resource) }

    private fun toastFor(result: SeasonBatchEpisodeResult) = runBlocking {
        SeasonBatchDownloads.summaryToast(request, SeasonBatchSummary(mapOf(request.episodes.single() to result), wasCancelled = false))
    }

    @Test
    fun a_movie_is_queued_as_a_movie_download_and_the_user_is_told_at_once() {
        val result = SeasonBatchDownloads.enqueueEpisode(
            request,
            request.episodes.single(),
            StreamItem(name = "1080p", url = "https://example.com/movie.mkv", addonName = "A", addonId = "addon:A"),
        )

        assertEquals(DownloadEnqueueResult.Started, result)
        val item = DownloadsRepository.uiState.value.items.single()
        assertEquals("wiring-movie", item.parentMetaId)
        assertEquals("wiring-movie", item.videoId)
        assertEquals("movie", item.contentType)
        assertEquals("Movie", item.title)
        assertEquals("poster", item.poster)
        assertEquals("background", item.background)
        assertNull(item.seasonNumber)
        assertNull(item.episodeNumber)
        assertEquals(false, item.isEpisode)
        assertEquals(text(Res.string.downloads_enqueue_started), NuvioToastController.currentToast.value?.message)
    }

    @Test
    fun an_episode_of_a_season_is_still_queued_as_an_episode_without_a_toast_of_its_own() {
        val episode = SeasonBatchEpisode("show:1:2", 1, 2, "Two", "thumb")
        val season = request.copy(parentMetaId = "show", parentMetaType = "series", contentType = "series", title = "Show", seasonNumber = 1, episodes = listOf(episode))

        SeasonBatchDownloads.enqueueEpisode(season, episode, StreamItem(name = "1080p", url = "https://example.com/e.mkv", addonName = "A", addonId = "addon:A"))

        val item = DownloadsRepository.uiState.value.items.single()
        assertEquals("show", item.parentMetaId)
        assertEquals(1, item.seasonNumber)
        assertEquals(2, item.episodeNumber)
        assertEquals("Two", item.episodeTitle)
        assertNull(NuvioToastController.currentToast.value)
    }

    @Test
    fun the_end_of_a_movie_download_is_reported_only_when_there_is_something_to_say() {
        assertEquals(text(Res.string.downloads_movie_not_found), toastFor(SeasonBatchEpisodeResult.NoMatchingSource))
        assertEquals(text(Res.string.download_failed), toastFor(SeasonBatchEpisodeResult.Failed))
        assertEquals(text(Res.string.downloads_movie_already_downloaded), toastFor(SeasonBatchEpisodeResult.AlreadyDownloaded))
        assertNull(toastFor(SeasonBatchEpisodeResult.Completed))
        assertNull(toastFor(SeasonBatchEpisodeResult.Paused))
        assertNull(toastFor(SeasonBatchEpisodeResult.Cancelled))
    }

    @Test
    fun a_season_still_ends_with_its_summary() {
        val episode = SeasonBatchEpisode("show:1:2", 1, 2, "Two", null)
        val season = request.copy(episodes = listOf(episode))

        val toast = runBlocking {
            SeasonBatchDownloads.summaryToast(season, SeasonBatchSummary(mapOf(episode to SeasonBatchEpisodeResult.NoMatchingSource), wasCancelled = false))
        }

        assertEquals(true, toast?.startsWith(text(Res.string.downloads_season_finished)))
    }
}

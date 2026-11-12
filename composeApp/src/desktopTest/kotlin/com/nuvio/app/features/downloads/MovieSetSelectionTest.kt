package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_movies_finished
import nuvio.composeapp.generated.resources.downloads_movies_stopped
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MovieSetSelectionTest {
    private fun movie(number: Int, released: String? = "1977-05-25", runtime: String? = null) =
        MetaPreview(id = "tmdb:$number", type = "movie", name = "Movie $number", poster = "poster$number", banner = "banner$number", rawReleaseDate = released, runtime = runtime)

    private val movies = (1..5).map { movie(it) }
    private val now = 1_700_000_000_000L

    private fun entries(downloads: List<DownloadItem> = emptyList(), set: List<MetaPreview> = movies) =
        movieSetEntries(set, downloads, todayIsoDate = "2023-11-14", nowEpochMs = now)

    @Test
    fun movies_already_in_downloads_and_unreleased_ones_are_told_apart_from_available_ones() {
        val downloads = listOf(
            movieItem("tmdb:1", DownloadStatus.Completed),
            movieItem("tmdb:2", DownloadStatus.Downloading),
            movieItem("tmdb:3", DownloadStatus.Paused),
            movieItem("tmdb:4", DownloadStatus.Failed),
            movieItem("tmdb:5", DownloadStatus.Completed).copy(seasonNumber = 1, episodeNumber = 1),
        )

        val states = entries(downloads, movies + movie(6, released = "2999-01-01")).map { it.state }

        assertEquals(
            listOf(
                MovieSetEntryState.InDownloads,
                MovieSetEntryState.InDownloads,
                MovieSetEntryState.InDownloads,
                MovieSetEntryState.Available,
                MovieSetEntryState.Available,
                MovieSetEntryState.Unreleased,
            ),
            states,
        )
    }

    @Test
    fun a_finished_download_that_is_only_a_placeholder_clip_does_not_count_as_in_downloads() {
        val clip = movieItem("tmdb:1", DownloadStatus.Completed).copy(downloadedBytes = 73_615)

        assertEquals(MovieSetEntryState.Available, entries(listOf(clip)).first().state)
    }

    @Test
    fun every_movie_that_can_be_downloaded_starts_ticked_and_the_others_do_not() {
        val selection = MovieSetSelection(entries(listOf(movieItem("tmdb:2", DownloadStatus.Completed)), movies + movie(6, released = "2999-01-01")))

        assertEquals(setOf("tmdb:1", "tmdb:3", "tmdb:4", "tmdb:5"), selection.selectedIds)
    }

    @Test
    fun a_movie_is_unticked_and_ticked_again_and_the_ticked_ones_keep_the_order_of_the_set() {
        val selection = MovieSetSelection(entries())

        selection.toggle(movies[1])
        selection.toggle(movies[3])
        assertEquals(listOf("tmdb:1", "tmdb:3", "tmdb:5"), selection.selectedMovies.map { it.id })
        assertFalse(selection.isSelected(movies[1]))

        selection.toggle(movies[1])
        assertEquals(listOf("tmdb:1", "tmdb:2", "tmdb:3", "tmdb:5"), selection.selectedMovies.map { it.id })
    }

    @Test
    fun a_movie_that_cannot_be_downloaded_can_never_be_ticked() {
        val unreleased = movie(6, released = "2999-01-01")
        val selection = MovieSetSelection(entries(listOf(movieItem("tmdb:1", DownloadStatus.Completed)), movies + unreleased))

        selection.toggle(movies[0])
        selection.toggle(unreleased)
        selection.clear()
        selection.selectAll()

        assertEquals(setOf("tmdb:2", "tmdb:3", "tmdb:4", "tmdb:5"), selection.selectedIds)
    }

    @Test
    fun clear_unticks_everything_and_select_all_ticks_everything_available() {
        val selection = MovieSetSelection(entries())

        selection.clear()
        assertEquals(emptySet(), selection.selectedIds)
        assertEquals(emptyList(), selection.selectedMovies)

        selection.selectAll()
        assertEquals(movies.map { it.id }.toSet(), selection.selectedIds)
    }

    @Test
    fun a_movie_listed_twice_is_one_entry() {
        assertEquals(5, entries(set = movies + movies[0]).size)
    }

    @Test
    fun a_set_request_has_one_movie_item_per_ticked_movie_and_runs_under_the_name_of_the_set() {
        val request = movieSetBatchRequest(
            setId = movieSetBatchId(movies),
            setName = "Star Wars Collection",
            movies = listOf(movie(1, runtime = "2h 1m"), movie(3)),
            choices = SeasonDownloadDefaults(BatchQuality.P1080, maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN),
        )

        assertTrue(request.isMovieSet)
        assertFalse(request.isSingleMovie)
        assertEquals("movie-set:tmdb:1", request.parentMetaId)
        assertEquals("Star Wars Collection", request.title)
        assertEquals(MovieBatchSeasonNumber, request.seasonNumber)
        assertEquals(listOf("tmdb:1", "tmdb:3"), request.episodes.map { it.videoId })
        assertEquals(listOf(1, 2), request.episodes.map { it.episodeNumber })
        assertEquals(listOf<Int?>(121, null), request.episodes.map { it.runtimeMinutes })
        assertEquals(SeasonBatchMovie("tmdb:3", "movie", "Movie 3", poster = "poster3", background = "banner3"), request.episodes[1].movie)
        assertEquals(BatchQuality.P1080, request.quality)
        assertEquals(10, request.maxBitrateMbps)
        assertEquals(DebridStreamLanguage.EN, request.audioLanguage)
    }

    @Test
    fun the_id_of_a_set_is_the_same_in_any_order_and_differs_between_sets_of_the_same_name() {
        assertEquals(movieSetBatchId(movies), movieSetBatchId(movies.reversed()))
        assertEquals(movieSetBatchId(movies), movieSetBatchId(movies.drop(1) + movies.first()))
        assertFalse(movieSetBatchId(movies) == movieSetBatchId((11..15).map { movie(it) }))
    }

    @Test
    fun a_set_of_one_ticked_movie_is_reported_like_a_single_movie() {
        val request = movieSetBatchRequest("movie-set:x", "Set", listOf(movie(1)), SeasonDownloadDefaults())

        assertTrue(request.isSingleMovie)
        assertFalse(request.isMovieSet)
    }

    @Test
    fun the_movies_of_a_set_are_queued_two_at_a_time_and_ones_already_in_downloads_are_skipped() = runTest {
        val state = MutableStateFlow(DownloadsUiState(listOf(movieItem("tmdb:2", DownloadStatus.Completed))))
        val queued = mutableListOf<String>()
        val summaries = mutableListOf<SeasonBatchSummary>()
        var mostAtOnce = 0
        val downloader = SeasonBatchDownloader(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
            downloads = state,
            fetchStreams = { item -> listOf(StreamItem(name = "1080p", url = "https://x/${item.videoId}.mp4", addonName = "A", addonId = "addon:A")) },
            prepareStream = { stream, _ -> stream },
            enqueue = { _, item, _ ->
                queued += item.videoId
                state.value = DownloadsUiState(state.value.items + movieItem(item.movie!!.metaId, DownloadStatus.Downloading))
                mostAtOnce = maxOf(mostAtOnce, state.value.items.count { it.status == DownloadStatus.Downloading })
                DownloadEnqueueResult.Started
            },
            onFinished = { _, summary -> summaries += summary },
        )
        fun finish(id: String) {
            state.value = DownloadsUiState(
                state.value.items.map { if (it.parentMetaId == id) it.copy(status = DownloadStatus.Completed, downloadedBytes = 2_000_000_000) else it },
            )
        }

        downloader.start(movieSetBatchRequest(movieSetBatchId(movies), "Set", movies, SeasonDownloadDefaults()))
        advanceUntilIdle()
        assertEquals(listOf("tmdb:1", "tmdb:3"), queued)
        assertEquals(SeasonBatchProgress(total = 5, finished = 1), downloader.running.value[seasonBatchKey(movieSetBatchId(movies), MovieBatchSeasonNumber)])
        listOf("tmdb:1", "tmdb:3", "tmdb:4", "tmdb:5").forEach {
            finish(it)
            advanceUntilIdle()
        }

        assertEquals(listOf("tmdb:1", "tmdb:3", "tmdb:4", "tmdb:5"), queued)
        assertEquals(2, mostAtOnce)
        val summary = summaries.single()
        assertEquals(4, summary.count(SeasonBatchEpisodeResult.Completed))
        assertEquals(1, summary.count(SeasonBatchEpisodeResult.AlreadyDownloaded))
    }

    @Test
    fun a_set_ends_with_a_summary_that_speaks_of_movies() = runBlocking {
        val request = movieSetBatchRequest("movie-set:x", "Set", movies.take(2), SeasonDownloadDefaults())
        val results = request.episodes.associateWith { SeasonBatchEpisodeResult.Completed }

        val finished = SeasonBatchDownloads.summaryToast(request, SeasonBatchSummary(results, wasCancelled = false))
        val stopped = SeasonBatchDownloads.summaryToast(request, SeasonBatchSummary(results, wasCancelled = true))

        assertEquals(true, finished?.startsWith(getString(Res.string.downloads_movies_finished)))
        assertEquals(true, stopped?.startsWith(getString(Res.string.downloads_movies_stopped)))
    }
}

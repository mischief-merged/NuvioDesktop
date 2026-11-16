package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The episode list saved beside a download, and the next-episode flow that uses it with no details and no network. */
class EpisodeListSidecarTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val files = mutableMapOf<String, DownloadedFileFacts>()
        var finish: ((String, Long?) -> Unit)? = null
        var progress: ((Long, Long?) -> Unit)? = null
        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) { this.payload = payload }
        override fun restoreItem(item: DownloadItem): DownloadItem = item
        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            finish = onSuccess; progress = onProgress
            return object : DownloadsTaskHandle { override fun cancel() = Unit }
        }
        override fun removeFile(localFileUri: String?): Boolean = true
        override fun removePartialFile(destinationFileName: String): Boolean = true
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri?.takeIf { it in files }
        override fun inspectFile(localFileUri: String): DownloadedFileFacts = files[localFileUri] ?: DownloadedFileFacts(exists = false)
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private val realCompanions = DownloadsRepository.companions
    private val realGuard = DownloadCompanionGuard.stillWanted
    private lateinit var folder: File
    private var now = 1_700_000_000_000L
    private var meta: MetaDetails? = null
    private var fetches = 0

    private fun video(season: Int, episode: Int, title: String = "Episode $episode") = MetaVideo(
        id = "tt1:$season:$episode", title = title, released = "2026-01-0${episode}T00:00:00Z", thumbnail = "https://img/$season$episode.jpg",
        season = season, episode = episode, overview = "About $episode",
    )

    private val show = MetaDetails(
        id = "tt1", type = "series", name = "Show", imdbId = "tt1", poster = "https://img/poster.jpg",
        videos = listOf(video(1, 1), video(1, 2), video(1, 3), video(2, 1), video(2, 2), video(3, 1)),
    )

    private fun companion() = EpisodeListCompanion(fetchMeta = { _, _ -> fetches++; meta }, nowEpochMs = { now })

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("nuvio-episodes").toFile()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadCompanionGuard.stillWanted = { true }
        DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
    }

    @AfterTest
    fun tearDown() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.companions = realCompanions
        DownloadCompanionGuard.stillWanted = realGuard
        folder.deleteRecursively()
    }

    private fun uriOf(name: String) = File(folder, name).also { it.writeText("v") }.toURI().toString()

    private fun item(uri: String, season: Int = 1, episode: Int = 1) = DownloadItem(
        id = "d$season$episode", contentType = "series", parentMetaId = "tt1", parentMetaType = "series", videoId = "tt1:$season:$episode",
        title = "Show", seasonNumber = season, episodeNumber = episode, streamTitle = "s", providerName = "p",
        sourceUrl = "https://example.com/a.mkv", localFileUri = uri, fileName = File(java.net.URI(uri)).name,
        status = DownloadStatus.Completed, createdAtEpochMs = 1, updatedAtEpochMs = 2,
    )

    @Test
    fun the_season_and_the_first_episode_of_the_next_season_are_kept() {
        val saved = assertNotNull(show.savedEpisodesFor(1, now))

        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1), saved.episodes.map { it.season to it.episode })
        assertEquals(Triple("Show", "tt1", "https://img/poster.jpg"), Triple(saved.showName, saved.parentMetaId, saved.poster))
        assertEquals("https://img/11.jpg", saved.episodes.first().thumbnail)
    }

    @Test
    fun a_show_without_episodes_of_that_season_saves_nothing() {
        assertNull(show.savedEpisodesFor(9, now))
        assertNull(show.copy(videos = emptyList()).savedEpisodesFor(1, now))
    }

    @Test
    fun the_episode_list_is_written_beside_the_file_and_read_back() = runBlocking {
        val uri = uriOf("Show.S01E01.mkv")
        meta = show

        assertEquals(CompanionOutcome.Saved, companion().run(item(uri), uri))

        assertTrue(File(folder, "Show.S01E01.mkv.subtitles/episodes.json").isFile)
        assertEquals(4, EpisodeListSidecar.read(uri)!!.episodes.size)
        assertEquals(listOf("tt1:1:1", "tt1:1:2", "tt1:1:3", "tt1:2:1"), savedEpisodeVideosFor(uri).map { it.id })
    }

    @Test
    fun a_failed_fetch_saves_nothing_and_a_saved_list_is_left_alone() = runBlocking {
        val uri = uriOf("a.mkv")
        meta = show
        companion().run(item(uri), uri)
        val before = File(folder, "a.mkv.subtitles/episodes.json").readText()

        meta = null
        assertEquals(CompanionOutcome.Failed, companion().run(item(uri), uri))

        assertEquals(before, File(folder, "a.mkv.subtitles/episodes.json").readText())
    }

    @Test
    fun a_later_save_adds_and_updates_but_never_drops_a_saved_episode() = runBlocking {
        val uri = uriOf("a.mkv")
        meta = show
        companion().run(item(uri), uri)

        meta = show.copy(videos = listOf(video(1, 1, "Renamed"), video(1, 2)))
        companion().run(item(uri), uri)

        val saved = EpisodeListSidecar.read(uri)!!
        assertEquals(4, saved.episodes.size)
        assertEquals("Renamed", saved.episodes.first().title)
    }

    @Test
    fun a_corrupt_or_unknown_version_file_is_ignored_and_a_newer_one_is_not_replaced() = runBlocking {
        val uri = uriOf("a.mkv")
        val file = File(folder, "a.mkv.subtitles/episodes.json").also { it.parentFile.mkdirs() }
        file.writeText("{ nope")
        assertNull(EpisodeListSidecar.read(uri))
        file.writeText("{\"version\":2,\"fetchedAtEpochMs\":5}")
        assertNull(EpisodeListSidecar.read(uri))

        meta = show
        assertEquals(CompanionOutcome.Failed, companion().run(item(uri), uri))
        assertEquals("{\"version\":2,\"fetchedAtEpochMs\":5}", file.readText())
    }

    @Test
    fun an_episode_list_is_due_when_missing_or_old_and_a_movie_never() = runBlocking {
        val uri = uriOf("a.mkv")
        assertTrue(companion().isDue(item(uri), uri))
        meta = show
        companion().run(item(uri), uri)
        assertFalse(companion().isDue(item(uri), uri))
        now += EpisodeListCompanion.RefreshAgeMs
        assertTrue(companion().isDue(item(uri), uri))
        assertFalse(companion().isDue(item(uri).copy(seasonNumber = null, episodeNumber = null), uri))
    }

    @Test
    fun the_shared_pass_saves_episode_lists_for_older_downloads() = runBlocking {
        val uri = uriOf("a.mkv")
        meta = show
        val companions = DownloadCompanions(listOf(companion()), { true }, { now }, CoroutineScope(Dispatchers.Unconfined), {}, Dispatchers.Default)

        val summary = companions.backfill(listOf(item(uri))) { it.localFileUri }

        assertEquals(1, summary.saved)
        assertNotNull(EpisodeListSidecar.read(uri))
        Unit
    }

    @Test
    fun after_a_restart_with_no_details_and_no_network_the_next_downloaded_episode_is_offered_and_found() = runBlocking {
        // Two episodes are downloaded; the list was saved while online.
        val first = download("Show.S01E01.mkv", 1)
        val second = download("Show.S01E02.mkv", 2)
        meta = show
        companion().run(item(first), first)

        // A restart: nothing in memory, no details, no network.
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        meta = null
        val videos = savedEpisodeVideosFor(first)

        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 1)
        assertEquals("tt1:1:2", next?.id)
        val downloaded = DownloadsRepository.findPlayableDownload("tt1", next!!.season, next.episode, next.id)
        assertNotNull(downloaded)
        assertEquals(second, DownloadsRepository.playableLocalFileUri(downloaded))
        // The last episode saved of the season leads on to the first of the next, which is not downloaded.
        val third = PlayerNextEpisodeRules.resolveNextEpisode(videos, 1, 3)
        assertEquals("tt1:2:1", third?.id)
        assertNull(DownloadsRepository.findPlayableDownload("tt1", third!!.season, third.episode, third.id))
    }

    private fun download(name: String, episode: Int): String {
        val uri = uriOf(name)
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:$episode", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = episode, episodeTitle = null,
            episodeThumbnail = null, stream = StreamItem(name = "1080p", url = "https://example.com/e$episode.mkv", addonName = "A", addonId = "a"),
        )
        backend.files[uri] = DownloadedFileFacts(exists = true, sizeBytes = 1_000, isVideoContainer = true)
        backend.progress!!(0, 1_000)
        backend.finish!!(uri, 1_000)
        return uri
    }

    // ---- existing downloads get their list, loaded once per show ----

    private fun episodeItem(uri: String, episode: Int) = item(uri, season = 1, episode = episode)

    @Test
    fun several_episodes_of_one_show_load_the_details_once() = runBlocking {
        meta = show
        val uris = (1..4).map { uriOf("e$it.mkv") }
        val companions = DownloadCompanions(listOf(companion()), { true }, { now }, CoroutineScope(Dispatchers.Unconfined), {}, Dispatchers.Default)

        val summary = companions.backfill(uris.mapIndexed { i, uri -> episodeItem(uri, i + 1) }) { it.localFileUri }

        assertEquals(4, summary.saved)
        assertEquals(1, fetches, "the show was loaded more than once")
        assertTrue(uris.all { EpisodeListSidecar.read(it) != null })
    }

    @Test
    fun a_failed_load_is_not_cached_and_a_loaded_show_is_cached_only_for_a_while() = runBlocking {
        val uriA = uriOf("a.mkv")
        val uriB = uriOf("b.mkv")
        meta = null
        assertEquals(CompanionOutcome.Failed, companion().run(episodeItem(uriA, 1), uriA))
        meta = show
        assertEquals(CompanionOutcome.Saved, companion().run(episodeItem(uriA, 1), uriA))
        assertEquals(2, fetches)

        val sameCompanion = companion()
        sameCompanion.run(episodeItem(uriB, 2), uriB)
        val after = fetches
        sameCompanion.run(episodeItem(uriB, 2).copy(id = "other"), uriA.replace("a.mkv", "b.mkv"))
        now += EpisodeListCompanion.ShowCacheMs + 1
        File(java.net.URI(uriB)).parentFile.resolve("b.mkv.subtitles").deleteRecursively()
        sameCompanion.run(episodeItem(uriB, 2), uriB)
        assertEquals(after + 1, fetches)
    }

    @Test
    fun a_movie_has_no_episode_list_and_nothing_is_loaded_for_it() = runBlocking {
        val uri = uriOf("m.mkv")
        meta = show
        val movie = item(uri).copy(contentType = "movie", parentMetaType = "movie", seasonNumber = null, episodeNumber = null)

        assertFalse(companion().isDue(movie, uri))
        assertEquals(0, fetches)
    }

    @Test
    fun a_pass_that_failed_because_details_were_not_ready_is_tried_again_and_then_fills_everything_in() = runBlocking {
        val uris = (1..3).map { uriOf("r$it.mkv") }
        val items = uris.mapIndexed { i, uri -> episodeItem(uri, i + 1) }
        meta = null
        var rounds = 0
        val companions = DownloadCompanions(
            listOf(companion()), { true }, { now }, CoroutineScope(Dispatchers.Unconfined),
            pause = { ms -> now += ms; if (++rounds == 1) meta = show }, Dispatchers.Default,
        )

        val summary = companions.backfillUntilDone({ items }) { it.localFileUri }

        assertTrue(summary.saved >= 3)
        assertTrue(uris.all { EpisodeListSidecar.read(it) != null })
    }

    @Test
    fun the_retries_are_bounded() = runBlocking {
        val uri = uriOf("x.mkv")
        meta = null
        val companions = DownloadCompanions(
            listOf(companion()), { true }, { now }, CoroutineScope(Dispatchers.Unconfined), pause = { ms -> now += ms }, Dispatchers.Default,
        )

        companions.backfillUntilDone({ listOf(episodeItem(uri, 1)) }) { it.localFileUri }

        assertTrue(fetches <= DownloadCompanions.BackfillMaxRounds, "fetches $fetches")
        assertNull(EpisodeListSidecar.read(uri))
    }
}

package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.skip.SkipInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Skip times can only be looked up where a service is set up. A download for which none is must
 * cause no lookup, no failure, no marker and no "none found" line.
 */
class SkipServiceAvailabilityTest {
    private fun request(isMovie: Boolean = false, videoId: String = "tt1:1:1", imdb: String? = "tt1") =
        SkipTimesRequest(isMovie, "tt1", videoId, 1, 1, imdb)

    @Test
    fun which_lookups_are_possible_with_which_services() {
        data class Case(val label: String, val request: SkipTimesRequest, val introDb: Boolean, val simkl: Boolean, val possible: Boolean)
        val cases = listOf(
            Case("nothing set up, episode", request(), false, false, false),
            Case("nothing set up, movie", request(isMovie = true, videoId = "tt1"), false, false, false),
            Case("episode, only the main service", request(), true, false, true),
            Case("episode, only Simkl", request(), false, true, true),
            Case("movie, only the main service", request(isMovie = true, videoId = "tt1"), true, false, true),
            Case("movie, only Simkl", request(isMovie = true, videoId = "tt1"), false, true, false),
            Case("MAL id needs no key: AniSkip is open", request(videoId = "mal:123:4", imdb = null), false, false, true),
            Case("Kitsu id, nothing set up", request(videoId = "kitsu:5:6", imdb = null), false, false, false),
            Case("Kitsu id, only Simkl", request(videoId = "kitsu:5:6", imdb = null), false, true, true),
            Case("Kitsu id with an IMDb id and the main service", request(videoId = "kitsu:5:6", imdb = "tt9"), true, false, true),
            Case("Kitsu id without an IMDb id, only the main service", request(videoId = "kitsu:5:6", imdb = null), true, false, false),
            Case("an id nothing can look up", request(videoId = "yt:abc", imdb = null), true, true, false),
        )
        for (c in cases) {
            assertEquals(c.possible, skipLookupPossible(c.request, introDbConfigured = c.introDb, simklConfigured = c.simkl), c.label)
        }
    }

    // ---- with the service unavailable nothing happens ----

    private lateinit var folder: File
    private val realGuard = DownloadCompanionGuard.stillWanted
    private val calls = AtomicInteger()

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("nuvio-skip-unavailable").toFile()
        DownloadCompanionGuard.stillWanted = { true }
    }

    @AfterTest
    fun tearDown() {
        DownloadCompanionGuard.stillWanted = realGuard
        folder.deleteRecursively()
    }

    private val unavailable = object : SkipTimesFetcher {
        override suspend fun fetch(request: SkipTimesRequest): SkipFetchResult { calls.incrementAndGet(); return SkipFetchResult.Failed }
        override fun canLookUp(request: SkipTimesRequest) = false
    }

    private fun uri() = File(folder, "a.mkv").also { it.writeText("v") }.toURI().toString()
    private fun item(uri: String, id: String = "d1") = DownloadItem(
        id = id, contentType = "series", parentMetaId = "tt1", parentMetaType = "series", videoId = "tt1:1:1", title = "Show",
        seasonNumber = 1, episodeNumber = 1, streamTitle = "s", providerName = "p", sourceUrl = "https://example.com/a.mkv",
        localFileUri = uri, fileName = "a.mkv", status = DownloadStatus.Completed, createdAtEpochMs = 1, updatedAtEpochMs = 2,
    )

    @Test
    fun an_unavailable_service_is_not_due_asks_nobody_and_writes_nothing() = runBlocking {
        val uri = uri()
        val skipTimes = OfflineSkipTimes(fetcher = unavailable, isOnline = { true })

        assertFalse(skipTimes.isDue(item(uri), uri))
        assertEquals(CompanionOutcome.Busy, skipTimes.run(item(uri), uri))
        assertNull(skipTimes.refresh(SkipTimesRequest(false, "tt1", "tt1:1:1", 1, 1), uri))

        assertEquals(0, calls.get())
        assertEquals(SavedSkipTimesRead.Missing, SkipTimesSidecar.read(uri))
        assertNull(skipTimes.note(uri), "the row must not imply a lookup happened")
        assertFalse(File(folder, "a.mkv.subtitles").exists())
    }

    @Test
    fun a_pass_over_downloads_whose_only_missing_piece_is_an_unavailable_service_neither_fails_nor_stops() = runBlocking {
        val items = (1..6).map { item(File(folder, "e$it.mkv").also { f -> f.writeText("v") }.toURI().toString(), "d$it") }
        val companions = DownloadCompanions(
            listOf(OfflineSkipTimes(fetcher = unavailable, isOnline = { true })),
            { true }, { 1L }, CoroutineScope(Dispatchers.Unconfined), {}, Dispatchers.Default,
        )

        val summary = companions.backfill(items) { it.localFileUri }

        assertEquals(0, summary.looked)
        assertEquals(0, summary.failed)
        assertFalse(summary.stoppedEarly)
        assertEquals(0, calls.get())
    }

    @Test
    fun a_service_that_is_set_up_is_still_asked() = runBlocking {
        val uri = uri()
        val available = SkipTimesFetcher { calls.incrementAndGet(); SkipFetchResult.Found(listOf(SkipInterval(1.0, 2.0, "intro", "introdb"))) }
        val skipTimes = OfflineSkipTimes(fetcher = available, isOnline = { true })

        assertTrue(skipTimes.isDue(item(uri), uri))
        assertEquals(CompanionOutcome.Saved, skipTimes.run(item(uri), uri))
        assertEquals(1, calls.get())
    }
}

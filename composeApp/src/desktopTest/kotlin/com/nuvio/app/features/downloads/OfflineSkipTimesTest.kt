package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.skip.SkipInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/** Saving, using and refreshing skip times with a fake service, a fake clock and temporary files. */
class OfflineSkipTimesTest {
    private lateinit var folder: File
    private var now = 1_700_000_000_000L
    private var online = true
    private val calls = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val mostInFlight = AtomicInteger()
    private var answer: (SkipTimesRequest) -> SkipFetchResult = { SkipFetchResult.NoneFound }

    private val intro = SkipInterval(10.0, 90.0, "intro", "introdb")
    private val outro = SkipInterval(1300.0, 1400.0, "outro", "introdb")

    private val fetcher = SkipTimesFetcher { request ->
        calls.incrementAndGet()
        val now = inFlight.incrementAndGet()
        mostInFlight.updateAndGet { maxOf(it, now) }
        try {
            delay(5)
            answer(request)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun skipTimes() = OfflineSkipTimes(fetcher = fetcher, isOnline = { online }, nowEpochMs = { now })

    private fun companions(skipTimes: OfflineSkipTimes = skipTimes()) = DownloadCompanions(
        companions = listOf(skipTimes),
        isOnline = { online },
        nowEpochMs = { now },
        scope = CoroutineScope(Dispatchers.Unconfined),
        pause = {},
        io = Dispatchers.Default,
    )

    private val realGuard = DownloadCompanionGuard.stillWanted
    private var stillWanted = true

    @BeforeTest
    fun create_a_temporary_folder() {
        folder = Files.createTempDirectory("nuvio-offline-skip").toFile()
        DownloadCompanionGuard.stillWanted = { stillWanted }
    }

    @AfterTest
    fun remove_the_temporary_folder() {
        DownloadCompanionGuard.stillWanted = realGuard
        folder.deleteRecursively()
    }

    private fun video(name: String = "Show.S01E01.mkv") = File(folder, name).also { it.writeText("video") }.toURI().toString()

    private fun request(episode: Int = 1) = SkipTimesRequest(
        isMovie = false, parentMetaId = "tt1", videoId = "tt1:1:$episode", season = 1, episode = episode,
    )

    private fun completed(id: String, uri: String?) = DownloadItem(
        id = id, contentType = "series", parentMetaId = "tt1", parentMetaType = "series", videoId = "tt1:1:${id.removePrefix("d")}",
        title = "Show", seasonNumber = 1, episodeNumber = id.removePrefix("d").toIntOrNull() ?: 1, streamTitle = "1080p",
        providerName = "A", sourceUrl = "https://example.com/$id.mkv", localFileUri = uri, fileName = "$id.mkv",
        status = DownloadStatus.Completed, createdAtEpochMs = 1, updatedAtEpochMs = 2,
    )

    private fun read(uri: String) = SkipTimesSidecar.read(uri)

    private val day = 24L * 60 * 60 * 1000

    // ---- saved at completion ----

    @Test
    fun skip_times_are_saved_when_a_download_finishes_whether_or_not_skip_intro_is_on() = runBlocking {
        val uri = video()
        answer = { SkipFetchResult.Found(listOf(intro, outro)) }

        companions().saveAfterDownload(completed("d1", uri), uri).join()

        assertEquals(listOf(intro, outro), (read(uri) as SavedSkipTimesRead.Saved).times.intervals)
        assertEquals(now, (read(uri) as SavedSkipTimesRead.Saved).times.fetchedAtEpochMs)
    }

    @Test
    fun a_download_that_finishes_offline_saves_nothing_and_asks_nobody() = runBlocking {
        val uri = video()
        online = false

        companions().saveAfterDownload(completed("d1", uri), uri).join()

        assertEquals(0, calls.get())
        assertEquals(SavedSkipTimesRead.Missing, read(uri))
    }

    @Test
    fun a_failed_lookup_at_completion_records_nothing() = runBlocking {
        val uri = video()
        answer = { SkipFetchResult.Failed }

        companions().saveAfterDownload(completed("d1", uri), uri).join()

        assertEquals(SavedSkipTimesRead.Missing, read(uri))
    }

    // ---- used with no network ----

    @Test
    fun saved_intervals_are_available_at_once_with_no_network_and_no_lookup() {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(now, listOf(intro, outro)))
        online = false
        answer = { error("the service must not be asked") }

        assertEquals(listOf(intro, outro), skipTimes().saved(uri))
        assertEquals(0, calls.get())
    }

    @Test
    fun a_file_with_no_sidecar_has_no_saved_intervals() {
        assertEquals(emptyList(), skipTimes().saved(video()))
    }

    @Test
    fun offline_a_refresh_does_not_ask_and_changes_nothing() = runBlocking {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(now - 2 * day, listOf(intro)))
        online = false

        assertNull(skipTimes().refresh(request(), uri))
        assertEquals(0, calls.get())
        assertEquals(listOf(intro), skipTimes().saved(uri))
    }

    // ---- background refresh ----

    @Test
    fun a_refresh_that_returns_something_different_replaces_the_saved_intervals_and_reports_them() = runBlocking {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(now - 2 * day, listOf(intro)))
        answer = { SkipFetchResult.Found(listOf(intro.copy(endTime = 95.0), outro)) }

        val changed = skipTimes().refresh(request(), uri)

        assertEquals(listOf(intro.copy(endTime = 95.0), outro), changed)
        assertEquals(changed, skipTimes().saved(uri))
    }

    @Test
    fun a_refresh_that_returns_the_same_intervals_reports_no_change_but_restarts_the_age() = runBlocking {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(now - 2 * day, listOf(intro)))
        answer = { SkipFetchResult.Found(listOf(intro)) }

        assertNull(skipTimes().refresh(request(), uri))
        assertEquals(now, (read(uri) as SavedSkipTimesRead.Saved).times.fetchedAtEpochMs)
    }

    @Test
    fun a_failed_refresh_leaves_the_saved_file_exactly_as_it_was() = runBlocking {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(now - 2 * day, listOf(intro)))
        val before = read(uri)
        answer = { SkipFetchResult.Failed }

        assertNull(skipTimes().refresh(request(), uri))
        assertEquals(before, read(uri))
    }

    @Test
    fun an_answer_with_nothing_never_removes_saved_intervals() = runBlocking {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(now - 2 * day, listOf(intro)))
        answer = { SkipFetchResult.NoneFound }

        assertNull(skipTimes().refresh(request(), uri))
        assertEquals(listOf(intro), skipTimes().saved(uri))
    }

    @Test
    fun saved_intervals_are_not_asked_about_again_within_the_refresh_age() = runBlocking {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(now - day / 2, listOf(intro)))

        assertNull(skipTimes().refresh(request(), uri))
        assertEquals(0, calls.get())

        now += day
        answer = { SkipFetchResult.Found(listOf(outro)) }
        assertEquals(listOf(outro), skipTimes().refresh(request(), uri))
        assertEquals(1, calls.get())
    }

    @Test
    fun a_lookup_that_is_slow_does_not_touch_the_saved_intervals_until_it_answers() = runBlocking {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(now - 2 * day, listOf(intro)))
        val slow = OfflineSkipTimes(
            fetcher = { delay(60_000); SkipFetchResult.Found(listOf(outro)) },
            isOnline = { true }, nowEpochMs = { now },
        )
        val job = CoroutineScope(Dispatchers.Default).launch { slow.refresh(request(), uri) }
        delay(50)

        assertEquals(listOf(intro), slow.saved(uri))
        job.cancelAndJoin()
        assertEquals(listOf(intro), slow.saved(uri))
    }

    // ---- none found ----

    @Test
    fun none_found_is_recorded_with_its_time_and_is_not_asked_again_before_the_age_limit() = runBlocking {
        val uri = video()
        answer = { SkipFetchResult.NoneFound }
        val skipTimes = skipTimes()

        assertNull(skipTimes.refresh(request(), uri))
        val saved = (read(uri) as SavedSkipTimesRead.Saved).times
        assertTrue(saved.noneFound)
        assertEquals(now, saved.fetchedAtEpochMs)
        assertEquals(1, calls.get())

        now += OfflineSkipTimes.NoneFoundRecheckAgeMs - 1
        assertNull(skipTimes.refresh(request(), uri))
        assertEquals(1, calls.get())

        now += 1
        answer = { SkipFetchResult.Found(listOf(intro)) }
        assertEquals(listOf(intro), skipTimes.refresh(request(), uri))
        assertEquals(2, calls.get())
    }

    @Test
    fun the_notes_of_a_row_say_saved_none_found_or_nothing() {
        val uri = video()
        assertNull(skipTimes().note(uri))
        SkipTimesSidecar.write(uri, SavedSkipTimes(now, emptyList()))
        assertEquals(SkipTimesNote.NoneFound, skipTimes().note(uri))
        SkipTimesSidecar.write(uri, SavedSkipTimes(now, listOf(intro)))
        assertEquals(SkipTimesNote.Saved, skipTimes().note(uri))
    }

    @Test
    fun a_corrupt_file_counts_as_missing_and_is_replaced_by_a_lookup() = runBlocking {
        val uri = video()
        // A corrupt file in the app's own (marked) folder.
        val folder = File(File(java.net.URI(uri)).path + ".subtitles")
        assertTrue(SidecarOwnership.ensureOwned(folder.toPath(), File(java.net.URI(uri)).name))
        File(folder, SkipTimesSidecar.FILE_NAME).writeText("<<<")
        answer = { SkipFetchResult.Found(listOf(intro)) }

        assertEquals(listOf(intro), skipTimes().refresh(request(), uri))
    }

    // ---- backfill ----

    @Test
    fun backfill_fills_in_downloads_without_a_sidecar_and_leaves_others_alone() = runBlocking {
        val bare = video("a.mkv")
        val withTimes = video("b.mkv").also { SkipTimesSidecar.write(it, SavedSkipTimes(now - 5 * day, listOf(intro))) }
        val freshNone = video("c.mkv").also { SkipTimesSidecar.write(it, SavedSkipTimes(now - day, emptyList())) }
        val staleNone = video("d.mkv").also { SkipTimesSidecar.write(it, SavedSkipTimes(now - 20 * day, emptyList())) }
        val items = listOf(completed("d1", bare), completed("d2", withTimes), completed("d3", freshNone), completed("d4", staleNone))
        answer = { SkipFetchResult.Found(listOf(outro)) }

        val summary = companions().backfill(items) { it.localFileUri }

        assertEquals(2, summary.looked)
        assertEquals(2, summary.saved)
        assertEquals(listOf(outro), skipTimes().saved(bare))
        assertEquals(listOf(outro), skipTimes().saved(staleNone))
        assertEquals(listOf(intro), skipTimes().saved(withTimes))
        assertTrue((read(freshNone) as SavedSkipTimesRead.Saved).times.noneFound)
    }

    @Test
    fun backfill_skips_a_download_whose_file_or_drive_is_not_available() = runBlocking {
        val here = video("a.mkv")
        val items = listOf(completed("d1", here), completed("d2", null), completed("d3", "file:/Volumes/Gone/c.mkv"))
        answer = { SkipFetchResult.Found(listOf(intro)) }

        val summary = companions().backfill(items) { item -> item.localFileUri?.takeIf { it == here } }

        assertEquals(1, summary.looked)
        assertEquals(2, summary.unavailable)
        assertEquals(SavedSkipTimesRead.Missing, read("file:/Volumes/Gone/c.mkv"))
    }

    @Test
    fun backfill_ignores_downloads_that_are_not_finished() = runBlocking {
        val uri = video("a.mkv")

        val summary = companions().backfill(listOf(completed("d1", uri).copy(status = DownloadStatus.Downloading))) { it.localFileUri }

        assertEquals(0, summary.looked)
        assertEquals(0, calls.get())
    }

    @Test
    fun backfill_makes_a_bounded_number_of_lookups_per_pass_and_only_a_few_at_a_time() = runBlocking {
        val items = (1..(DownloadCompanions.BackfillMaxPerPass + 12)).map { completed("d$it", video("e$it.mkv")) }
        answer = { SkipFetchResult.Found(listOf(intro)) }

        val summary = companions().backfill(items) { it.localFileUri }

        assertEquals(DownloadCompanions.BackfillMaxPerPass, summary.looked)
        assertEquals(DownloadCompanions.BackfillMaxPerPass, calls.get())
        assertTrue(summary.stoppedEarly)
        assertTrue(mostInFlight.get() in 1..DownloadCompanions.BackfillConcurrency, "most at once: ${mostInFlight.get()}")

        // The next pass carries on with the rest.
        val next = companions().backfill(items) { it.localFileUri }
        assertEquals(12, next.looked)
    }

    @Test
    fun backfill_waits_between_groups_of_lookups() = runBlocking {
        val waits = mutableListOf<Long>()
        val skipTimes = DownloadCompanions(listOf(skipTimes()), { online }, { now }, CoroutineScope(Dispatchers.Unconfined), { waits += it }, Dispatchers.Default)
        val items = (1..5).map { completed("d$it", video("w$it.mkv")) }
        answer = { SkipFetchResult.Found(listOf(intro)) }

        skipTimes.backfill(items) { it.localFileUri }

        assertEquals(List(2) { DownloadCompanions.BackfillPauseMs }, waits)
    }

    @Test
    fun backfill_stops_when_the_connection_is_lost() = runBlocking {
        val items = (1..10).map { completed("d$it", video("o$it.mkv")) }
        answer = { online = false; SkipFetchResult.Found(listOf(intro)) }

        val summary = companions().backfill(items) { it.localFileUri }

        assertEquals(DownloadCompanions.BackfillConcurrency, summary.looked)
        assertTrue(summary.stoppedEarly)
    }

    @Test
    fun backfill_stops_after_failures_in_a_row_and_does_not_retry_those_downloads_soon() = runBlocking {
        val items = (1..10).map { completed("d$it", video("f$it.mkv")) }
        answer = { SkipFetchResult.Failed }
        val skipTimes = companions()

        val first = skipTimes.backfill(items) { it.localFileUri }
        assertTrue(first.stoppedEarly)
        assertTrue(first.failed >= DownloadCompanions.BackfillStopAfterFailures)
        assertTrue(items.all { read(it.localFileUri!!) == SavedSkipTimesRead.Missing })

        val callsAfterFirst = calls.get()
        answer = { SkipFetchResult.Found(listOf(intro)) }
        skipTimes.backfill(items) { it.localFileUri }
        // Those that failed wait; the rest are looked up.
        assertEquals(callsAfterFirst + (10 - first.failed).coerceAtMost(DownloadCompanions.BackfillMaxPerPass), calls.get())

        now += DownloadCompanions.BackfillRetryAfterMs
        val retry = skipTimes.backfill(items) { it.localFileUri }
        assertTrue(retry.looked >= first.failed)
    }

    @Test
    fun backfill_does_nothing_offline() = runBlocking {
        online = false
        val summary = companions().backfill(listOf(completed("d1", video("a.mkv")))) { it.localFileUri }

        assertEquals(0, calls.get())
        assertTrue(summary.stoppedEarly)
    }

    @Test
    fun only_one_backfill_pass_runs_at_a_time() = runBlocking {
        val items = (1..4).map { completed("d$it", video("p$it.mkv")) }
        answer = { SkipFetchResult.Found(listOf(intro)) }
        val skipTimes = companions()

        val results = listOf(
            async(Dispatchers.Default) { skipTimes.backfill(items) { it.localFileUri } },
            async(Dispatchers.Default) { skipTimes.backfill(items) { it.localFileUri } },
        ).map { it.await() }

        assertEquals(4, results.sumOf { it.looked })
        assertEquals(4, calls.get())
    }

    @Test
    fun the_limits_are_small() {
        assertTrue(DownloadCompanions.BackfillConcurrency <= 3)
        assertTrue(DownloadCompanions.BackfillMaxPerPass <= 50)
        assertTrue(DownloadCompanions.BackfillStartDelayMs >= 10_000)
        assertFalse(OfflineSkipTimes.NoneFoundRecheckAgeMs < 7 * day)
    }
}

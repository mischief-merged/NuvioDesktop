package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.skip.SkipInterval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What must never happen to saved sidecar data: overwritten by a partial or empty answer, torn, raced, or written for nothing. */
class SidecarSafetyTest {
    private lateinit var folder: File
    private var now = 1_700_000_000_000L
    private var online = true
    private var stillWanted = true
    private val realGuard = DownloadCompanionGuard.stillWanted
    private val day = 24L * 60 * 60 * 1000
    private val intro = SkipInterval(10.0, 90.0, "intro", "introdb")
    private val recap = SkipInterval(0.0, 8.0, "recap", "introdb")
    private val credits = SkipInterval(1300.0, 1400.0, "outro", "introdb")
    private val calls = AtomicInteger()
    private var answer: suspend () -> SkipFetchResult = { SkipFetchResult.NoneFound }

    private fun skipTimes() = OfflineSkipTimes(
        fetcher = { calls.incrementAndGet(); answer() },
        isOnline = { online },
        nowEpochMs = { now },
    )

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("nuvio-sidecar-safety").toFile()
        DownloadCompanionGuard.stillWanted = { stillWanted }
    }

    @AfterTest
    fun tearDown() {
        DownloadCompanionGuard.stillWanted = realGuard
        folder.deleteRecursively()
    }

    private fun video(name: String = "a.mkv") = File(folder, name).also { it.writeText("v") }.toURI().toString()
    private fun request() = SkipTimesRequest(false, "tt1", "tt1:1:1", 1, 1)
    private fun saved(uri: String) = (SkipTimesSidecar.read(uri) as SavedSkipTimesRead.Saved).times
    private fun item(uri: String, id: String = "d1") = DownloadItem(
        id = id, contentType = "series", parentMetaId = "tt1", parentMetaType = "series", videoId = "tt1:1:1", title = "Show",
        seasonNumber = 1, episodeNumber = 1, streamTitle = "s", providerName = "p", sourceUrl = "https://example.com/a.mkv",
        localFileUri = uri, fileName = "a.mkv", status = DownloadStatus.Completed, createdAtEpochMs = 1, updatedAtEpochMs = 2,
    )

    // ---- 1. partial failures ----

    @Test
    fun an_incomplete_lookup_never_replaces_or_removes_saved_intervals() = runBlocking {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(now - 2 * day, listOf(intro, recap, credits)))
        answer = { SkipFetchResult.Found(listOf(intro.copy(endTime = 99.0)), complete = false) }

        assertNull(skipTimes().refresh(request(), uri))

        assertEquals(listOf(intro, recap, credits), saved(uri).intervals)
    }

    @Test
    fun an_incomplete_lookup_may_add_a_kind_of_segment_that_is_not_saved_yet() = runBlocking {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(now - 2 * day, listOf(intro)))
        answer = { SkipFetchResult.Found(listOf(intro.copy(endTime = 99.0), credits), complete = false) }

        val changed = skipTimes().refresh(request(), uri)

        assertEquals(listOf(intro, credits), changed)
        assertFalse(saved(uri).complete)
    }

    @Test
    fun saved_data_from_an_incomplete_lookup_is_looked_up_again_after_the_short_age_and_a_complete_one_may_replace_it() = runBlocking {
        val uri = video()
        answer = { SkipFetchResult.Found(listOf(intro), complete = false) }
        val skipTimes = skipTimes()
        skipTimes.refresh(request(), uri)
        assertEquals(1, calls.get())

        now += OfflineSkipTimes.FailureCooldownMs + 1
        assertNull(skipTimes.refresh(request(), uri))
        assertEquals(1, calls.get(), "not due before the incomplete recheck age")

        now += OfflineSkipTimes.IncompleteRecheckAgeMs
        answer = { SkipFetchResult.Found(listOf(intro.copy(endTime = 95.0), credits)) }
        assertEquals(listOf(intro.copy(endTime = 95.0), credits), skipTimes.refresh(request(), uri))
        assertTrue(saved(uri).complete)
    }

    @Test
    fun the_merge_rules_in_one_place() {
        val old = SavedSkipTimes(1, listOf(intro))
        assertNull(mergeSkipLookup(old, SkipFetchResult.Failed, 5))
        assertEquals(old.copy(fetchedAtEpochMs = 5), mergeSkipLookup(old, SkipFetchResult.NoneFound, 5))
        assertTrue(mergeSkipLookup(null, SkipFetchResult.NoneFound, 5)!!.noneFound)
        assertEquals(SavedSkipTimes(5, listOf(credits)), mergeSkipLookup(old, SkipFetchResult.Found(listOf(credits)), 5))
        assertNull(mergeSkipLookup(old, SkipFetchResult.Found(listOf(intro), complete = false), 5))
    }

    // ---- 2. torn writes ----

    @Test
    fun a_write_replaces_the_file_whole_and_leaves_no_temporary_file() {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(2, listOf(intro, credits)))
        SkipTimesSidecar.write(uri, SavedSkipTimes(3, listOf(recap)))

        assertEquals(listOf(recap), saved(uri).intervals)
        assertEquals(listOf("skip-times.json"), File(folder, "a.mkv.subtitles").list().orEmpty().toList())
    }

    @Test
    fun a_temporary_file_left_by_a_crash_is_ignored_and_cleaned_by_the_next_write() {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(2, listOf(intro)))
        val leftover = File(folder, "a.mkv.subtitles/skip-times.json.tmp").also { it.writeText("{\"version\":1,\"fetchedAt") }

        assertEquals(listOf(intro), saved(uri).intervals)
        assertTrue(leftover.exists())

        SkipTimesSidecar.write(uri, SavedSkipTimes(3, listOf(recap)))
        assertFalse(leftover.exists())
        assertEquals(listOf(recap), saved(uri).intervals)
    }

    @Test
    fun the_subtitle_manifest_is_written_the_same_way() {
        val uri = video()
        val storage = DownloadSubtitleStorage(uri)
        storage.write("manifest.json", "one")
        storage.write("manifest.json", "two")

        assertEquals("two", storage.read("manifest.json"))
        assertEquals(listOf("manifest.json"), File(folder, "a.mkv.subtitles").list().orEmpty().toList())
    }

    // ---- 3. races ----

    @Test
    fun a_lookup_that_answers_empty_after_playback_saved_intervals_does_not_overwrite_them() = runBlocking {
        val uri = video()
        val asked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answer = { asked.complete(Unit); release.await(); SkipFetchResult.NoneFound }
        val skipTimes = skipTimes()
        val backfill = async(Dispatchers.Default) { skipTimes.run(item(uri), uri) }
        asked.await()

        // Meanwhile playback saved intervals.
        SidecarLocks.withLock(uri) { SkipTimesSidecar.write(uri, SavedSkipTimes(now, listOf(intro, credits))) }
        release.complete(Unit)
        backfill.await()

        assertEquals(listOf(intro, credits), saved(uri).intervals)
    }

    @Test
    fun nothing_is_written_beside_a_download_that_was_deleted_meanwhile() = runBlocking {
        val uri = video()
        answer = { stillWanted = false; SkipFetchResult.Found(listOf(intro)) }

        skipTimes().run(item(uri), uri)

        assertEquals(SavedSkipTimesRead.Missing, SkipTimesSidecar.read(uri))
        assertFalse(File(folder, "a.mkv.subtitles").exists())
    }

    @Test
    fun two_writers_of_one_file_run_one_after_the_other() = runBlocking {
        val uri = video()
        val inside = AtomicInteger()
        val most = AtomicInteger()
        List(6) {
            async(Dispatchers.Default) {
                SidecarLocks.withLock(uri) {
                    most.updateAndGet { m -> maxOf(m, inside.incrementAndGet()) }
                    Thread.sleep(5)
                    inside.decrementAndGet()
                }
            }
        }.forEach { it.await() }
        assertEquals(1, most.get())
    }

    // ---- 4. disk work off the caller's thread, bounded ----

    @Test
    fun a_backfill_pass_looks_at_the_disk_only_on_the_io_dispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "sidecar-io-test") }
        try {
            val threads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val uri = video()
            val probe = object : DownloadCompanion {
                override val name = "probe"
                override fun isDue(item: DownloadItem, localVideoUri: String): Boolean { threads += Thread.currentThread().name.substringBefore(" @"); return true }
                override suspend fun run(item: DownloadItem, localVideoUri: String): CompanionOutcome { threads += Thread.currentThread().name.substringBefore(" @"); return CompanionOutcome.Saved }
            }
            val companions = DownloadCompanions(listOf(probe), { true }, { now }, CoroutineScope(Dispatchers.Unconfined), {}, executor.asCoroutineDispatcher())
            val resolved = java.util.Collections.synchronizedSet(mutableSetOf<String>())

            companions.backfill(listOf(item(uri))) { resolved += Thread.currentThread().name.substringBefore(" @"); it.localFileUri }

            assertEquals(setOf("sidecar-io-test"), threads)
            assertEquals(setOf("sidecar-io-test"), resolved)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun the_scan_stops_once_the_pass_has_all_it_may_do() = runBlocking {
        val resolvedCount = AtomicInteger()
        val probe = object : DownloadCompanion {
            override val name = "probe"
            override fun isDue(item: DownloadItem, localVideoUri: String) = true
            override suspend fun run(item: DownloadItem, localVideoUri: String) = CompanionOutcome.Saved
        }
        val companions = DownloadCompanions(listOf(probe), { true }, { now }, CoroutineScope(Dispatchers.Unconfined), {}, Dispatchers.Default)
        val items = (1..500).map { item("file:/x/$it.mkv", "d$it") }

        val summary = companions.backfill(items) { resolvedCount.incrementAndGet(); it.localFileUri }

        assertEquals(DownloadCompanions.BackfillMaxPerPass, summary.looked)
        assertTrue(resolvedCount.get() <= DownloadCompanions.BackfillMaxPerPass, "resolved ${resolvedCount.get()}")
    }

    @Test
    fun a_location_that_is_not_available_is_never_looked_at() = runBlocking {
        val touched = AtomicInteger()
        val probe = object : DownloadCompanion {
            override val name = "probe"
            override fun isDue(item: DownloadItem, localVideoUri: String): Boolean { touched.incrementAndGet(); return true }
            override suspend fun run(item: DownloadItem, localVideoUri: String): CompanionOutcome { touched.incrementAndGet(); return CompanionOutcome.Saved }
        }
        val companions = DownloadCompanions(listOf(probe), { true }, { now }, CoroutineScope(Dispatchers.Unconfined), {}, Dispatchers.Default)

        val summary = companions.backfill(listOf(item("file:/Volumes/Gone/a.mkv"))) { null }

        assertEquals(1, summary.unavailable)
        assertEquals(0, touched.get())
    }

    // ---- 5. failure cooldown ----

    @Test
    fun a_failed_refresh_is_not_repeated_on_every_play_until_the_cooldown_passes() = runBlocking {
        val uri = video()
        SkipTimesSidecar.write(uri, SavedSkipTimes(now - 2 * day, listOf(intro)))
        val before = SkipTimesSidecar.read(uri)
        answer = { SkipFetchResult.Failed }
        val skipTimes = skipTimes()

        repeat(5) { assertNull(skipTimes.refresh(request(), uri)) }
        assertEquals(1, calls.get())
        assertEquals(before, SkipTimesSidecar.read(uri))

        now += OfflineSkipTimes.FailureCooldownMs
        assertNull(skipTimes.refresh(request(), uri))
        assertEquals(2, calls.get())
        assertEquals(before, SkipTimesSidecar.read(uri))
    }
}

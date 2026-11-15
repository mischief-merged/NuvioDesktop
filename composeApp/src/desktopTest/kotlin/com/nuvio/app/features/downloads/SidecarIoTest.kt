package com.nuvio.app.features.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sidecar reads never hold a screen or playback on a slow or disconnected drive. */
class SidecarIoTest {
    @Test
    fun a_read_runs_off_the_callers_thread_and_returns_its_value() = runBlocking {
        val caller = Thread.currentThread().name
        val where = SidecarIo.read { Thread.currentThread().name }
        assertNotEquals(caller, where)
        assertEquals(42, SidecarIo.read { 42 })
    }

    @Test
    fun a_read_that_blocks_gives_up_at_the_time_limit_and_the_caller_goes_on() = runBlocking {
        val release = CountDownLatch(1)
        val started = System.nanoTime()

        val result = SidecarIo.read(timeoutMs = 150) { release.await(10, TimeUnit.SECONDS); "late" }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        assertNull(result)
        assertTrue(elapsedMs < 3_000, "waited $elapsedMs ms")
    }

    @Test
    fun a_read_that_throws_gives_nothing() = runBlocking {
        assertNull(SidecarIo.read<String> { error("drive gone") })
    }

    @Test
    fun the_episode_list_read_for_playback_survives_a_blocked_drive() = runBlocking {
        val release = CountDownLatch(1)
        val result = SidecarIo.read(timeoutMs = 100) { release.await(10, TimeUnit.SECONDS); savedEpisodeVideosFor("file:/Volumes/Gone/a.mkv") }
        release.countDown()
        assertNull(result)
        // An address that is not there simply has no saved list.
        assertEquals(emptyList(), savedEpisodeVideosFor("file:/Volumes/Gone/a.mkv"))
    }

    @Test
    fun skip_time_lookups_do_their_file_work_on_the_io_dispatcher_not_the_callers() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "sidecar-io-skip") }
        val folder = Files.createTempDirectory("nuvio-io").toFile()
        val realGuard = DownloadCompanionGuard.stillWanted
        DownloadCompanionGuard.stillWanted = { true }
        try {
            val uri = File(folder, "a.mkv").also { it.writeText("v") }.toURI().toString()
            val threads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val skipTimes = OfflineSkipTimes(
                fetcher = { threads += Thread.currentThread().name.substringBefore(" @"); SkipFetchResult.NoneFound },
                isOnline = { true },
                io = executor.asCoroutineDispatcher(),
            )
            runBlocking(Dispatchers.Unconfined) {
                skipTimes.refresh(SkipTimesRequest(false, "tt1", "tt1:1:1", 1, 1), uri)
            }
            assertEquals(setOf("sidecar-io-skip"), threads)
        } finally {
            DownloadCompanionGuard.stillWanted = realGuard
            executor.shutdown()
            folder.deleteRecursively()
        }
    }

    // ---- images ----

    private fun item(poster: String?, thumb: String?, uri: String?, status: DownloadStatus = DownloadStatus.Completed) = DownloadItem(
        id = "d", contentType = "series", parentMetaId = "tt1", parentMetaType = "series", videoId = "tt1:1:1", title = "Show",
        poster = poster, seasonNumber = 1, episodeNumber = 1, episodeThumbnail = thumb, streamTitle = "s", providerName = "p",
        sourceUrl = "https://example.com/a.mkv", localFileUri = uri, fileName = "a.mkv", status = status, createdAtEpochMs = 1, updatedAtEpochMs = 2,
    )

    @Test
    fun an_image_that_is_not_a_downloads_poster_or_thumbnail_maps_to_nothing_without_touching_the_disk() {
        val folder = Files.createTempDirectory("nuvio-img").toFile()
        try {
            val items = listOf(item("https://img/p.jpg", "https://img/t.jpg", File(folder, "gone/a.mkv").toURI().toString()))

            assertEquals(emptyList(), SavedArtwork.candidatesFor("https://remote/other.jpg", items))
            assertEquals(1, SavedArtwork.candidatesFor("https://img/p.jpg", items).size)
            assertEquals(ArtworkRole.Thumbnail, SavedArtwork.candidatesFor("https://img/t.jpg", items).single().second)
            assertEquals(emptyList(), SavedArtwork.candidatesFor("https://img/p.jpg", listOf(items.single().copy(status = DownloadStatus.Paused))))
            // Nothing was created or read: the folder is still empty.
            assertEquals(emptyList(), folder.list().orEmpty().toList())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun a_blocked_drive_never_holds_the_image_lookup_longer_than_its_limit() = runBlocking {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        val saved = SidecarIo.read(timeoutMs = 100) { release.await(10, TimeUnit.SECONDS); "file:/x/poster.jpg" }
        release.countDown()
        assertNull(saved)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }
}

package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nothing is written beside a video that is no longer there, even when the app still lists it, and
 * the app's own delete cannot interleave with a write.
 */
class SidecarDeleteSafetyTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val files = mutableSetOf<String>()
        val trashed = mutableListOf<String>()
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
        override fun inspectFile(localFileUri: String): DownloadedFileFacts =
            if (localFileUri in files) DownloadedFileFacts(exists = true, sizeBytes = 1_000, isVideoContainer = true) else DownloadedFileFacts(exists = false)
        override fun trashFile(localFileUri: String): Boolean { trashed += localFileUri; files.remove(localFileUri); return true }
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private val realCompanions = DownloadsRepository.companions
    private lateinit var folder: File
    private val intro = SkipInterval(10.0, 90.0, "intro", "introdb")

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("nuvio-delete-safety").toFile()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
    }

    @AfterTest
    fun tearDown() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.companions = realCompanions
        folder.deleteRecursively()
    }

    /** A finished download that the app lists, with a real file. */
    private fun download(name: String = "a.mkv"): String {
        val file = File(folder, name).also { it.writeText("v") }
        val uri = file.toURI().toString()
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:1", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 1, episodeTitle = null,
            episodeThumbnail = null, stream = StreamItem(name = "1080p", url = "https://example.com/e.mkv", addonName = "A", addonId = "a"),
        )
        backend.files += uri
        backend.progress!!(0, 1_000)
        backend.finish!!(uri, 1_000)
        return uri
    }

    @Test
    fun the_guard_says_no_when_the_video_is_gone_though_the_app_still_lists_it() {
        val uri = download()
        assertTrue(DownloadCompanionGuard.stillWanted(uri))

        backend.files.remove(uri) // removed outside the app: the record stays
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
        assertFalse(DownloadCompanionGuard.stillWanted(uri))
    }

    @Test
    fun a_refresh_after_the_video_was_removed_outside_the_app_creates_no_folder() = runBlocking {
        val uri = download()
        backend.files.remove(uri)
        File(java.net.URI(uri)).delete()
        val skipTimes = OfflineSkipTimes(fetcher = { SkipFetchResult.Found(listOf(intro)) }, isOnline = { true })

        skipTimes.run(DownloadsRepository.uiState.value.items.single(), uri)
        skipTimes.refresh(SkipTimesRequest(false, "tt1", "tt1:1:1", 1, 1), uri)

        assertFalse(File(folder, "a.mkv.subtitles").exists())
    }

    @Test
    fun the_other_companions_also_write_nothing_for_a_missing_video() = runBlocking {
        val uri = download()
        backend.files.remove(uri)
        val item = DownloadsRepository.uiState.value.items.single().copy(
            poster = "https://img/p.jpg",
            sourceSubtitles = listOf(com.nuvio.app.features.streams.StreamSubtitle(url = "https://s/en.srt", language = "en")),
        )
        val artwork = ArtworkCompanion(fetchImage = { byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1) })
        val episodes = EpisodeListCompanion(fetchMeta = { _, _ ->
            com.nuvio.app.features.details.MetaDetails(
                id = "tt1", type = "series", name = "Show",
                videos = listOf(com.nuvio.app.features.details.MetaVideo(id = "tt1:1:1", title = "E", season = 1, episode = 1)),
            )
        })
        val realFetch = DownloadSubtitles.fetchText
        DownloadSubtitles.fetchText = { _, _ -> "1\n00:00:01,000 --> 00:00:02,000\nHi\n" }
        try {
            artwork.run(item, uri)
            episodes.run(item, uri)
            SubtitlesCompanion().run(item, uri)
        } finally {
            DownloadSubtitles.fetchText = realFetch
        }

        assertFalse(File(folder, "a.mkv.subtitles").exists())
    }

    @Test
    fun a_subtitle_save_stops_writing_the_moment_the_video_disappears() = runBlocking {
        val uri = download()
        val item = DownloadsRepository.uiState.value.items.single().copy(
            sourceSubtitles = listOf("en", "fr", "de").map { com.nuvio.app.features.streams.StreamSubtitle(url = "https://s/$it.srt", language = it) },
        )
        val realFetch = DownloadSubtitles.fetchText
        DownloadSubtitles.fetchText = { _, _ -> "1\n00:00:01,000 --> 00:00:02,000\nHi\n" }
        var writes = 0
        try {
            DownloadSubtitles.prepare(item, uri, guardedWrite = { action ->
                if (writes >= 2) false else { writes++; action(); true }
            })
        } finally {
            DownloadSubtitles.fetchText = realFetch
        }

        // Two saves ran (each a file and its manifest); the third was refused and the save stopped.
        assertEquals(2, writes)
        assertEquals(2, DownloadSubtitles.savedCount(uri))
    }

    @Test
    fun the_apps_own_delete_waits_for_a_write_in_progress_and_a_write_after_it_does_nothing() {
        val uri = download()
        val inWrite = CountDownLatch(1)
        val letWriteFinish = CountDownLatch(1)
        val writer = Thread {
            SidecarLocks.withLock(uri) {
                inWrite.countDown()
                letWriteFinish.await(5, TimeUnit.SECONDS)
                SkipTimesSidecar.write(uri, SavedSkipTimes(2, listOf(intro)))
            }
        }.also { it.start() }
        assertTrue(inWrite.await(5, TimeUnit.SECONDS))

        val deleted = AtomicBoolean(false)
        val deleter = Thread {
            DownloadsRepository.deleteDownload(DownloadsRepository.uiState.value.items.single().id)
            deleted.set(true)
        }.also { it.start() }
        Thread.sleep(300)
        assertFalse(deleted.get(), "delete must wait for the write")

        letWriteFinish.countDown()
        writer.join(5_000)
        deleter.join(5_000)
        assertTrue(deleted.get())
        assertEquals(listOf(uri), backend.trashed)
        // The video is gone now: a later write does nothing.
        assertFalse(DownloadCompanionGuard.stillWanted(uri))
    }
}

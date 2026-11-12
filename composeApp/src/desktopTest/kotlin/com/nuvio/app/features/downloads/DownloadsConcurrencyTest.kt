package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadsConcurrencyTest {
    private class FakeTransfer(
        val onProgress: (Long, Long?) -> Unit,
        val onSuccess: (String, Long?) -> Unit,
        val onFailure: (String) -> Unit,
        val onPaused: () -> Unit,
    ) : DownloadsTaskHandle {
        @Volatile
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    /** Keeps the stored payload in memory and never touches a file or the network. */
    private inner class FakeBackend : DownloadsBackend {
        @Volatile
        var payload: String? = null
        val removedFiles = CopyOnWriteArrayList<String>()

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
        ): DownloadsTaskHandle = FakeTransfer(onProgress, onSuccess, onFailure, onPaused).also {
            transfers += it
            onTransferStarted(it)
        }

        override fun removeFile(localFileUri: String?): Boolean = true

        override fun removePartialFile(destinationFileName: String): Boolean {
            removedFiles += destinationFileName
            return true
        }

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }

    private val showId = "concurrency-test"
    private val transfers = CopyOnWriteArrayList<FakeTransfer>()
    private val workers = CopyOnWriteArrayList<Thread>()
    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend

    /** Called by the fake backend with each transfer it has just created, before start returns. */
    private var onTransferStarted: (FakeTransfer) -> Unit = {}

    @BeforeTest
    fun use_an_empty_in_memory_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun restore_the_real_repository() {
        workers.forEach { it.interrupt() }
        workers.forEach { it.join(10_000) }
        val stillRunning = workers.count { it.isAlive }
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        assertEquals(0, stillRunning, "worker threads were still running when the test ended")
    }

    @Test
    fun downloads_queued_from_several_threads_at_once_are_all_kept_with_distinct_ids() {
        val threads = 8
        val perThread = 50

        runOnThreads(threads) { thread ->
            repeat(perThread) { index -> enqueueEpisode(thread * perThread + index + 1) }
        }

        val items = ownItems()
        assertEquals((1..threads * perThread).toList(), items.mapNotNull { it.episodeNumber }.sorted())
        assertEquals(items.size, items.map { it.id }.toSet().size, "two downloads share an id")
        assertEquals(threads * perThread, transfers.size)
    }

    @Test
    fun resuming_a_paused_download_from_several_threads_starts_a_single_transfer() {
        val downloadId = enqueueEpisode(1)

        repeat(50) { round ->
            DownloadsRepository.pauseDownload(downloadId)
            runOnThreads(8) { DownloadsRepository.resumeDownload(downloadId) }
            assertEquals(round + 2, transfers.size, "a resume started more than one transfer")
        }
        assertEquals(DownloadStatus.Downloading, statusOf(downloadId))
    }

    @Test
    fun retrying_a_failed_download_from_several_threads_starts_a_single_transfer() {
        val downloadId = enqueueEpisode(1)

        repeat(50) { round ->
            failEveryAttempt()
            assertEquals(DownloadStatus.Failed, statusOf(downloadId))
            val before = transfers.size
            runOnThreads(8) { DownloadsRepository.retryDownload(downloadId) }
            assertEquals(before + 1, transfers.size, "a retry started more than one transfer in round $round")
        }
    }

    @Test
    fun pausing_and_resuming_from_several_threads_leaves_one_live_transfer_for_a_downloading_item() {
        val downloadId = enqueueEpisode(1)

        runOnThreads(8) { thread ->
            repeat(200) {
                if (thread % 2 == 0) DownloadsRepository.pauseDownload(downloadId) else DownloadsRepository.resumeDownload(downloadId)
            }
        }
        DownloadsRepository.resumeDownload(downloadId)

        assertEquals(DownloadStatus.Downloading, statusOf(downloadId))
        assertEquals(1, transfers.count { !it.cancelled }, "every transfer but the current one must be cancelled")
    }

    @Test
    fun a_late_pause_report_from_a_cancelled_transfer_does_not_pause_the_resumed_download() {
        val downloadId = enqueueEpisode(1)
        DownloadsRepository.pauseDownload(downloadId)
        DownloadsRepository.resumeDownload(downloadId)
        val (cancelled, current) = transfers

        cancelled.onPaused()

        assertEquals(DownloadStatus.Downloading, statusOf(downloadId))
        DownloadsRepository.pauseDownload(downloadId)
        assertTrue(current.cancelled, "the resumed transfer was no longer tracked")
    }

    @Test
    fun a_late_failure_from_a_cancelled_transfer_neither_fails_nor_restarts_the_resumed_download() {
        val downloadId = enqueueEpisode(1)
        DownloadsRepository.pauseDownload(downloadId)
        DownloadsRepository.resumeDownload(downloadId)

        repeat(5) { transfers.first().onFailure("connection lost") }

        assertEquals(2, transfers.size)
        assertEquals(DownloadStatus.Downloading, statusOf(downloadId))
    }

    @Test
    fun late_progress_and_success_from_a_cancelled_transfer_do_not_touch_the_resumed_download() {
        val downloadId = enqueueEpisode(1)
        DownloadsRepository.pauseDownload(downloadId)
        DownloadsRepository.resumeDownload(downloadId)
        val (cancelled, current) = transfers
        current.onProgress(10L, 100L)

        cancelled.onProgress(90L, 100L)
        cancelled.onSuccess("file:///cancelled-transfer.mkv", 100L)

        val item = ownItems().single()
        assertEquals(DownloadStatus.Downloading, item.status)
        assertEquals(10L, item.downloadedBytes)
        current.onSuccess("file:///current-transfer.mkv", 100L)
        assertEquals(DownloadStatus.Completed, statusOf(downloadId))
    }

    @Test
    fun reports_from_a_transfer_of_a_removed_download_are_ignored() {
        val downloadId = enqueueEpisode(1)
        DownloadsRepository.cancelDownload(downloadId)
        val removed = transfers.single()

        removed.onFailure("connection lost")
        removed.onPaused()
        removed.onSuccess("file:///removed.mkv", 1L)

        assertTrue(removed.cancelled)
        assertEquals(1, transfers.size)
        assertTrue(ownItems().isEmpty())
    }

    @Test
    fun a_pause_that_arrives_while_the_transfer_is_still_starting_cancels_it_once_it_has_started() {
        onTransferStarted = { DownloadsRepository.pauseDownload(ownItems().single().id) }

        val downloadId = enqueueEpisode(1)

        assertEquals(DownloadStatus.Paused, statusOf(downloadId))
        assertTrue(transfers.single().cancelled, "the transfer kept running after the pause")
    }

    @Test
    fun a_removal_that_arrives_while_the_transfer_is_still_starting_cancels_it_once_it_has_started() {
        onTransferStarted = { DownloadsRepository.cancelDownload(ownItems().single().id) }

        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "$showId:1:1", parentMetaId = showId, parentMetaType = "series",
            title = "Concurrency test", logo = null, poster = null, background = null, seasonNumber = 1,
            episodeNumber = 1, episodeTitle = null, episodeThumbnail = null,
            stream = StreamItem(name = "test", url = "http://127.0.0.1/episode-1.mkv", addonName = "test", addonId = "test"),
        )

        assertTrue(ownItems().isEmpty())
        assertTrue(transfers.single().cancelled, "the transfer kept running after the download was removed")
    }

    @Test
    fun every_change_is_persisted_to_the_backend_and_read_back_after_a_reload() {
        val downloadId = enqueueEpisode(1)
        DownloadsRepository.pauseDownload(downloadId)

        DownloadsRepository.clearLocalState()
        assertTrue(ownItems().isEmpty())
        DownloadsRepository.ensureLoaded()

        assertEquals(DownloadStatus.Paused, statusOf(downloadId))
    }

    @Test
    fun a_downloader_that_reports_failure_before_start_returns_keeps_the_retry_as_the_tracked_transfer() {
        onTransferStarted = { transfer -> if (transfers.size == 1) transfer.onFailure("refused") }

        val downloadId = enqueueEpisode(1)

        assertEquals(2, transfers.size)
        assertEquals(DownloadStatus.Downloading, statusOf(downloadId))
        DownloadsRepository.pauseDownload(downloadId)
        assertTrue(transfers[1].cancelled, "the retry was not the tracked transfer")
        assertFalse(transfers[0].cancelled)
    }

    @Test
    fun a_downloader_that_reports_success_before_start_returns_leaves_no_transfer_behind() {
        onTransferStarted = { transfer -> transfer.onSuccess("file:///instant.mkv", 1L) }

        val downloadId = enqueueEpisode(1)

        assertEquals(DownloadStatus.Completed, statusOf(downloadId))
        DownloadsRepository.cancelDownload(downloadId)
        assertFalse(transfers.single().cancelled, "a finished transfer was still tracked")
    }

    private fun failEveryAttempt() {
        repeat(AttemptsPerTransfer) { transfers.last().onFailure("refused") }
    }

    private fun enqueueEpisode(episodeNumber: Int): String {
        DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "$showId:1:$episodeNumber",
            parentMetaId = showId,
            parentMetaType = "series",
            title = "Concurrency test",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = episodeNumber,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "test",
                url = "http://127.0.0.1/episode-$episodeNumber.mkv",
                addonName = "test",
                addonId = "test",
            ),
        )
        return ownItems().first { it.episodeNumber == episodeNumber }.id
    }

    private fun ownItems(): List<DownloadItem> =
        DownloadsRepository.uiState.value.items.filter { it.parentMetaId == showId }

    private fun statusOf(downloadId: String): DownloadStatus? =
        ownItems().firstOrNull { it.id == downloadId }?.status

    private fun runOnThreads(count: Int, block: (thread: Int) -> Unit) {
        val barrier = CyclicBarrier(count)
        val done = CountDownLatch(count)
        val failure = AtomicReference<Throwable?>(null)
        repeat(count) { index ->
            workers += thread(isDaemon = true) {
                try {
                    barrier.await(60, TimeUnit.SECONDS)
                    block(index)
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "worker threads did not finish")
        failure.get()?.let { throw AssertionError("a worker thread failed", it) }
    }

    private companion object {
        const val AttemptsPerTransfer = 3
    }
}

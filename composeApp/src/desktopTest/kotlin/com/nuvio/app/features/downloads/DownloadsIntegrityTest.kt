package com.nuvio.app.features.downloads

import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_file_checksum
import nuvio.composeapp.generated.resources.downloads_error_file_incomplete
import nuvio.composeapp.generated.resources.downloads_error_file_missing
import nuvio.composeapp.generated.resources.downloads_error_file_not_video
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadsIntegrityTest {
    /** Keeps the stored payload in memory. Files are what the test says they are. */
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val files = mutableMapOf<String, DownloadedFileFacts>()
        val removed = mutableListOf<String?>()
        var finish: ((String, Long?) -> Unit)? = null
        var progress: ((Long, Long?) -> Unit)? = null
        var digestVerified: (() -> Unit)? = null

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
        ): DownloadsTaskHandle {
            finish = onSuccess
            progress = onProgress
            digestVerified = request.onDigestVerified
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun removeFile(localFileUri: String?): Boolean {
            removed += localFileUri
            files.remove(localFileUri)
            return true
        }

        override fun removePartialFile(destinationFileName: String): Boolean = true

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            localFileUri?.takeIf { it in files }

        override fun inspectFile(localFileUri: String): DownloadedFileFacts = files[localFileUri] ?: DownloadedFileFacts(exists = false)
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private val uri = "file:/downloads/episode.mkv"
    private val video = DownloadedFileFacts(exists = true, sizeBytes = 1_000, isVideoContainer = true)

    @BeforeTest
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    private fun text(resource: StringResource) = runBlocking { getString(resource) }

    private fun item() = DownloadsRepository.uiState.value.items.single()

    /** Queues a download and lets the transfer report that it finished with [announcedBytes]. */
    private fun download(
        file: DownloadedFileFacts?,
        announcedBytes: Long? = 1_000,
        sourceHash: String? = null,
        sourceSize: Long? = null,
        serverAnnouncesSize: Boolean = true,
        digestMatched: Boolean = false,
    ) {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:1", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 1, episodeTitle = null,
            episodeThumbnail = null,
            stream = com.nuvio.app.features.streams.StreamItem(
                name = "1080p",
                url = "https://example.com/e.mkv",
                addonName = "A",
                addonId = "a",
                behaviorHints = com.nuvio.app.features.streams.StreamBehaviorHints(videoHash = sourceHash, videoSize = sourceSize),
            ),
        )
        if (file != null) backend.files[uri] = file
        if (serverAnnouncesSize) backend.progress!!(0, announcedBytes)
        if (digestMatched) backend.digestVerified!!()
        backend.finish!!(uri, announcedBytes)
    }

    private fun storedCompleted(totalBytes: Long?) =
        """{"items":[{"id":"a","contentType":"series","parentMetaId":"tt1","parentMetaType":"series","videoId":"tt1:1:1","title":"Show",""" +
            """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/a.mkv","fileName":"episode.mkv",""" +
            """"localFileUri":"$uri","status":"Completed","downloadedBytes":${totalBytes ?: 0},"totalBytes":$totalBytes,""" +
            """"createdAtEpochMs":1,"updatedAtEpochMs":2}]}"""

    @Test
    fun a_download_whose_file_is_a_video_of_the_announced_size_is_finished() {
        download(video)

        assertEquals(DownloadStatus.Completed, item().status)
        assertEquals(uri, item().localFileUri)
        assertEquals(emptyList(), backend.removed)
    }

    @Test
    fun a_body_shorter_than_the_server_announced_is_a_failed_download_and_its_file_is_removed() {
        download(video.copy(sizeBytes = 400), announcedBytes = 1_000)

        assertEquals(DownloadStatus.Failed, item().status)
        assertEquals(text(Res.string.downloads_error_file_incomplete), item().errorMessage)
        assertNull(item().localFileUri)
        assertEquals(listOf<String?>(uri), backend.removed)
    }

    @Test
    fun a_file_that_is_not_a_video_is_a_failed_download_and_is_removed() {
        download(video.copy(isVideoContainer = false))

        assertEquals(DownloadStatus.Failed, item().status)
        assertEquals(text(Res.string.downloads_error_file_not_video), item().errorMessage)
        assertEquals(listOf<String?>(uri), backend.removed)
    }

    @Test
    fun a_download_that_reports_success_without_a_file_is_a_failed_download() {
        download(file = null)

        assertEquals(DownloadStatus.Failed, item().status)
        assertEquals(text(Res.string.downloads_error_file_missing), item().errorMessage)
    }

    @Test
    fun a_file_with_the_hash_its_source_stated_is_finished_and_recorded_as_checked_by_hash() {
        download(video.copy(videoHash = "00000000000abcde"), sourceHash = "00000000000ABCDE")

        assertEquals(DownloadStatus.Completed, item().status)
        assertEquals(DownloadVerification.Hash, item().verifiedBy)
        assertEquals("00000000000abcde", item().expectedVideoHash)
    }

    @Test
    fun a_file_with_another_hash_than_its_source_stated_is_a_failed_download_and_is_removed() {
        download(video.copy(videoHash = "00000000000abcde"), sourceHash = "00000000000abcdf")

        assertEquals(DownloadStatus.Failed, item().status)
        assertEquals(text(Res.string.downloads_error_file_checksum), item().errorMessage)
        assertEquals(listOf<String?>(uri), backend.removed)
        assertNull(item().verifiedBy)
    }

    @Test
    fun a_body_that_matched_the_digest_of_its_server_is_recorded_as_checked_by_digest() {
        download(video, digestMatched = true)

        assertEquals(DownloadVerification.Digest, item().verifiedBy)
    }

    @Test
    fun without_a_checksum_the_announced_size_is_what_the_file_was_checked_against() {
        download(video)
        assertEquals(DownloadVerification.Size, item().verifiedBy)
    }

    @Test
    fun the_size_a_source_stated_counts_when_the_server_announced_none() {
        download(video, announcedBytes = null, serverAnnouncesSize = false, sourceSize = 1_000)
        assertEquals(DownloadVerification.Size, item().verifiedBy)
        assertEquals(1_000, item().expectedSizeBytes)
    }

    @Test
    fun a_file_that_could_be_checked_against_nothing_is_recorded_as_such() {
        download(video, announcedBytes = null, serverAnnouncesSize = false)

        assertEquals(DownloadStatus.Completed, item().status)
        assertEquals(DownloadVerification.None, item().verifiedBy)
    }

    @Test
    fun what_a_download_was_checked_against_survives_saving_and_loading() {
        download(video.copy(videoHash = "00000000000abcde"), sourceHash = "00000000000abcde")

        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        assertEquals(DownloadVerification.Hash, item().verifiedBy)
    }

    @Test
    fun a_download_stored_before_checks_were_recorded_has_none_recorded() {
        backend.payload = storedCompleted(totalBytes = 1_000)
        backend.files[uri] = video

        DownloadsRepository.ensureLoaded()

        assertNull(item().verifiedBy)
        assertNull(item().expectedVideoHash)
    }

    @Test
    fun a_failed_verification_can_be_tried_again() {
        download(video.copy(isVideoContainer = false))

        DownloadsRepository.retryDownload(item().id)

        assertEquals(DownloadStatus.Downloading, item().status)
        assertNull(item().errorMessage)
    }

    @Test
    fun a_finished_download_whose_file_is_gone_at_launch_becomes_a_failed_download_and_is_not_deleted() {
        backend.payload = storedCompleted(totalBytes = 1_000)

        DownloadsRepository.ensureLoaded()

        assertEquals(DownloadStatus.Failed, item().status)
        assertEquals(text(Res.string.downloads_error_file_missing), item().errorMessage)
        assertTrue(""""status":"Failed"""" in backend.payload!!)
        assertNull(DownloadsRepository.findPlayableDownloadByVideoId("tt1:1:1"))
    }

    @Test
    fun a_finished_download_whose_file_is_smaller_than_it_was_becomes_a_failed_download_at_launch() {
        backend.payload = storedCompleted(totalBytes = 1_000)
        backend.files[uri] = video.copy(sizeBytes = 999)

        DownloadsRepository.ensureLoaded()

        assertEquals(DownloadStatus.Failed, item().status)
        assertEquals(text(Res.string.downloads_error_file_incomplete), item().errorMessage)
    }

    @Test
    fun a_finished_download_whose_file_is_there_stays_finished_at_launch_whatever_its_container() {
        backend.payload = storedCompleted(totalBytes = 1_000)
        backend.files[uri] = video.copy(isVideoContainer = false)

        DownloadsRepository.ensureLoaded()

        assertEquals(DownloadStatus.Completed, item().status)
    }

    @Test
    fun a_finished_download_of_unknown_size_stays_finished_when_its_file_is_there() {
        backend.payload = storedCompleted(totalBytes = null)
        backend.files[uri] = video.copy(sizeBytes = 5)

        DownloadsRepository.ensureLoaded()

        assertEquals(DownloadStatus.Completed, item().status)
    }

    @Test
    fun a_finished_download_can_be_checked_again_later() {
        download(video)
        assertTrue(DownloadsRepository.verifyCompletedDownload(item().id))

        backend.files.clear()

        assertFalse(DownloadsRepository.verifyCompletedDownload(item().id))
        assertEquals(DownloadStatus.Failed, item().status)
        assertFalse(DownloadsRepository.verifyCompletedDownload(item().id))
        assertFalse(DownloadsRepository.verifyCompletedDownload("unknown"))
    }
}

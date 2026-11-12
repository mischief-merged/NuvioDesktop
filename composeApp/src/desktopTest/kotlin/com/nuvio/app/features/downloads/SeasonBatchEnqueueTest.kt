package com.nuvio.app.features.downloads

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonBatchEnqueueTest {
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

    private val showId = "enqueue-test"
    private val realBackend = DownloadsRepository.backend
    private val skipped = mutableListOf<Pair<String?, Int>>()

    @BeforeTest
    fun use_an_empty_in_memory_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = FakeBackend()
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    private fun enqueue(episode: Int, url: String, ruleKey: String?) = SeasonBatchDownloads.enqueueEpisode(
        request = SeasonBatchRequest(
            contentType = "series",
            parentMetaId = showId,
            parentMetaType = "series",
            title = "Show",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodes = listOf(testEpisode(episode, showId = showId)),
            quality = BatchQuality.BestAvailable,
            autoDownloadRuleKey = ruleKey,
        ),
        episode = testEpisode(episode, showId = showId),
        stream = testStream(url),
        onPrivateSourceSkipped = { request, skippedEpisode -> skipped += request.autoDownloadRuleKey to skippedEpisode.episodeNumber },
    )

    @Test
    fun an_automatic_source_at_a_private_address_is_refused_and_reported_as_skipped() {
        val ruleKey = autoDownloadRuleKey("series", showId)

        assertEquals(DownloadEnqueueResult.UnsupportedFormat, enqueue(1, "http://192.168.1.20/1.mp4", ruleKey))
        assertEquals(DownloadEnqueueResult.UnsupportedFormat, enqueue(2, "http://127.0.0.1:11470/2.mp4", ruleKey))

        assertEquals(listOf<Pair<String?, Int>>(ruleKey to 1, ruleKey to 2), skipped)
        assertEquals(emptyList(), DownloadsRepository.uiState.value.items)
    }

    @Test
    fun an_automatic_source_at_a_public_address_is_queued_and_not_reported() {
        val ruleKey = autoDownloadRuleKey("series", showId)

        assertEquals(DownloadEnqueueResult.Started, enqueue(1, "https://example.com/1.mp4", ruleKey))

        assertEquals(emptyList(), skipped)
        assertEquals(ruleKey, DownloadsRepository.uiState.value.items.single().autoDownloadRuleKey)
    }

    @Test
    fun a_season_download_by_the_user_from_a_private_address_is_queued_and_not_reported() {
        assertEquals(DownloadEnqueueResult.Started, enqueue(1, "http://192.168.1.20/1.mp4", ruleKey = null))

        assertEquals(emptyList(), skipped)
    }
}

package com.nuvio.app.features.downloads

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoDownloadQueueTest {
    /** Keeps the stored payload in memory and never touches a file or the network. */
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        var started = 0

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
            started++
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun removeFile(localFileUri: String?): Boolean = true

        override fun removePartialFile(destinationFileName: String): Boolean = true

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            "file:/downloads/$destinationFileName"
    }

    private val showId = "auto-queue-test"
    private val ruleKey = autoDownloadRuleKey("series", showId)
    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend

    @BeforeTest
    fun use_an_empty_in_memory_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    private fun enqueue(episode: Int, url: String, rule: String?) = DownloadsRepository.enqueueFromStream(
        contentType = "series",
        videoId = "$showId:1:$episode",
        parentMetaId = showId,
        parentMetaType = "series",
        title = "Show",
        logo = null,
        poster = null,
        background = null,
        seasonNumber = 1,
        episodeNumber = episode,
        episodeTitle = null,
        episodeThumbnail = null,
        stream = testStream(url),
        autoDownloadRuleKey = rule,
    )

    private fun items() = DownloadsRepository.uiState.value.items

    @Test
    fun an_automatic_download_from_this_computer_or_a_private_network_is_refused() {
        listOf("http://127.0.0.1:11470/a.mp4", "http://localhost/a.mp4", "http://192.168.1.20/a.mp4", "http://[::1]/a.mp4").forEach { url ->
            assertEquals(DownloadEnqueueResult.UnsupportedFormat, enqueue(1, url, ruleKey), url)
        }

        assertEquals(emptyList(), items())
        assertEquals(0, backend.started)
    }

    @Test
    fun a_download_the_user_starts_may_use_a_private_network() {
        assertEquals(DownloadEnqueueResult.Started, enqueue(1, "http://192.168.1.20/a.mp4", rule = null))

        assertEquals(1, backend.started)
    }

    @Test
    fun an_automatic_download_names_its_rule_and_a_download_by_hand_does_not() {
        enqueue(1, "https://example.com/1.mp4", ruleKey)
        enqueue(2, "https://example.com/2.mp4", rule = null)

        assertEquals(mapOf<Int?, String?>(1 to ruleKey, 2 to null), items().associate { it.episodeNumber to it.autoDownloadRuleKey })
    }

    @Test
    fun downloading_an_automatic_episode_again_by_hand_makes_it_the_users_download() {
        enqueue(1, "https://example.com/1.mp4", ruleKey)

        assertEquals(DownloadEnqueueResult.Replaced, enqueue(1, "https://example.com/other.mp4", rule = null))

        assertNull(items().single().autoDownloadRuleKey)
    }

    @Test
    fun a_rule_key_survives_saving_and_loading() {
        enqueue(1, "https://example.com/1.mp4", ruleKey)

        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        assertEquals(ruleKey, items().single().autoDownloadRuleKey)
    }

    @Test
    fun a_played_file_is_matched_to_its_finished_download_in_either_spelling_of_the_address() {
        enqueue(1, "https://example.com/1.mp4", ruleKey)
        enqueue(2, "https://example.com/2.mp4", ruleKey)
        val first = items().first { it.episodeNumber == 1 }
        backend.payload = backend.payload!!.replace("\"Downloading\"", "\"Completed\"")
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        val byResolvedFile = DownloadsRepository.findCompletedDownloadByLocalFile("file:///downloads/${first.fileName}")

        assertEquals(first.id, byResolvedFile?.id)
        assertEquals(first.id, DownloadsRepository.findCompletedDownloadByLocalFile("file:/downloads/${first.fileName}")?.id)
        assertNull(DownloadsRepository.findCompletedDownloadByLocalFile("file:/downloads/unknown.mp4"))
        assertNull(DownloadsRepository.findCompletedDownloadByLocalFile("https://example.com/1.mp4"))
        assertNull(DownloadsRepository.findCompletedDownloadByLocalFile(""))
    }

    @Test
    fun a_file_that_is_still_downloading_is_not_matched() {
        enqueue(1, "https://example.com/1.mp4", ruleKey)

        assertNull(DownloadsRepository.findCompletedDownloadByLocalFile("file:/downloads/${items().single().fileName}"))
    }
}

package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stored list that cannot be read is reported to the screen, not shown as an empty one. In-memory store only. */
class DownloadsStoreProblemTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        var previousPayload: String? = null
        var puttingAsideFails = false
        val unreadable = mutableListOf<String>()

        override fun loadPayload(): String? = payload
        override fun loadPreviousPayload(): String? = previousPayload
        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun saveUnreadablePayload(payload: String, atEpochMs: Long) {
            if (puttingAsideFails) error("the store is read-only")
            unreadable += payload
            previousPayload = this.payload
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

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend

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

    private val good = """{"items":[{"id":"a","contentType":"movie","parentMetaId":"tt1","parentMetaType":"movie","videoId":"tt1","title":"Film",""" +
        """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/a.mkv","fileName":"a.mkv",""" +
        """"status":"Paused","createdAtEpochMs":1,"updatedAtEpochMs":2}]}"""

    private val problem get() = DownloadsRepository.uiState.value.storeProblem

    @Test
    fun a_fresh_install_and_a_readable_list_have_no_problem() {
        DownloadsRepository.ensureLoaded()
        assertNull(problem)

        DownloadsRepository.clearLocalState()
        backend.payload = good
        DownloadsRepository.ensureLoaded()
        assertNull(problem)
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
    }

    @Test
    fun a_list_that_cannot_be_read_and_has_no_earlier_copy_starts_empty_and_says_so() {
        backend.payload = "this is not a list"

        DownloadsRepository.ensureLoaded()

        assertEquals(DownloadsStoreProblem.StartedEmpty, problem)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertEquals(listOf("this is not a list"), backend.unreadable, "a copy was not kept")
    }

    @Test
    fun a_list_that_cannot_be_read_shows_the_earlier_copy_and_says_so() {
        backend.payload = "{\"items\": 12}"
        backend.previousPayload = good

        DownloadsRepository.ensureLoaded()

        assertEquals(DownloadsStoreProblem.EarlierCopyShown, problem)
        assertEquals(listOf("a"), DownloadsRepository.uiState.value.items.map { it.id })
    }

    @Test
    fun a_list_that_cannot_be_read_or_kept_says_that_nothing_will_be_saved() {
        backend.payload = "garbage"
        backend.puttingAsideFails = true

        DownloadsRepository.ensureLoaded()

        assertEquals(DownloadsStoreProblem.NotSaving, problem)
    }

    @Test
    fun the_problem_stays_while_downloads_come_and_go() {
        backend.payload = "garbage"
        DownloadsRepository.ensureLoaded()

        DownloadsRepository.enqueueFromStream(
            contentType = "movie", videoId = "tt9", parentMetaId = "tt9", parentMetaType = "movie", title = "New",
            logo = null, poster = null, background = null, seasonNumber = null, episodeNumber = null, episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(name = "1080p", url = "https://example.com/n.mkv", addonName = "A", addonId = "a", behaviorHints = StreamBehaviorHints()),
        )

        assertEquals(1, DownloadsRepository.uiState.value.items.size)
        assertEquals(DownloadsStoreProblem.StartedEmpty, problem)
    }

    @Test
    fun loading_a_readable_list_again_clears_the_problem() {
        backend.payload = "garbage"
        DownloadsRepository.ensureLoaded()
        assertEquals(DownloadsStoreProblem.StartedEmpty, problem)

        backend.payload = good
        DownloadsRepository.onProfileChanged()

        assertNull(problem)
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
    }

    @Test
    fun clearing_local_state_clears_the_problem() {
        backend.payload = "garbage"
        DownloadsRepository.ensureLoaded()

        DownloadsRepository.clearLocalState()

        assertNull(problem)
    }
}

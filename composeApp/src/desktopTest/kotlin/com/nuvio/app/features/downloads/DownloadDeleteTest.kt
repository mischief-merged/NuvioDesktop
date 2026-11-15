package com.nuvio.app.features.downloads

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deleting a download the user chose to delete. The backend is the seam: nothing here touches a real
 * file, the real Trash or the network.
 */
class DownloadDeleteTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val trashed = mutableListOf<String>()
        val removedOutright = mutableListOf<String?>()
        val partialsRemoved = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val files = mutableSetOf<String>()
        var trashSucceeds = true

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
            override fun cancel() {
                cancelled += request.item.id
            }
        }

        override fun removeFile(localFileUri: String?): Boolean {
            removedOutright += localFileUri
            return true
        }

        override fun removePartialFile(destinationFileName: String): Boolean {
            partialsRemoved += destinationFileName
            return true
        }

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            localFileUri?.takeIf { it in files }

        override fun trashFile(localFileUri: String): Boolean {
            if (trashSucceeds) trashed += localFileUri
            return trashSucceeds
        }
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

    private fun stored(id: String, status: String, file: String? = null, extra: String = ""): String {
        if (file != null) backend.files += file
        return """{"id":"$id","contentType":"movie","parentMetaId":"tt$id","parentMetaType":"movie","videoId":"tt$id","title":"Film $id",""" +
            """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/$id.mkv","fileName":"$id.mkv","status":"$status",""" +
            """${file?.let { """"localFileUri":"$it",""" }.orEmpty()}"createdAtEpochMs":1,"updatedAtEpochMs":2$extra}"""
    }

    private fun load(vararg items: String) {
        backend.payload = """{"items":[${items.joinToString(",")}]}"""
        DownloadsRepository.ensureLoaded()
    }

    private fun ids() = DownloadsRepository.uiState.value.items.map { it.id }

    @Test
    fun the_file_of_a_finished_download_goes_to_the_trash_and_the_entry_is_dropped() {
        load(stored("a", "Completed", file = "file:/a.mkv"))

        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload("a"))

        assertEquals(listOf("file:/a.mkv"), backend.trashed)
        assertTrue(backend.removedOutright.isEmpty(), "the file was deleted outright")
        assertTrue(ids().isEmpty())
        assertFalse(backend.payload!!.contains("\"a\""), "the entry is still stored")
    }

    @Test
    fun a_file_that_cannot_be_moved_is_not_deleted_and_its_entry_stays() {
        load(stored("a", "Completed", file = "file:/a.mkv"))
        backend.trashSucceeds = false

        assertEquals(DownloadDeleteResult.NotMoved, DownloadsRepository.deleteDownload("a"))

        assertEquals(listOf("a"), ids())
        assertTrue(backend.removedOutright.isEmpty())
        assertTrue(backend.partialsRemoved.isEmpty())
    }

    @Test
    fun a_file_that_another_download_also_uses_stays_where_it_is() {
        load(
            stored("a", "Completed", file = "file:/shared.mkv"),
            stored("b", "Completed", file = "file:/shared.mkv"),
        )

        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload("a"))

        assertTrue(backend.trashed.isEmpty())
        assertEquals(listOf("b"), ids())
    }

    @Test
    fun a_finished_download_whose_file_is_already_gone_is_dropped_without_the_trash() {
        load(stored("a", "Completed", extra = ""","localFileUri":"file:/gone.mkv""""))

        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload("a"))

        assertTrue(backend.trashed.isEmpty())
        assertTrue(ids().isEmpty())
    }

    @Test
    fun a_running_download_is_stopped_and_what_it_wrote_is_removed_not_trashed() {
        load(stored("a", "Downloading"))

        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload("a"))

        assertEquals(listOf("a"), backend.cancelled)
        assertEquals(listOf("a.mkv"), backend.partialsRemoved)
        assertTrue(backend.trashed.isEmpty())
        assertTrue(ids().isEmpty())
    }

    @Test
    fun a_paused_or_failed_download_is_dropped_with_its_partial_file() {
        load(stored("p", "Paused"), stored("f", "Failed"))

        DownloadsRepository.deleteDownload("p")
        DownloadsRepository.deleteDownload("f")

        assertEquals(listOf("p.mkv", "f.mkv"), backend.partialsRemoved)
        assertTrue(backend.trashed.isEmpty())
        assertTrue(ids().isEmpty())
    }

    @Test
    fun a_download_that_is_not_there_is_reported_as_gone() {
        load(stored("a", "Paused"))

        assertEquals(DownloadDeleteResult.Gone, DownloadsRepository.deleteDownload("nope"))
        assertEquals(listOf("a"), ids())
    }

    @Test
    fun other_downloads_are_left_alone() {
        load(stored("a", "Completed", file = "file:/a.mkv"), stored("b", "Completed", file = "file:/b.mkv"))

        DownloadsRepository.deleteDownload("a")

        assertEquals(listOf("b"), ids())
        assertEquals(listOf("file:/a.mkv"), backend.trashed)
    }
}

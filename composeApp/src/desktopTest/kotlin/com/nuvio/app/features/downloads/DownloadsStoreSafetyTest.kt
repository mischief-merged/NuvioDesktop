package com.nuvio.app.features.downloads

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadsStoreSafetyTest {
    /** Keeps the stored payload in memory and never touches a file or the network. */
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val unreadable = mutableListOf<Pair<Long, String>>()
        val started = mutableListOf<String>()

        override fun loadPayload(): String? = payload

        var previousPayload: String? = null

        override fun loadPreviousPayload(): String? = previousPayload

        override fun savePayload(payload: String) {
            this.payload = payload
        }

        var puttingAsideFails = false

        override fun saveUnreadablePayload(payload: String, atEpochMs: Long) {
            if (puttingAsideFails) error("the store is read-only")
            unreadable += atEpochMs to payload
            // Like the real store: every write makes what was stored until then the previous copy.
            previousPayload = this.payload
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            started += request.item.id
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
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

    /** One stored download with only the fields that have no default, plus [extra]. */
    private fun stored(id: String, status: String = "Completed", extra: String = "") =
        """{"id":"$id","contentType":"series","parentMetaId":"tt1","parentMetaType":"series","videoId":"tt1:1:1","title":"Show",""" +
            """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/$id.mkv","fileName":"$id.mkv",""" +
            """"status":"$status","createdAtEpochMs":1,"updatedAtEpochMs":2$extra}"""

    private fun payloadOf(vararg items: String) = """{"items":[${items.joinToString(",")}]}"""

    private fun load(): List<DownloadItem> {
        DownloadsRepository.ensureLoaded()
        return DownloadsRepository.uiState.value.items
    }

    /** Makes the repository write its list, as any change to a download does. */
    private fun rewrite(id: String) {
        DownloadsRepository.pauseDownload(id)
        DownloadsRepository.resumeDownload(id)
    }

    @Test
    fun a_payload_that_cannot_be_read_is_put_aside_and_is_not_overwritten_by_an_empty_list() {
        backend.payload = """{"items":[{"id":"a","status":"""

        assertEquals(emptyList(), load())

        assertEquals("""{"items":[{"id":"a","status":""", backend.payload)
        assertEquals(listOf("""{"items":[{"id":"a","status":"""), backend.unreadable.map { it.second })
    }

    @Test
    fun a_payload_cut_short_by_an_interrupted_write_is_put_aside_and_the_list_is_read_from_the_copy_before_it() {
        backend.previousPayload = payloadOf(stored("a"), stored("b"))
        backend.payload = """{"items":[{"id":"a","status":"""

        assertEquals(listOf("a", "b"), load().map { it.id })

        assertEquals(listOf("""{"items":[{"id":"a","status":"""), backend.unreadable.map { it.second })
    }

    @Test
    fun a_copy_from_before_that_cannot_be_read_either_leaves_the_list_empty_and_the_payload_untouched() {
        backend.previousPayload = """{"items":[{"id":"""
        backend.payload = """{"items":[{"id":"a","status":"""

        assertEquals(emptyList(), load())

        assertEquals("""{"items":[{"id":"a","status":""", backend.payload)
    }

    @Test
    fun the_copy_from_before_is_not_read_in_place_of_a_payload_that_can_be_read() {
        backend.previousPayload = payloadOf(stored("a"), stored("b"))
        backend.payload = payloadOf(stored("a"))

        assertEquals(listOf("a"), load().map { it.id })
    }

    @Test
    fun a_payload_that_is_not_a_list_of_downloads_is_put_aside_too() {
        listOf("[1,2,3]", """{"items":5}""", "not json at all").forEach { payload ->
            DownloadsRepository.clearLocalState()
            backend.payload = payload

            assertEquals(emptyList(), load(), payload)
            assertEquals(payload, backend.unreadable.last().second)
            assertEquals(payload, backend.payload)
        }
    }

    @Test
    fun a_payload_whose_list_is_under_another_name_is_put_aside_and_not_taken_for_an_empty_list() {
        val payload = """{"downloads":[${stored("a")}]}"""
        backend.payload = payload

        assertEquals(emptyList(), load())

        assertEquals(listOf(payload), backend.unreadable.map { it.second })
        assertEquals(payload, backend.payload)
    }

    @Test
    fun nothing_is_saved_over_a_payload_that_could_neither_be_read_nor_be_put_aside() {
        backend.payload = "not json at all"
        backend.puttingAsideFails = true
        load()

        DownloadsRepository.enqueueFromStream(
            contentType = "movie", videoId = "tt9", parentMetaId = "tt9", parentMetaType = "movie", title = "Movie",
            logo = null, poster = null, background = null, seasonNumber = null, episodeNumber = null, episodeTitle = null,
            episodeThumbnail = null,
            stream = com.nuvio.app.features.streams.StreamItem(name = "1080p", url = "https://example.com/m.mkv", addonName = "A", addonId = "a"),
        )

        assertEquals("not json at all", backend.payload)
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
    }

    @Test
    fun what_else_the_payload_holds_beside_the_list_is_written_back() {
        backend.payload = """{"version":7,"items":[${stored("a", status = "Paused")}],"note":{"by":"a later build"}}"""
        load()

        rewrite("a")

        assertTrue(""""version":7""" in backend.payload!!, backend.payload)
        assertTrue(""""note":{"by":"a later build"}""" in backend.payload!!, backend.payload)
    }

    @Test
    fun a_value_this_build_read_as_a_default_is_kept_as_it_was_stored_until_the_field_is_changed() {
        backend.payload = payloadOf(stored("a", status = "Paused", extra = ""","verifiedBy":"Signature","errorMessage":"kept""""), stored("b", status = "Paused"))
        val items = load()
        assertEquals(listOf("a", "b"), items.map { it.id })

        rewrite("b")
        assertTrue(""""verifiedBy":"Signature"""" in backend.payload!!, backend.payload)
        assertTrue(""""errorMessage":"kept"""" in backend.payload!!, backend.payload)

        rewrite("a")
        assertFalse(""""errorMessage":"kept"""" in backend.payload!!, "a field the app changed was not written")
        assertTrue(""""verifiedBy":"Signature"""" in backend.payload!!, backend.payload)
    }

    @Test
    fun an_empty_store_is_not_put_aside() {
        listOf(null, "", "   ", "{}", """{"items":[]}""").forEach { payload ->
            DownloadsRepository.clearLocalState()
            backend.payload = payload

            assertEquals(emptyList(), load(), payload.toString())
        }
        assertEquals(emptyList(), backend.unreadable)
    }

    @Test
    fun a_download_with_a_value_this_build_does_not_know_does_not_take_the_others_with_it() {
        val unknown = stored("b", status = "Verifying")
        backend.payload = payloadOf(stored("a"), unknown, stored("c", status = "Paused"))

        assertEquals(listOf("a", "c"), load().map { it.id })
        assertEquals(emptyList(), backend.unreadable)

        rewrite("c")

        assertTrue(unknown in backend.payload!!, "the download that could not be read was dropped from ${backend.payload}")
        DownloadsRepository.clearLocalState()
        assertEquals(listOf("a", "c"), load().map { it.id })
    }

    @Test
    fun a_download_that_is_not_an_object_or_lacks_a_required_field_is_kept_as_it_was() {
        backend.payload = """{"items":[${stored("a", status = "Paused")},7,{"id":"x"}]}"""

        assertEquals(listOf("a"), load().map { it.id })
        rewrite("a")

        assertTrue(""",7,{"id":"x"}]""" in backend.payload!!, backend.payload)
    }

    @Test
    fun fields_this_build_does_not_know_are_ignored_and_written_back_with_their_download() {
        backend.payload = payloadOf(
            stored("a", status = "Paused", extra = ""","origin":"AUTO","ruleKey":"series|tt1","nested":{"a":[1,2]}"""),
            stored("b"),
        )

        val items = load()
        assertEquals(listOf("a", "b"), items.map { it.id })
        assertEquals("Show", items.first().title)

        rewrite("a")

        val written = backend.payload!!
        assertTrue(""""origin":"AUTO"""" in written, written)
        assertTrue(""""ruleKey":"series|tt1"""" in written, written)
        assertTrue(""""nested":{"a":[1,2]}""" in written, written)
        DownloadsRepository.clearLocalState()
        assertEquals(DownloadStatus.Downloading, load().first { it.id == "a" }.status)
    }

    @Test
    fun a_field_this_build_knows_is_written_with_its_current_value_not_the_stored_one() {
        backend.payload = payloadOf(stored("a", status = "Paused"))
        load()

        DownloadsRepository.resumeDownload("a")

        assertTrue(""""status":"Downloading"""" in backend.payload!!)
        assertFalse(""""status":"Paused"""" in backend.payload!!)
    }

    @Test
    fun a_download_that_is_removed_takes_its_stored_fields_with_it() {
        backend.payload = payloadOf(stored("a", extra = ""","origin":"AUTO""""), stored("b"))
        load()

        DownloadsRepository.cancelDownload("a")

        assertFalse("origin" in backend.payload!!)
        assertEquals(listOf("b"), DownloadsRepository.uiState.value.items.map { it.id })
    }

    @Test
    fun a_second_download_with_an_id_that_is_already_taken_is_kept_aside_in_the_payload() {
        val twin = stored("a", status = "Paused")
        backend.payload = payloadOf(stored("a"), twin, stored("b", status = "Paused"))

        assertEquals(listOf("a", "b"), load().map { it.id })
        rewrite("b")

        assertTrue(twin in backend.payload!!)
    }

    @Test
    fun a_record_whose_id_was_taken_does_not_come_back_when_the_download_with_that_id_is_removed() {
        backend.payload = payloadOf(stored("a"), stored("a", status = "Downloading"), stored("b"))
        load()

        DownloadsRepository.cancelDownload("a")
        DownloadsRepository.clearLocalState()

        assertEquals(listOf("b"), load().map { it.id })
        assertEquals(emptyList(), backend.started)
    }

    @Test
    fun a_download_that_was_running_when_the_app_was_closed_is_started_again_and_one_the_user_paused_is_not() {
        backend.payload = payloadOf(
            stored("running", status = "Downloading"),
            stored("paused", status = "Paused"),
            stored("done"),
            stored("failed", status = "Failed"),
        )

        val items = load()

        assertEquals(listOf("running"), backend.started)
        assertEquals(
            mapOf("running" to DownloadStatus.Downloading, "paused" to DownloadStatus.Paused, "done" to DownloadStatus.Completed, "failed" to DownloadStatus.Failed),
            items.associate { it.id to it.status },
        )
    }

    @Test
    fun loading_again_does_not_start_a_running_download_a_second_time() {
        backend.payload = payloadOf(stored("running", status = "Downloading"))
        load()

        DownloadsRepository.onProfileChanged()

        assertEquals(listOf("running"), backend.started)
    }
}

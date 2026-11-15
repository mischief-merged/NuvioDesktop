package com.nuvio.app.features.downloads

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadsFormatMigrationTest {
    /** Keeps the stored payload in memory and never touches a file or the network. */
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val unreadable = mutableListOf<String>()

        override fun loadPayload(): String? = payload

        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun saveUnreadablePayload(payload: String, atEpochMs: Long) {
            unreadable += payload
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

    /**
     * One record with every field the app wrote before automatic downloads existed, in the order
     * and with the defaults it wrote them. [later] are the fields a later build added at the end.
     */
    private fun record(id: String, episode: Int, status: String, later: String = "") =
        """{"id":"$id","contentType":"series","parentMetaId":"tt0121955","parentMetaType":"series","videoId":"tt0121955:29:$episode",""" +
            """"title":"South Park","logo":"https://img/logo.png","poster":"https://img/poster.jpg","background":null,""" +
            """"seasonNumber":29,"episodeNumber":$episode,"episodeTitle":"Episode $episode","episodeThumbnail":null,""" +
            """"streamTitle":"1080p WEB","streamSubtitle":"1.2 GB","providerName":"Addon","providerAddonId":"addon:1",""" +
            """"sourceUrl":"https://cdn.example.com/$id.mkv","sourceHeaders":{"Authorization":"Bearer x"},"sourceResponseHeaders":{},""" +
            """"subtitleRequests":[],"sourceSubtitles":[],"localFileUri":"file:/downloads/$id.mkv","fileName":"$id.mkv",""" +
            """"status":"$status","downloadedBytes":1200000000,"totalBytes":1200000000,"errorMessage":null,""" +
            """"createdAtEpochMs":1762900000000,"updatedAtEpochMs":1762900500000$later}"""

    private fun payloadOf(vararg records: String) = """{"items":[${records.joinToString(",")}]}"""

    private fun load(): List<DownloadItem> {
        DownloadsRepository.ensureLoaded()
        return DownloadsRepository.uiState.value.items
    }

    private fun rewrite(id: String) {
        DownloadsRepository.pauseDownload(id)
        DownloadsRepository.resumeDownload(id)
    }

    private fun assertOriginalFieldsKept(item: DownloadItem, episode: Int) {
        assertEquals("tt0121955", item.parentMetaId)
        assertEquals("tt0121955:29:$episode", item.videoId)
        assertEquals("South Park", item.title)
        assertEquals("https://img/logo.png", item.logo)
        assertEquals(29, item.seasonNumber)
        assertEquals(episode, item.episodeNumber)
        assertEquals("Episode $episode", item.episodeTitle)
        assertEquals("1.2 GB", item.streamSubtitle)
        assertEquals("addon:1", item.providerAddonId)
        assertEquals(mapOf("Authorization" to "Bearer x"), item.sourceHeaders)
        assertEquals("file:/downloads/${item.id}.mkv", item.localFileUri)
        assertEquals(1_200_000_000L, item.downloadedBytes)
        assertEquals(1_200_000_000L, item.totalBytes)
        assertEquals(1_762_900_000_000L, item.createdAtEpochMs)
    }

    @Test
    fun a_list_written_before_automatic_downloads_existed_is_read_in_full_as_the_users_own_downloads() {
        backend.payload = payloadOf(record("a", 1, "Completed"), record("b", 2, "Paused"), record("c", 3, "Failed"))

        val items = load()

        assertEquals(listOf("a", "b", "c"), items.map { it.id })
        assertEquals(listOf(DownloadStatus.Completed, DownloadStatus.Paused, DownloadStatus.Failed), items.map { it.status })
        items.forEachIndexed { index, item -> assertOriginalFieldsKept(item, index + 1) }
        assertTrue(items.all { it.autoDownloadRuleKey == null })
        assertEquals(emptyList(), backend.unreadable)
    }

    @Test
    fun a_list_written_by_the_first_build_of_automatic_downloads_keeps_its_rules() {
        backend.payload = payloadOf(
            record("a", 1, "Completed", later = ""","origin":"AUTO","ruleKey":"series|tt0121955","pausedAutomatically":false"""),
            record("b", 2, "Paused", later = ""","origin":"AUTO","ruleKey":"series|tt0121955","pausedAutomatically":true"""),
            record("c", 3, "Completed", later = ""","origin":"MANUAL","ruleKey":null,"pausedAutomatically":false"""),
        )

        val items = load()

        assertEquals(listOf("a", "b", "c"), items.map { it.id })
        assertEquals(listOf<String?>("series|tt0121955", "series|tt0121955", null), items.map { it.autoDownloadRuleKey })
        items.forEachIndexed { index, item -> assertOriginalFieldsKept(item, index + 1) }
        assertEquals(emptyList(), backend.unreadable)
    }

    @Test
    fun rewriting_such_a_list_adds_the_current_field_and_keeps_the_earlier_ones_for_the_earlier_build() {
        backend.payload = payloadOf(
            record("a", 1, "Completed", later = ""","origin":"AUTO","ruleKey":"series|tt0121955","pausedAutomatically":false"""),
            record("b", 2, "Paused", later = ""","origin":"AUTO","ruleKey":"series|tt0121955","pausedAutomatically":true"""),
        )
        load()

        rewrite("b")

        val written = backend.payload!!
        fun count(text: String) = written.split(text).size - 1
        assertEquals(2, count(""""origin":"AUTO""""))
        assertEquals(2, count(""""ruleKey":"series|tt0121955""""))
        assertEquals(2, count(""""autoDownloadRuleKey":"series|tt0121955""""))
        assertTrue(""""pausedAutomatically":true""" in written)
        DownloadsRepository.clearLocalState()
        assertEquals(listOf<String?>("series|tt0121955", "series|tt0121955"), load().map { it.autoDownloadRuleKey })
    }

    @Test
    fun a_record_that_already_names_its_rule_in_the_current_field_keeps_that_rule() {
        backend.payload = payloadOf(
            record("a", 1, "Completed", later = ""","autoDownloadRuleKey":"series|current","origin":"AUTO","ruleKey":"series|earlier""""),
            record("b", 2, "Completed", later = ""","autoDownloadRuleKey":null,"origin":"AUTO","ruleKey":"series|earlier""""),
        )

        assertEquals(listOf<String?>("series|current", null), load().map { it.autoDownloadRuleKey })
    }

    @Test
    fun an_earlier_record_without_a_rule_or_from_the_user_is_the_users_own() {
        backend.payload = payloadOf(
            record("a", 1, "Completed", later = ""","origin":"AUTO","ruleKey":null"""),
            record("b", 2, "Completed", later = ""","origin":"AUTO","ruleKey":"  """"),
            record("c", 3, "Completed", later = ""","origin":"MANUAL","ruleKey":"series|tt0121955""""),
            record("d", 4, "Completed", later = ""","ruleKey":"series|tt0121955""""),
        )

        assertEquals(listOf<String?>(null, null, null, null), load().map { it.autoDownloadRuleKey })
    }

    @Test
    fun a_list_written_by_a_build_that_checks_finished_files_keeps_what_it_recorded() {
        backend.payload = payloadOf(
            record("a", 1, "Completed", later = ""","expectedVideoHash":"8e245d9679d31e12","expectedSizeBytes":1200000000,"verifiedBy":"Hash","preferredAudioLanguage":"en""""),
            record("b", 2, "Completed", later = ""","expectedVideoHash":null,"expectedSizeBytes":null,"verifiedBy":null,"preferredAudioLanguage":null"""),
        )

        val (checked, plain) = load()

        assertOriginalFieldsKept(checked, 1)
        assertEquals(DownloadVerification.Hash, checked.verifiedBy)
        assertEquals("8e245d9679d31e12", checked.expectedVideoHash)
        assertEquals("en", checked.preferredAudioLanguage)
        assertNull(checked.autoDownloadRuleKey)
        assertOriginalFieldsKept(plain, 2)
        assertNull(plain.verifiedBy)
        assertEquals(emptyList(), backend.unreadable)
    }

    @Test
    fun a_record_written_before_frames_were_checked_is_read_in_full_as_not_checked_and_keeps_its_fields_when_rewritten() {
        backend.payload = payloadOf(
            record("a", 1, "Completed", later = ""","verifiedBy":"Size""""),
            record("b", 2, "Paused"),
        )

        val (finished, paused) = load()

        assertOriginalFieldsKept(finished, 1)
        assertEquals(DownloadVerification.Size, finished.verifiedBy)
        assertEquals(DownloadFramesCheck.NotChecked, finished.framesCheck)
        assertOriginalFieldsKept(paused, 2)
        assertEquals(DownloadFramesCheck.NotChecked, paused.framesCheck)
        assertEquals(emptyList(), backend.unreadable)

        rewrite("b")
        val written = backend.payload!!
        DownloadsRepository.clearLocalState()
        backend.payload = written
        val (afterFinished, afterPaused) = load()
        assertOriginalFieldsKept(afterFinished, 1)
        assertEquals("tt0121955:29:2", afterPaused.videoId)
        assertEquals(DownloadFramesCheck.NotChecked, afterFinished.framesCheck)
    }

    @Test
    fun a_record_that_states_what_the_frames_came_to_is_read_with_it_and_an_unknown_or_null_value_reads_as_not_checked() {
        backend.payload = payloadOf(
            record("a", 1, "Completed", later = ""","framesCheck":"Passed""""),
            record("b", 2, "Completed", later = ""","framesCheck":"Suspect""""),
            record("c", 3, "Completed", later = ""","framesCheck":"SomethingFromALaterBuild""""),
            record("d", 4, "Completed", later = ""","framesCheck":null"""),
        )

        val items = load()

        assertEquals(
            listOf(DownloadFramesCheck.Passed, DownloadFramesCheck.Suspect, DownloadFramesCheck.NotChecked, DownloadFramesCheck.NotChecked),
            items.map { it.framesCheck },
        )
        assertOriginalFieldsKept(items[2], 3)
        assertEquals(emptyList(), backend.unreadable)
    }

    @Test
    fun a_download_that_a_limit_stopped_is_read_with_the_reason_it_was_stopped_for() {
        backend.payload = payloadOf(
            record("a", 1, "Paused", later = ""","autoDownloadRuleKey":"series|tt0121955","autoDownloadStop":"StorageLimit""""),
            record("b", 2, "Paused", later = ""","autoDownloadRuleKey":"series|tt0121955","autoDownloadStop":null"""),
        )

        val (stopped, paused) = load()

        assertOriginalFieldsKept(stopped, 1)
        assertEquals(AutoDownloadStop.StorageLimit, stopped.autoDownloadStop)
        assertNull(paused.autoDownloadStop)
        assertEquals("series|tt0121955", paused.autoDownloadRuleKey)
    }

    @Test
    fun a_reason_for_stopping_that_this_build_does_not_know_reads_as_none_and_is_written_back_as_it_was() {
        backend.payload = payloadOf(
            record("a", 1, "Paused", later = ""","autoDownloadRuleKey":"series|tt0121955","autoDownloadStop":"MeteredNetwork""""),
            record("b", 2, "Paused"),
        )

        assertNull(load().first().autoDownloadStop)
        rewrite("b")

        assertTrue(""""status":"Downloading"""" in backend.payload.orEmpty())
        assertTrue(""""autoDownloadStop":"MeteredNetwork"""" in backend.payload.orEmpty())
        assertEquals(emptyList(), backend.unreadable)
    }

    @Test
    fun a_download_written_now_is_read_back_with_its_rule() {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:1", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 1, episodeTitle = null,
            episodeThumbnail = null, stream = testStream("https://example.com/1.mkv"), autoDownloadRuleKey = "series|tt1",
        )

        DownloadsRepository.clearLocalState()

        assertEquals("series|tt1", load().single().autoDownloadRuleKey)
        assertNull(load().single().verifiedBy)
    }
}

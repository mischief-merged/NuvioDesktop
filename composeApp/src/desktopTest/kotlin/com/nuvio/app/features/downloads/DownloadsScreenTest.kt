package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.downloads_choose_another_source
import nuvio.composeapp.generated.resources.downloads_clear_failed
import nuvio.composeapp.generated.resources.downloads_error_not_enough_space
import nuvio.composeapp.generated.resources.downloads_failed_count
import nuvio.composeapp.generated.resources.downloads_failed_next_bad_file
import nuvio.composeapp.generated.resources.downloads_failed_next_not_enough_space
import nuvio.composeapp.generated.resources.downloads_failed_next_other
import nuvio.composeapp.generated.resources.downloads_failed_next_source_refused
import nuvio.composeapp.generated.resources.downloads_result_picture_ok
import nuvio.composeapp.generated.resources.downloads_retry_all_failed
import nuvio.composeapp.generated.resources.downloads_status_failed_reason
import nuvio.composeapp.generated.resources.downloads_try_source_again
import nuvio.composeapp.generated.resources.downloads_status_auto
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Downloads screen, driven by the real repository over an in-memory store. No network, no real folder, no real Trash. */
@OptIn(ExperimentalTestApi::class)
class DownloadsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    internal class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val started = mutableListOf<String>()

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

    @Before
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
    }

    @After
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    private fun text(resource: PluralStringResource, quantity: Int) = runBlocking { getPluralString(resource, quantity, quantity) }

    /** One stored download with the fields that have no default, plus [extra]. */
    private fun stored(
        id: String,
        status: String,
        title: String = "Pantheon",
        season: Int? = 2,
        episode: Int? = 3,
        extra: String = "",
        updatedAt: Long = System.currentTimeMillis(),
    ): String {
        val kind = if (season != null) {
            """"contentType":"series","parentMetaType":"series","seasonNumber":$season,"episodeNumber":$episode,"videoId":"tt1:$season:$episode","parentMetaId":"tt1","""
        } else {
            """"contentType":"movie","parentMetaType":"movie","videoId":"tt2","parentMetaId":"tt2","""
        }
        return """{"id":"$id",$kind"title":"$title","streamTitle":"1080p","providerName":"Addon",""" +
            """"sourceUrl":"https://example.com/$id.mkv","fileName":"$id.mkv","status":"$status",""" +
            """"createdAtEpochMs":1,"updatedAtEpochMs":$updatedAt$extra}"""
    }

    private var chooseSourceFor: DownloadItem? = null

    private fun show(vararg items: String, withChooseSource: Boolean = true) {
        backend.payload = """{"items":[${items.joinToString(",")}]}"""
        compose.setContent {
            NuvioTheme {
                DownloadsScreen(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    onChooseAnotherSource = if (withChooseSource) ({ chooseSourceFor = it }) else null,
                )
            }
        }
    }

    private fun failedRow(id: String, kind: String?, message: String, episode: Int = 3) = stored(
        id, "Failed", episode = episode,
        extra = ""","errorMessage":"$message"${kind?.let { ""","failureKind":"$it"""" }.orEmpty()}""",
    )

    @Test
    fun a_download_waiting_on_a_silent_connection_says_it_is_reconnecting() {
        show(
            stored("a", "Downloading", extra = ""","downloadedBytes":10,"totalBytes":100""", updatedAt = 1),
        )

        compose.onNodeWithText(text(DownloadPhase.Reconnecting.message, "10 B / 100 B")).assertIsDisplayed()
    }

    @Test
    fun a_download_with_all_its_bytes_says_it_is_checking_the_file() {
        show(stored("a", "Downloading", extra = ""","downloadedBytes":100,"totalBytes":100""", updatedAt = 1))

        compose.onNodeWithText(text(DownloadPhase.CheckingFile.message)).assertIsDisplayed()
    }

    @Test
    fun a_download_stopped_by_the_storage_limit_says_so_and_is_marked_auto() {
        show(
            stored(
                "a", "Paused",
                extra = ""","downloadedBytes":10,"totalBytes":100,"autoDownloadRuleKey":"r","autoDownloadStop":"StorageLimit"""",
            ),
        )

        compose.onNodeWithText(
            text(Res.string.downloads_status_auto) + " • " + text(DownloadPhase.PausedStorageLimit.message, "10 B / 100 B"),
        ).assertIsDisplayed()
    }

    @Test
    fun a_finished_movie_says_what_it_was_checked_against() {
        show(
            stored(
                "m", "Completed", title = "Film", season = null, episode = null,
                extra = ""","localFileUri":"file:/m.mkv","downloadedBytes":100,"totalBytes":100,"verifiedBy":"Hash","framesCheck":"Passed"""",
            ),
        )

        compose.onNodeWithText(
            text(DownloadResult.VerifiedBySource.message) + " • " + text(Res.string.downloads_result_picture_ok),
        ).assertIsDisplayed()
    }

    @Test
    fun a_suspect_finished_movie_says_so() {
        show(
            stored(
                "m", "Completed", title = "Film", season = null, episode = null,
                extra = ""","localFileUri":"file:/m.mkv","downloadedBytes":100,"totalBytes":100,"verifiedBy":"Size","framesCheck":"Suspect"""",
            ),
        )

        compose.onNodeWithText(text(DownloadResult.Suspect.message)).assertIsDisplayed()
    }

    @Test
    fun a_download_from_before_checks_were_recorded_says_not_checked() {
        show(stored("m", "Completed", title = "Film", season = null, episode = null, extra = ""","localFileUri":"file:/m.mkv","downloadedBytes":100,"totalBytes":100"""))

        compose.onNodeWithText(text(DownloadResult.NotChecked.message)).assertIsDisplayed()
    }

    @Test
    fun a_file_that_failed_its_check_says_why_and_offers_another_source_before_the_same_one() {
        show(failedRow("a", "BadFile", "The downloaded video is blank"))

        compose.onNodeWithText(text(Res.string.downloads_status_failed_reason, "The downloaded video is blank")).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_failed_next_bad_file)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_try_source_again)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.action_retry)).assertDoesNotExist()
    }

    @Test
    fun choosing_another_source_hands_over_the_failed_download() {
        show(failedRow("a", "BadFile", "The downloaded video is blank"))

        compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).performClick()

        assertEquals("a", chooseSourceFor?.id)
        assertEquals(0, backend.started.count { it == "a" }, "choosing another source fetched the old one again")
    }

    @Test
    fun trying_the_same_source_again_is_a_separate_labelled_action() {
        show(failedRow("a", "BadFile", "The downloaded video is blank"))
        val startsBefore = backend.started.count { it == "a" }

        compose.onNodeWithText(text(Res.string.downloads_try_source_again)).performClick()

        compose.waitForIdle()
        assertEquals(startsBefore + 1, backend.started.count { it == "a" })
        assertNull(chooseSourceFor)
    }

    @Test
    fun without_a_way_to_open_the_sources_the_action_is_not_offered() {
        show(failedRow("a", "BadFile", "The downloaded video is blank"), withChooseSource = false)

        compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_try_source_again)).assertIsDisplayed()
    }

    @Test
    fun a_lost_connection_offers_a_plain_retry_and_another_source() {
        show(failedRow("a", "Other", "Connection lost"))

        compose.onNodeWithText(text(Res.string.downloads_status_failed_reason, "Connection lost")).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_failed_next_other)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.action_retry)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).assertIsDisplayed()
    }

    @Test
    fun a_full_disk_says_not_enough_space_and_not_the_system_message() {
        show(failedRow("a", "NotEnoughSpace", "No space left on device"))

        compose.onNodeWithText(text(Res.string.downloads_status_failed_reason, text(Res.string.downloads_error_not_enough_space))).assertIsDisplayed()
        compose.onNodeWithText("No space left on device", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_failed_next_not_enough_space)).assertIsDisplayed()
    }

    @Test
    fun a_refusal_is_not_shown_as_an_http_code() {
        show(failedRow("a", "SourceRefused", "Download failed with HTTP 404"))

        compose.onNodeWithText("HTTP", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_failed_next_source_refused)).assertIsDisplayed()
    }

    @Test
    fun the_list_says_how_many_failed_and_offers_retry_all_and_clear() {
        show(failedRow("a", "Other", "Connection lost", 1), failedRow("b", "Other", "Connection lost", 2), failedRow("c", "BadFile", "The downloaded video is blank", 3))

        compose.onNodeWithText(text(Res.plurals.downloads_failed_count, 3)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_retry_all_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_clear_failed)).assertIsDisplayed()
    }

    @Test
    fun retry_all_starts_each_failed_download_that_can_be_tried_again() {
        show(failedRow("a", "Other", "Connection lost", 1), failedRow("b", "Other", "Connection lost", 2), failedRow("c", "BadFile", "The downloaded video is blank", 3))

        compose.onNodeWithText(text(Res.string.downloads_retry_all_failed)).performClick()

        compose.waitForIdle()
        val items = DownloadsRepository.uiState.value.items.associateBy { it.id }
        assertEquals(DownloadStatus.Downloading, items.getValue("a").status)
        assertEquals(DownloadStatus.Downloading, items.getValue("b").status)
        assertEquals(DownloadStatus.Failed, items.getValue("c").status)
    }

    @Test
    fun retry_all_is_not_offered_when_every_failure_needs_another_source() {
        show(failedRow("c", "BadFile", "The downloaded video is blank", 3))

        compose.onNodeWithText(text(Res.string.downloads_retry_all_failed)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_clear_failed)).assertIsDisplayed()
    }

    @Test
    fun clear_failed_empties_the_list_of_failures_and_leaves_the_rest() {
        show(failedRow("a", "Other", "Connection lost", 1), stored("r", "Paused", episode = 2, extra = ""","downloadedBytes":5"""))

        compose.onNodeWithText(text(Res.string.downloads_clear_failed)).performClick()

        compose.waitForIdle()
        assertEquals(listOf("r"), DownloadsRepository.uiState.value.items.map { it.id })
        compose.onNodeWithText(text(Res.string.downloads_clear_failed)).assertDoesNotExist()
    }
}

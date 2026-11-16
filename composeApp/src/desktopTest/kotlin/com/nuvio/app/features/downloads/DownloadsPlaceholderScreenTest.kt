package com.nuvio.app.features.downloads

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_check_again
import nuvio.composeapp.generated.resources.downloads_check_again_description
import nuvio.composeapp.generated.resources.downloads_check_all
import nuvio.composeapp.generated.resources.downloads_choose_another_source
import nuvio.composeapp.generated.resources.downloads_delete_description
import nuvio.composeapp.generated.resources.downloads_placeholder_next_step
import nuvio.composeapp.generated.resources.downloads_play_description
import nuvio.composeapp.generated.resources.downloads_replace_with_new_copy
import nuvio.composeapp.generated.resources.downloads_section_active
import nuvio.composeapp.generated.resources.downloads_section_movies
import nuvio.composeapp.generated.resources.downloads_status_problem_reason
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A finished download that is not the real video, on the Downloads screen. A list in memory, no real file. */
@OptIn(ExperimentalTestApi::class)
class DownloadsPlaceholderScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = DownloadsScreenTest.FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private val looked = CopyOnWriteArrayList<String>()
    private var chosen: DownloadItem? = null
    private val reason = "Not the real video: 8.1 MB arrived, the source said 9.5 GB"

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

    private fun stored(id: String, extra: String, meta: String = id) =
        """{"id":"$id","contentType":"movie","parentMetaType":"movie","videoId":"tt$meta","parentMetaId":"tt$meta","title":"Film $id",""" +
            """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/$id.mkv","fileName":"$id.mkv",""" +
            """"status":"Completed","createdAtEpochMs":1,"updatedAtEpochMs":1,"localFileUri":"file:/$id.mkv",""" +
            """"downloadedBytes":8509628,"totalBytes":8509628$extra}"""

    private val marked = ""","framesCheck":"NotTheRealVideo","errorMessage":"Not the real video: 8.1 MB arrived, the source said 9.5 GB""""

    private fun show(vararg items: String) {
        backend.payload = """{"items":[${items.joinToString(",")}]}"""
        val rechecks = DownloadRechecks(
            items = { DownloadsRepository.uiState.value.items },
            resolveLocalFileUri = { it.localFileUri },
            isPlaying = { false },
            recheck = { looked += it; RecheckOutcome.LooksRight },
            io = Dispatchers.Default,
        )
        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    onChooseAnotherSource = { chosen = it },
                    rechecks = rechecks,
                )
            }
        }
    }

    @Test
    fun a_marked_row_says_plainly_why_it_is_not_the_video() {
        show(stored("a", marked))

        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_placeholder_next_step)).assertIsDisplayed()
    }

    @Test
    fun a_marked_row_is_read_as_a_problem_by_assistive_technology() {
        val expected = text(Res.string.downloads_status_problem_reason, reason)
        show(stored("a", marked))

        compose.onNodeWithText(reason)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(
                SemanticsMatcher("announces a problem") { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { description ->
                        description.contains(expected)
                    } == true
                },
            )
    }

    @Test
    fun a_marked_row_is_not_shown_as_downloaded_or_playable() {
        show(stored("a", marked), stored("b", ""","verifiedBy":"Size","framesCheck":"Passed""""))

        compose.onNodeWithText(text(Res.string.downloads_section_active)).assertIsDisplayed()
        // The good one is the only movie; the marked one has no play button.
        compose.onNodeWithText(text(Res.string.downloads_section_movies)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_play_description, "Film b")).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_play_description, "Film a")).assertDoesNotExist()
    }

    @Test
    fun a_marked_row_offers_another_source_and_delete() {
        show(stored("a", marked))

        compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).performClick()
        assertEquals("a", chosen?.id)
        compose.onNodeWithContentDescription(text(Res.string.downloads_delete_description, "Film a")).assertIsDisplayed()
    }

    @Test
    fun a_marked_row_can_be_checked_again() {
        show(stored("a", marked))

        compose.onNodeWithContentDescription(text(Res.string.downloads_check_again_description, "Film a")).performClick()
        compose.waitUntil(5_000) { looked.contains("a") }
        assertTrue("a" in looked)
        compose.onNodeWithText(text(Res.string.downloads_check_again)).assertIsDisplayed()
    }

    @Test
    fun the_header_can_check_every_download() {
        show(stored("a", marked), stored("b", ""","verifiedBy":"Size""""))

        compose.onNodeWithContentDescription(text(Res.string.downloads_check_all)).performClick()
        compose.waitUntil(5_000) { looked.size >= 2 }
        assertEquals(setOf("a", "b"), looked.toSet())
    }

    @Test
    fun opening_the_screen_checks_the_due_downloads_at_once() {
        show(stored("a", ""))

        compose.waitUntil(5_000) { looked.contains("a") }
        assertTrue("a" in looked)
    }

    @Test
    fun a_marked_row_offers_to_replace_it_with_the_new_copy_only_when_the_new_copy_is_there() {
        show(stored("a", marked))
        compose.onNodeWithText(text(Res.string.downloads_replace_with_new_copy)).assertDoesNotExist()
    }

    @Test
    fun a_marked_row_with_a_good_copy_beside_it_offers_to_replace_it() {
        // The same title twice: the marked file and a finished good one.
        show(stored("a", marked, meta = "x"), stored("b", ""","verifiedBy":"Size","framesCheck":"Passed"""", meta = "x"))

        compose.onNodeWithText(text(Res.string.downloads_replace_with_new_copy)).assertIsDisplayed()
    }
}

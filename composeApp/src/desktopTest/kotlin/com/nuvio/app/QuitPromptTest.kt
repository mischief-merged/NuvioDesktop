package com.nuvio.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.testDownload
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.quit_downloads_keep_open
import nuvio.composeapp.generated.resources.quit_downloads_message
import nuvio.composeapp.generated.resources.quit_downloads_quit
import nuvio.composeapp.generated.resources.quit_downloads_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class QuitPromptTest {
    @get:Rule
    val compose = createComposeRule()

    private var quit = 0
    private var keptOpen = 0

    private fun text(resource: StringResource) = runBlocking { getString(resource) }

    private fun show(running: Int) {
        compose.setContent {
            NuvioTheme {
                QuitWithDownloadsDialog(activeDownloads = running, onQuit = { quit++ }, onKeepOpen = { keptOpen++ })
            }
        }
    }

    @Test
    fun nothing_running_closes_at_once() {
        assertEquals(QuitDecision.QuitNow, quitDecision(0))
        assertEquals(QuitDecision.QuitNow, quitDecision(activeDownloadCount(emptyList())))
    }

    @Test
    fun running_downloads_ask_first_with_their_number() {
        assertEquals(QuitDecision.AskFirst(1), quitDecision(1))
        assertEquals(QuitDecision.AskFirst(4), quitDecision(4))
    }

    @Test
    fun only_transfers_that_are_running_count() {
        val items = listOf(
            testDownload(1, DownloadStatus.Downloading),
            testDownload(2, DownloadStatus.Downloading),
            testDownload(3, DownloadStatus.Paused),
            testDownload(4, DownloadStatus.Failed),
            testDownload(5, DownloadStatus.Completed),
        )

        assertEquals(2, activeDownloadCount(items))
        assertEquals(QuitDecision.AskFirst(2), quitDecision(activeDownloadCount(items)))
        assertEquals(QuitDecision.QuitNow, quitDecision(activeDownloadCount(items.drop(2))))
    }

    @Test
    fun the_question_says_how_many_downloads_will_pause_and_resume() {
        show(3)

        compose.onNodeWithText(text(Res.string.quit_downloads_title)).assertIsDisplayed()
        compose.onNodeWithText(runBlocking { getPluralString(Res.plurals.quit_downloads_message, 3, 3) }).assertIsDisplayed()
        compose.onNodeWithText("3 downloads are in progress. They will pause and resume next time you open Nuvio.").assertIsDisplayed()
    }

    @Test
    fun one_download_is_said_in_the_singular() {
        show(1)

        compose.onNodeWithText("1 download is in progress. It will pause and resume next time you open Nuvio.").assertIsDisplayed()
    }

    @Test
    fun quit_and_keep_open_are_buttons_that_do_what_they_say() {
        show(2)
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

        compose.onNode(hasText(text(Res.string.quit_downloads_quit)) and button).assertHasClickAction()
        compose.onNode(hasText(text(Res.string.quit_downloads_keep_open)) and button).assertHasClickAction().performClick()

        assertEquals(0, quit)
        assertEquals(1, keptOpen)
    }

    @Test
    fun quit_quits_only_when_it_is_chosen() {
        show(2)

        compose.onNodeWithText(text(Res.string.quit_downloads_quit)).performClick()

        assertEquals(1, quit)
        assertEquals(0, keptOpen)
    }

    @Test
    fun both_buttons_work_from_the_keyboard() {
        show(2)

        compose.onNodeWithText(text(Res.string.quit_downloads_keep_open)).requestFocus().performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, keptOpen)
        compose.onNodeWithText(text(Res.string.quit_downloads_quit)).requestFocus().performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, quit)
    }
}

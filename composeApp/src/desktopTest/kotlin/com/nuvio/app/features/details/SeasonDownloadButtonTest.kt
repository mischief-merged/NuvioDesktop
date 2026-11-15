package com.nuvio.app.features.details

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.SeasonDownloadButton
import com.nuvio.app.features.downloads.AutoDownloadController
import com.nuvio.app.features.downloads.AutoDownloadRule
import com.nuvio.app.features.downloads.AutoDownloadScope
import com.nuvio.app.features.downloads.AutoDownloadStatus
import com.nuvio.app.features.downloads.SeasonBatchProgress
import com.nuvio.app.features.downloads.seasonBatchKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_season_button
import nuvio.composeapp.generated.resources.downloads_season_dialog_title
import nuvio.composeapp.generated.resources.downloads_season_quality_1080
import nuvio.composeapp.generated.resources.downloads_season_quality_any
import nuvio.composeapp.generated.resources.downloads_auto_button
import nuvio.composeapp.generated.resources.downloads_auto_button_off
import nuvio.composeapp.generated.resources.downloads_auto_button_on
import nuvio.composeapp.generated.resources.downloads_auto_turn_off
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.downloads_button_auto_on
import nuvio.composeapp.generated.resources.downloads_button_running
import nuvio.composeapp.generated.resources.downloads_button_state_running
import nuvio.composeapp.generated.resources.downloads_season_audio_language
import nuvio.composeapp.generated.resources.downloads_season_start
import nuvio.composeapp.generated.resources.downloads_season_audio_language_any
import nuvio.composeapp.generated.resources.downloads_season_bitrate_no_limit
import nuvio.composeapp.generated.resources.downloads_season_bitrate_value
import nuvio.composeapp.generated.resources.downloads_season_max_bitrate
import nuvio.composeapp.generated.resources.downloads_season_quality_heading
import nuvio.composeapp.generated.resources.downloads_season_stop
import nuvio.composeapp.generated.resources.episodes_season
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SeasonDownloadButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val running = MutableStateFlow(emptyMap<String, SeasonBatchProgress>())
    private val stopped = mutableListOf<Pair<String, Int>>()

    private val show = MetaDetails(
        id = "button-test",
        type = "series",
        name = "Show",
        videos = listOf(
            MetaVideo(id = "button-test:1:1", title = "One", released = "2020-01-01T00:00:00Z", season = 1, episode = 1),
        ),
    )

    private val dialogTitle get() = text(Res.string.downloads_season_dialog_title, text(Res.string.episodes_season, 1))

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun isRadioButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun the_idle_button_is_a_named_button_that_opens_a_menu_of_two_named_buttons() {
        showButton()

        pageButton(text(Res.string.downloads_season_start))
            .assertIsDisplayed()
            .assert(isButton())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(Res.string.downloads_auto_button_off)))
            .assertHasClickAction()
            .performClick()

        compose.onNodeWithContentDescription(dialogTitle).assert(isButton()).assertHasClickAction()
        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_button)).assert(isButton()).assertHasClickAction()
        compose.onNodeWithText(text(Res.string.downloads_season_button)).performClick()
        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
        assertEquals(emptyList(), stopped)
    }

    @Test
    fun while_a_season_is_being_added_the_button_shows_the_progress_and_still_opens_the_menu() {
        running.value = mapOf(seasonBatchKey("button-test", 1) to SeasonBatchProgress(total = 8, finished = 3))
        showButton()
        val runningLabel = text(Res.string.downloads_button_running, text(Res.string.downloads_season_start), 3, 8)
        val stopLabel = text(Res.string.downloads_season_stop, 3, 8)

        compose.onNodeWithText(runningLabel).assertIsDisplayed()
        pageButton(runningLabel)
            .assert(isButton())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(Res.string.downloads_button_state_running, text(Res.string.downloads_auto_button_off), 3, 8)))
            .performClick()

        assertEquals(emptyList(), stopped)
        compose.onNodeWithContentDescription(stopLabel).assert(isButton()).assertHasClickAction()
        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_button)).assert(isButton()).assertHasClickAction()
        compose.onNodeWithText(text(Res.string.downloads_season_button)).assertDoesNotExist()
    }

    @Test
    fun stop_is_the_first_entry_of_the_menu_while_a_season_is_being_added() {
        running.value = mapOf(seasonBatchKey("button-test", 1) to SeasonBatchProgress(total = 8, finished = 3))
        showButton()
        pageButton(text(Res.string.downloads_button_running, text(Res.string.downloads_season_start), 3, 8)).performClick()

        val stop = compose.onNodeWithContentDescription(text(Res.string.downloads_season_stop, 3, 8))
        val autoDownload = compose.onNodeWithContentDescription(text(Res.string.downloads_auto_button))
        assertTrue(stop.fetchSemanticsNode().boundsInRoot.bottom <= autoDownload.fetchSemanticsNode().boundsInRoot.top)
        stop.performClick()

        assertEquals(listOf("button-test" to 1), stopped)
        compose.onNodeWithText(dialogTitle).assertDoesNotExist()
    }

    @Test
    fun the_automatic_download_rule_can_be_opened_and_changed_while_a_season_is_being_added() {
        autoDownloads.rules.value = listOf(AutoDownloadRule("series", "button-test", "Show"))
        running.value = mapOf(seasonBatchKey("button-test", 1) to SeasonBatchProgress(total = 2, finished = 0))
        showButton()
        val runningLabel = text(Res.string.downloads_button_running, text(Res.string.downloads_button_auto_on), 0, 2)

        compose.onNodeWithText(runningLabel).assertIsDisplayed()
        pageButton(runningLabel).performClick()
        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_button_on)).performClick()

        compose.onNodeWithText(text(Res.string.downloads_auto_turn_off)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.action_save)).performClick()
        assertEquals(1, autoDownloads.saves)
        assertEquals(emptyList(), stopped)
    }

    @Test
    fun a_menu_that_was_open_when_the_season_started_stops_it_and_does_not_open_the_dialog() {
        showButton()
        pageButton(text(Res.string.downloads_season_start)).performClick()

        compose.runOnIdle { running.value = mapOf(seasonBatchKey("button-test", 1) to SeasonBatchProgress(total = 8, finished = 1)) }
        val stopLabel = text(Res.string.downloads_season_stop, 1, 8)
        compose.onNode(hasContentDescription(stopLabel) and hasAnyAncestor(isPopup())).assert(isButton()).performClick()

        assertEquals(listOf("button-test" to 1), stopped)
        compose.onNodeWithText(dialogTitle).assertDoesNotExist()
    }

    @Test
    fun a_show_with_automatic_downloads_says_so_on_the_button_and_in_the_menu() {
        autoDownloads.rules.value = listOf(AutoDownloadRule("series", "button-test", "Show"))
        showButton()
        val label = text(Res.string.downloads_button_auto_on)

        compose.onNodeWithText(label).assertIsDisplayed()
        pageButton(label)
            .assert(isButton())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(Res.string.downloads_auto_button_on)))
            .performClick()

        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_button_on)).assert(isButton()).performClick()
        compose.onNodeWithText(text(Res.string.downloads_auto_turn_off)).assertIsDisplayed()
    }

    @Test
    fun turning_automatic_downloads_on_changes_the_button_at_once() {
        showButton()
        compose.onNodeWithText(text(Res.string.downloads_season_start)).assertIsDisplayed()

        compose.runOnIdle { autoDownloads.rules.value = listOf(AutoDownloadRule("series", "button-test", "Show")) }

        compose.onNodeWithText(text(Res.string.downloads_button_auto_on)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_start)).assertDoesNotExist()
    }

    @Test
    fun the_menu_is_opened_and_used_with_the_keyboard() {
        showButton()

        pageButton(text(Res.string.downloads_season_start)).requestFocus().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription(dialogTitle).requestFocus().performKeyInput { pressKey(Key.Enter) }

        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
    }

    @Test
    fun the_progress_follows_the_batch_and_the_button_returns_to_idle_when_it_ends() {
        running.value = mapOf(seasonBatchKey("button-test", 1) to SeasonBatchProgress(total = 8, finished = 3))
        showButton()

        compose.runOnIdle { running.value = mapOf(seasonBatchKey("button-test", 1) to SeasonBatchProgress(total = 8, finished = 4)) }
        compose.onNodeWithText(text(Res.string.downloads_button_running, text(Res.string.downloads_season_start), 4, 8)).assertIsDisplayed()

        compose.runOnIdle { running.value = emptyMap() }
        compose.onNodeWithText(text(Res.string.downloads_season_start)).assertIsDisplayed()
    }

    @Test
    fun a_batch_of_another_season_does_not_change_the_button() {
        running.value = mapOf(seasonBatchKey("button-test", 2) to SeasonBatchProgress(total = 8, finished = 3))
        showButton()

        compose.onNodeWithText(text(Res.string.downloads_season_start)).assertIsDisplayed()
    }

    @Test
    fun the_button_is_operated_with_the_keyboard() {
        running.value = mapOf(seasonBatchKey("button-test", 1) to SeasonBatchProgress(total = 8, finished = 3))
        showButton()

        pageButton(text(Res.string.downloads_button_running, text(Res.string.downloads_season_start), 3, 8))
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_stop, 3, 8)).requestFocus().performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("button-test" to 1), stopped)
    }

    @Test
    fun the_three_choices_are_buttons_named_by_their_title_with_the_current_value_as_state() {
        showButton()
        openSeasonDialog()

        mapOf(
            Res.string.downloads_season_quality_heading to text(Res.string.downloads_season_quality_any),
            Res.string.downloads_season_max_bitrate to text(Res.string.downloads_season_bitrate_no_limit),
            Res.string.downloads_season_audio_language to text(Res.string.downloads_season_audio_language_any),
        ).forEach { (title, value) ->
            compose.onNodeWithContentDescription(text(title))
                .assert(isButton())
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
                .assertHasClickAction()
        }
    }

    @Test
    fun a_choice_is_opened_with_the_keyboard_and_its_options_are_named_radio_buttons_of_one_group() {
        showButton()
        openSeasonDialog()

        compose.onNodeWithContentDescription(text(Res.string.downloads_season_max_bitrate))
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }

        val noLimit = text(Res.string.downloads_season_bitrate_no_limit)
        compose.onNode(hasContentDescription(noLimit) and isRadioButton())
            .assert(hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)))
            .assertIsSelected()
        val tenMbps = text(Res.string.downloads_season_bitrate_value, 10)
        compose.onNode(hasContentDescription(tenMbps) and isRadioButton())
            .assertIsNotSelected()
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }

        compose.onNodeWithContentDescription(text(Res.string.downloads_season_max_bitrate))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, tenMbps))
    }

    @Test
    fun the_quality_options_are_named_radio_buttons_too() {
        showButton()
        openSeasonDialog()
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_quality_heading)).performClick()

        compose.onNode(hasContentDescription(text(Res.string.downloads_season_quality_any)) and isRadioButton()).assertIsSelected()
        compose.onNode(hasContentDescription(text(Res.string.downloads_season_quality_1080)) and isRadioButton()).assertIsNotSelected()
    }

    /** The control on the page, found by its accessible name. */
    private fun pageButton(name: String) = compose.onNodeWithContentDescription(name)

    private fun openSeasonDialog() {
        pageButton(text(Res.string.downloads_season_start)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_season_button)).performClick()
    }

    private class FakeAutoDownloads : AutoDownloadController {
        override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
        override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())

        var saves = 0

        override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope) {
            saves++
        }

        override fun turnOff(profileId: Int, ruleKey: String) = Unit

        override fun checkNow() = Unit
    }

    private val autoDownloads = FakeAutoDownloads()

    private fun showButton() {
        compose.setContent {
            NuvioTheme {
                SeasonDownloadButton(
                    meta = show,
                    seasonNumber = 1,
                    profileId = 9_900_009,
                    running = running,
                    cancelBatch = { id, season -> stopped += id to season },
                    autoDownloads = autoDownloads,
                )
            }
        }
    }

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }
}

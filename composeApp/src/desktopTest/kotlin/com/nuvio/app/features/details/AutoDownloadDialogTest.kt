package com.nuvio.app.features.details

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.SeasonDownloadButton
import com.nuvio.app.features.downloads.AutoDownloadController
import com.nuvio.app.features.downloads.AutoDownloadRule
import com.nuvio.app.features.downloads.AutoDownloadScope
import com.nuvio.app.features.downloads.AutoDownloadStatus
import com.nuvio.app.features.downloads.SeasonBatchStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.downloads_auto_button
import nuvio.composeapp.generated.resources.downloads_auto_button_on
import nuvio.composeapp.generated.resources.downloads_auto_dialog_title
import nuvio.composeapp.generated.resources.downloads_auto_keep_heading
import nuvio.composeapp.generated.resources.downloads_auto_keep_option
import nuvio.composeapp.generated.resources.downloads_auto_scope_heading
import nuvio.composeapp.generated.resources.downloads_auto_scope_latest
import nuvio.composeapp.generated.resources.downloads_auto_scope_next
import nuvio.composeapp.generated.resources.downloads_auto_turn_off
import nuvio.composeapp.generated.resources.downloads_auto_turn_on
import nuvio.composeapp.generated.resources.downloads_button_auto_on
import nuvio.composeapp.generated.resources.downloads_season_button
import nuvio.composeapp.generated.resources.downloads_season_start
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AutoDownloadDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeController : AutoDownloadController {
        override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
        override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
        val saved = mutableListOf<Triple<String, Int, AutoDownloadScope>>()
        val turnedOff = mutableListOf<String>()
        val profiles = mutableListOf<Int>()

        override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope) {
            profiles += profileId
            saved += Triple(meta.id, keepCount, scope)
        }

        override fun turnOff(profileId: Int, ruleKey: String) {
            profiles += profileId
            turnedOff += ruleKey
        }

        override fun checkNow() = Unit
    }

    private val controller = FakeController()
    private val profile = 9_900_005
    private val otherProfile = 9_900_006
    private var shownProfile by mutableIntStateOf(profile)

    private val show = MetaDetails(
        id = "auto-dialog-test",
        type = "series",
        name = "Show",
        videos = listOf(
            MetaVideo(id = "auto-dialog-test:1:1", title = "One", released = "2020-01-01T00:00:00Z", season = 1, episode = 1),
        ),
    )

    private val existingRule = AutoDownloadRule(
        parentMetaType = "series",
        parentMetaId = "auto-dialog-test",
        title = "Show",
        keepCount = 10,
        scope = AutoDownloadScope.LatestReleased,
    )

    @Test
    fun the_download_button_opens_a_menu_with_download_season_and_auto_download() {
        showButton()

        compose.onNodeWithContentDescription(text(Res.string.downloads_season_start)).performClick()

        compose.onNodeWithText(text(Res.string.downloads_season_button)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_auto_button)).assertIsDisplayed()
    }

    @Test
    fun the_dialog_has_headings_and_starts_from_the_default_choices() {
        showButton()

        openDialog(Res.string.downloads_auto_button)

        compose.onNodeWithText(text(Res.string.downloads_auto_dialog_title, "Show")).assertIsDisplayed()
        compose.onNode(hasText(text(Res.string.downloads_auto_keep_heading)) and isHeading()).assertIsDisplayed()
        compose.onNode(hasText(text(Res.string.downloads_auto_scope_heading)) and isHeading()).assertExists()
        compose.onNodeWithText(episodes(AutoDownloadRule.DefaultKeepCount)).assertIsSelected()
        compose.onNodeWithText(text(Res.string.downloads_auto_scope_next)).assertIsSelected()
    }

    @Test
    fun turning_it_on_saves_the_chosen_count_and_episodes() {
        showButton()
        openDialog(Res.string.downloads_auto_button)

        compose.onNodeWithText(episodes(5)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_auto_scope_latest)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_auto_turn_on)).performClick()

        assertEquals(listOf(Triple("auto-dialog-test", 5, AutoDownloadScope.LatestReleased)), controller.saved)
        compose.onNodeWithText(text(Res.string.downloads_auto_dialog_title, "Show")).assertDoesNotExist()
    }

    @Test
    fun the_options_are_named_radio_buttons_of_two_groups_and_are_chosen_with_the_keyboard() {
        showButton()
        openDialog(Res.string.downloads_auto_button)
        val radioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val inGroup = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))

        (AutoDownloadRule.keepCountOptions.map(::episodes) + text(Res.string.downloads_auto_scope_next) + text(Res.string.downloads_auto_scope_latest))
            .forEach { name -> compose.onNodeWithContentDescription(name).assert(radioButton).assert(inGroup) }
        compose.onNodeWithContentDescription(episodes(10)).requestFocus().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription(episodes(10)).assertIsSelected()
        compose.onNodeWithContentDescription(episodes(AutoDownloadRule.DefaultKeepCount)).assertIsNotSelected()
    }

    @Test
    fun cancel_changes_nothing() {
        showButton()
        openDialog(Res.string.downloads_auto_button)

        compose.onNodeWithText(episodes(1)).performClick()
        compose.onNodeWithText(text(Res.string.action_cancel)).performClick()

        assertEquals(emptyList(), controller.saved)
        assertEquals(emptyList(), controller.turnedOff)
        compose.onNodeWithText(text(Res.string.downloads_auto_dialog_title, "Show")).assertDoesNotExist()
    }

    @Test
    fun a_show_with_a_rule_says_so_in_the_menu_and_opens_with_its_choices() {
        controller.rules.value = listOf(existingRule)
        showButton()

        openDialog(Res.string.downloads_auto_button_on)

        compose.onNodeWithText(episodes(10)).assertIsSelected()
        compose.onNodeWithText(text(Res.string.downloads_auto_scope_latest)).assertIsSelected()
        compose.onNodeWithText(episodes(2)).performClick()
        compose.onNodeWithText(text(Res.string.action_save)).performClick()
        assertEquals(listOf(Triple("auto-dialog-test", 2, AutoDownloadScope.LatestReleased)), controller.saved)
    }

    @Test
    fun seven_episodes_can_be_chosen_and_a_rule_that_keeps_seven_opens_with_seven() {
        showButton()
        openDialog(Res.string.downloads_auto_button)
        compose.onNodeWithContentDescription(episodes(7)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_auto_turn_on)).performClick()
        assertEquals(listOf(Triple("auto-dialog-test", 7, AutoDownloadScope.NextUnwatched)), controller.saved)

        compose.runOnIdle { controller.rules.value = listOf(existingRule.copy(keepCount = 7, scope = AutoDownloadScope.NextUnwatched)) }
        openDialog(Res.string.downloads_auto_button_on)
        compose.onNodeWithContentDescription(episodes(7)).assertIsSelected()
        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_scope_next)).assertIsSelected()
    }

    @Test
    fun turn_off_removes_the_rule_of_the_show() {
        controller.rules.value = listOf(existingRule)
        showButton()
        openDialog(Res.string.downloads_auto_button_on)

        compose.onNodeWithText(text(Res.string.downloads_auto_turn_off)).performClick()

        assertEquals(listOf(existingRule.key), controller.turnedOff)
        assertEquals(emptyList(), controller.saved)
    }

    @Test
    fun a_profile_switch_while_the_dialog_is_open_keeps_the_change_with_the_profile_it_was_opened_for() {
        showButton()
        openDialog(Res.string.downloads_auto_button)

        compose.runOnIdle { shownProfile = otherProfile }
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.downloads_auto_turn_on)).performClick()

        assertEquals(listOf(profile), controller.profiles)
    }

    @Test
    fun turn_off_is_for_the_profile_the_dialog_was_opened_for() {
        controller.rules.value = listOf(existingRule)
        showButton()
        openDialog(Res.string.downloads_auto_button_on)

        compose.runOnIdle { shownProfile = otherProfile }
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.downloads_auto_turn_off)).performClick()

        assertEquals(listOf(profile), controller.profiles)
    }

    private fun showButton() {
        compose.setContent {
            NuvioTheme {
                SeasonDownloadButton(
                    meta = show,
                    seasonNumber = 1,
                    profileId = shownProfile,
                    startBatch = { SeasonBatchStart.Started },
                    autoDownloads = controller,
                )
            }
        }
    }

    private fun openDialog(menuItem: StringResource) {
        val pageButton = if (controller.rules.value.isEmpty()) Res.string.downloads_season_start else Res.string.downloads_button_auto_on
        compose.onNodeWithContentDescription(text(pageButton)).performClick()
        compose.onNodeWithText(text(menuItem)).performClick()
    }

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun episodes(count: Int): String =
        runBlocking { getPluralString(Res.plurals.downloads_auto_keep_option, count, count) }

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }
}

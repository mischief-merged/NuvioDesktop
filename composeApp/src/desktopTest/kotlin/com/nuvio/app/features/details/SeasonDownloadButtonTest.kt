package com.nuvio.app.features.details

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.SeasonDownloadButton
import com.nuvio.app.features.downloads.SeasonBatchProgress
import com.nuvio.app.features.downloads.seasonBatchKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_season_button
import nuvio.composeapp.generated.resources.downloads_season_dialog_title
import nuvio.composeapp.generated.resources.downloads_season_quality_1080
import nuvio.composeapp.generated.resources.downloads_season_quality_any
import nuvio.composeapp.generated.resources.downloads_season_quality_heading
import nuvio.composeapp.generated.resources.downloads_season_stop
import nuvio.composeapp.generated.resources.episodes_season
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun the_idle_button_is_a_named_button_that_opens_the_dialog() {
        showButton()

        compose.onNodeWithText(text(Res.string.downloads_season_button))
            .assertIsDisplayed()
            .assert(isButton())
            .assert(hasContentDescription(dialogTitle))
            .assertHasClickAction()
            .performClick()

        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
        assertEquals(emptyList(), stopped)
    }

    @Test
    fun while_a_season_is_being_added_the_button_shows_the_progress_and_one_click_stops() {
        running.value = mapOf(seasonBatchKey("button-test", 1) to SeasonBatchProgress(total = 8, finished = 3))
        showButton()
        val stopLabel = text(Res.string.downloads_season_stop, 3, 8)

        compose.onNodeWithText(text(Res.string.downloads_season_button)).assertDoesNotExist()
        compose.onNodeWithText(stopLabel)
            .assertIsDisplayed()
            .assert(isButton())
            .assert(hasContentDescription(stopLabel))
            .performClick()

        assertEquals(listOf("button-test" to 1), stopped)
        compose.onNodeWithText(dialogTitle).assertDoesNotExist()
    }

    @Test
    fun the_progress_follows_the_batch_and_the_button_returns_to_idle_when_it_ends() {
        running.value = mapOf(seasonBatchKey("button-test", 1) to SeasonBatchProgress(total = 8, finished = 3))
        showButton()

        compose.runOnIdle { running.value = mapOf(seasonBatchKey("button-test", 1) to SeasonBatchProgress(total = 8, finished = 4)) }
        compose.onNodeWithText(text(Res.string.downloads_season_stop, 4, 8)).assertIsDisplayed()

        compose.runOnIdle { running.value = emptyMap() }
        compose.onNodeWithText(text(Res.string.downloads_season_button)).assertIsDisplayed()
    }

    @Test
    fun a_batch_of_another_season_does_not_change_the_button() {
        running.value = mapOf(seasonBatchKey("button-test", 2) to SeasonBatchProgress(total = 8, finished = 3))
        showButton()

        compose.onNodeWithText(text(Res.string.downloads_season_button)).assertIsDisplayed()
    }

    @Test
    fun the_button_is_operated_with_the_keyboard() {
        running.value = mapOf(seasonBatchKey("button-test", 1) to SeasonBatchProgress(total = 8, finished = 3))
        showButton()

        compose.onNodeWithText(text(Res.string.downloads_season_stop, 3, 8)).requestFocus().performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("button-test" to 1), stopped)
    }

    @Test
    fun the_quality_options_are_named_radio_buttons_under_a_heading() {
        showButton()
        compose.onNodeWithText(text(Res.string.downloads_season_button)).performClick()

        compose.onNodeWithText(text(Res.string.downloads_season_quality_heading))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_quality_any))
            .assert(isRadioButton())
            .assert(hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)))
            .assertIsSelected()
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_quality_1080))
            .assert(isRadioButton())
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_quality_1080)).assertIsSelected()
    }

    private fun showButton() {
        compose.setContent {
            NuvioTheme {
                SeasonDownloadButton(
                    meta = show,
                    seasonNumber = 1,
                    running = running,
                    cancelBatch = { id, season -> stopped += id to season },
                )
            }
        }
    }

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }
}

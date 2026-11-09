package com.nuvio.app.features.settings

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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.downloads.DownloadBandwidth
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_download_speed_limit_title
import nuvio.composeapp.generated.resources.settings_download_speed_limit_unlimited
import nuvio.composeapp.generated.resources.settings_download_speed_limit_value
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class DownloadSpeedSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private val title get() = text(Res.string.settings_download_speed_limit_title)
    private val unlimited get() = text(Res.string.settings_download_speed_limit_unlimited)

    private fun speed(megabytesPerSecond: Int) = text(Res.string.settings_download_speed_limit_value, megabytesPerSecond)

    private fun isRadioButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Before
    @After
    fun start_and_end_without_a_limit() {
        DownloadBandwidth.setUserCap(null)
    }

    @Test
    fun the_row_shows_its_title_and_the_current_limit_and_opens_the_picker() {
        show()

        compose.onNode(hasText(title) and hasText(unlimited)).assertIsDisplayed().assertHasClickAction().performClick()

        compose.onNodeWithContentDescription(unlimited).assertIsDisplayed()
    }

    @Test
    fun every_speed_is_a_named_radio_button_of_one_group() {
        show()
        compose.onNode(hasText(title) and hasText(unlimited)).performClick()

        (listOf(unlimited) + listOf(1, 2, 5, 10, 25).map(::speed)).forEach { name ->
            compose.onNodeWithContentDescription(name)
                .assert(isRadioButton())
                .assert(hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)))
                .assertHasClickAction()
        }
        compose.onNodeWithContentDescription(unlimited).assertIsSelected()
        compose.onNodeWithContentDescription(speed(5)).assertIsNotSelected()
    }

    @Test
    fun a_speed_is_chosen_with_the_keyboard_and_becomes_the_limit() {
        show()
        compose.onNode(hasText(title) and hasText(unlimited)).requestFocus().performKeyInput { pressKey(Key.Enter) }

        compose.onNodeWithContentDescription(speed(5)).requestFocus().performKeyInput { pressKey(Key.Enter) }

        assertEquals(5_000_000L, DownloadBandwidth.userCapBytesPerSecond.value)
        compose.onNode(hasText(title) and hasText(speed(5))).assertIsDisplayed()
        compose.onNodeWithContentDescription(speed(5)).assertDoesNotExist()
    }

    @Test
    fun the_chosen_speed_is_the_selected_one_when_the_picker_reopens_and_unlimited_removes_the_limit() {
        DownloadBandwidth.setUserCap(10_000_000L)
        show()
        compose.onNode(hasText(title) and hasText(speed(10))).performClick()

        compose.onNodeWithContentDescription(speed(10)).assertIsSelected()
        compose.onNodeWithContentDescription(unlimited).assertIsNotSelected().performClick()

        assertNull(DownloadBandwidth.userCapBytesPerSecond.value)
    }

    private fun show() {
        compose.setContent {
            NuvioTheme {
                DownloadSpeedSetting(isTablet = false)
            }
        }
    }

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }
}

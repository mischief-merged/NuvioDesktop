package com.nuvio.app.features.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.streams.StreamAutoPlayMode
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_stream_selection_mode_preferred
import nuvio.composeapp.generated.resources.settings_playback_stream_selection_mode_preferred_description
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamAutoPlayModeDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val chosen = mutableListOf<StreamAutoPlayMode>()

    private fun show(selected: StreamAutoPlayMode) {
        compose.setContent {
            NuvioTheme {
                StreamAutoPlayModeDialog(
                    selectedMode = selected,
                    onModeSelected = { chosen += it },
                    onDismiss = {},
                )
            }
        }
    }

    private fun text(res: org.jetbrains.compose.resources.StringResource) = runBlocking { getString(res) }

    @Test
    fun the_preferred_option_is_listed_with_a_description_that_names_the_download_choices() {
        show(StreamAutoPlayMode.MANUAL)

        compose.onNodeWithText(text(Res.string.settings_playback_stream_selection_mode_preferred)).assertIsDisplayed()
        val description = text(Res.string.settings_playback_stream_selection_mode_preferred_description)
        compose.onNodeWithText(description).assertIsDisplayed()
        listOf("quality", "bitrate", "language", "download").forEach { word -> assertTrue(word in description, word) }
    }

    @Test
    fun the_option_is_a_radio_button_that_reports_whether_it_is_selected() {
        show(StreamAutoPlayMode.PREFERRED)
        val title = text(Res.string.settings_playback_stream_selection_mode_preferred)
        val option = compose.onNode(hasText(title).and(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)), useUnmergedTree = false)

        option.assertIsSelected()
        option.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    @Test
    fun the_option_is_not_selected_for_another_mode_and_choosing_it_reports_the_new_mode() {
        show(StreamAutoPlayMode.FIRST_STREAM)
        val title = text(Res.string.settings_playback_stream_selection_mode_preferred)

        compose.onNodeWithText(title).assertIsNotSelected()
        compose.onNodeWithText(title).performClick()

        assertEquals(listOf(StreamAutoPlayMode.PREFERRED), chosen)
    }
}

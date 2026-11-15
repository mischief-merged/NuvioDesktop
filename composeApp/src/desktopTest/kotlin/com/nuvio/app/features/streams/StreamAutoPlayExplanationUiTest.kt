package com.nuvio.app.features.streams

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_auto_bitrate_skipped
import nuvio.composeapp.generated.resources.streams_auto_change_preferences
import nuvio.composeapp.generated.resources.streams_auto_no_match
import nuvio.composeapp.generated.resources.streams_auto_no_match_plain
import nuvio.composeapp.generated.resources.streams_auto_preference_bitrate
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamAutoPlayExplanationUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val preferences = SeasonDownloadDefaults(BatchQuality.P1080, maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN)
    private var opened = 0

    private fun show(context: AutoPlayContext, sourceCount: Int, link: Boolean = true) {
        compose.setContent {
            NuvioTheme {
                AutoPlayNoMatchBanner(
                    context = context,
                    sourceCount = sourceCount,
                    onOpenPreferences = if (link) ({ opened++ }) else null,
                )
            }
        }
    }

    private fun noMatchText(count: Int, preferencesText: String): String =
        runBlocking { getPluralString(Res.plurals.streams_auto_no_match, count, count, preferencesText) }

    @Test
    fun the_banner_names_the_source_count_and_the_applied_preferences() {
        val bitrate = runBlocking { getString(Res.string.streams_auto_preference_bitrate, 10) }
        show(autoPlayContextFor(preferences, runtimeMinutes = 50), sourceCount = 7)

        compose.onNodeWithText(noMatchText(7, "1080p, $bitrate, English")).assertIsDisplayed()
        compose.onNodeWithText(runBlocking { getString(Res.string.streams_auto_bitrate_skipped) }).assertDoesNotExist()
    }

    @Test
    fun a_bitrate_limit_that_could_not_be_applied_is_not_named_as_a_reason_and_is_explained() {
        show(autoPlayContextFor(preferences, runtimeMinutes = null), sourceCount = 3)

        compose.onNodeWithText(noMatchText(3, "1080p, English")).assertIsDisplayed()
        compose.onNodeWithText(runBlocking { getString(Res.string.streams_auto_bitrate_skipped) }).assertIsDisplayed()
    }

    @Test
    fun the_banner_has_a_link_to_the_preferences_page() {
        show(autoPlayContextFor(preferences, runtimeMinutes = 50), sourceCount = 2)

        compose.onNodeWithContentDescription(runBlocking { getString(Res.string.streams_auto_change_preferences) }).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun without_a_way_to_open_settings_there_is_no_link() {
        show(autoPlayContextFor(preferences, runtimeMinutes = 50), sourceCount = 2, link = false)

        compose.onNodeWithContentDescription(runBlocking { getString(Res.string.streams_auto_change_preferences) }).assertDoesNotExist()
    }

    @Test
    fun a_miss_without_any_applied_preference_says_so_plainly() {
        show(AutoPlayContext(), sourceCount = 4)

        compose.onNodeWithText(runBlocking { getString(Res.string.streams_auto_no_match_plain) }).assertIsDisplayed()
    }

    @Test
    fun the_chosen_source_message_names_source_quality_and_language() {
        var message = ""
        compose.setContent {
            NuvioTheme {
                message = autoPlayChoiceMessage(
                    AutoPlayChoice("AddonA", DebridStreamResolution.P1080, DebridStreamLanguage.EN, withinBitrateMbps = 10),
                )
            }
        }
        compose.waitForIdle()

        assertEquals(
            "Chosen automatically: AddonA • 1080p • English • within 10 megabits per second",
            message,
        )
    }
}

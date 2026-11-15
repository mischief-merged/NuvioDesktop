package com.nuvio.app.features.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.PreferenceSetupAnswer
import com.nuvio.app.features.downloads.PreferenceSetupRepository
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsRepository
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsStorage
import com.nuvio.app.features.streams.StreamAutoPlayMode
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.downloads_season_audio_language
import nuvio.composeapp.generated.resources.downloads_season_audio_language_prefilled
import nuvio.composeapp.generated.resources.downloads_season_audio_language_any
import nuvio.composeapp.generated.resources.downloads_season_bitrate_no_limit
import nuvio.composeapp.generated.resources.downloads_season_quality_1080
import nuvio.composeapp.generated.resources.downloads_season_quality_720
import nuvio.composeapp.generated.resources.downloads_season_quality_any
import nuvio.composeapp.generated.resources.downloads_season_quality_heading
import nuvio.composeapp.generated.resources.settings_preferences_play_ask
import nuvio.composeapp.generated.resources.settings_preferences_play_best
import nuvio.composeapp.generated.resources.settings_preferences_setup_message
import nuvio.composeapp.generated.resources.settings_preferences_setup_skip
import nuvio.composeapp.generated.resources.settings_preferences_setup_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreferenceSetupDialogTest {
    @get:Rule
    val compose = createComposeRule()

    // Profile ids no real profile uses, so stored preferences of real profiles are never touched.
    private val profile = 9_900_031

    private var canInterrupt by mutableStateOf(true)
    private var hostGeneration by mutableStateOf(0)
    private var playMode = StreamAutoPlayMode.MANUAL
    private val modeChanges = mutableListOf<StreamAutoPlayMode>()

    @Before
    @After
    fun clear_test_profile() {
        SeasonDownloadDefaultsStorage.savePayload(profile, "")
        SeasonDownloadDefaultsStorage.saveSetupAnswer(profile, "")
    }

    @Test
    fun a_new_profile_is_asked_with_the_neutral_choices_preselected() {
        show()

        compose.onNodeWithText(text(Res.string.settings_preferences_setup_title)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_season_quality_any)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_season_bitrate_no_limit)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_season_audio_language_any)).assertIsDisplayed()
        radio(Res.string.settings_preferences_play_ask).assertIsSelected()
        radio(Res.string.settings_preferences_play_best).assertIsNotSelected()
    }

    @Test
    fun the_download_language_starts_from_the_playback_language_and_is_saved_only_with_save() {
        playbackLanguage = "es"
        show()

        compose.onNodeWithText(DebridStreamLanguage.ES.label).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_season_audio_language_prefilled), substring = true).assertIsDisplayed()
        assertEquals(false, SeasonDownloadDefaultsRepository.hasSaved(profile))

        compose.onNodeWithText(text(Res.string.action_save)).performClick()

        assertEquals(DebridStreamLanguage.ES, SeasonDownloadDefaultsRepository.load(profile).audioLanguage)
    }

    @Test
    fun not_now_leaves_the_language_of_playback_unsaved() {
        playbackLanguage = "es"
        show()

        compose.onNodeWithText(text(Res.string.settings_preferences_setup_skip)).performClick()

        assertEquals(false, SeasonDownloadDefaultsRepository.hasSaved(profile))
    }

    @Test
    fun the_dialog_says_where_the_choices_can_be_changed_later() {
        show()

        val message = text(Res.string.settings_preferences_setup_message)
        compose.onNodeWithText(message).assertIsDisplayed()
        assertTrue("Settings" in message && "Quality and language preferences" in message)
        assertTrue("downloads" in message && "Play" in message)
    }

    @Test
    fun saving_stores_the_choices_and_the_play_mode_and_the_dialog_does_not_come_back() {
        show()

        compose.onNodeWithContentDescription(text(Res.string.downloads_season_quality_heading)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_season_quality_1080)).performClick()
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_audio_language)).performClick()
        compose.onNodeWithText(DebridStreamLanguage.EN.label).performClick()
        radio(Res.string.settings_preferences_play_best).performClick()
        compose.onNodeWithText(text(Res.string.action_save)).performClick()

        assertEquals(
            SeasonDownloadDefaults(BatchQuality.P1080, null, DebridStreamLanguage.EN),
            SeasonDownloadDefaultsRepository.load(profile),
        )
        assertEquals(listOf(StreamAutoPlayMode.PREFERRED), modeChanges)
        assertEquals(PreferenceSetupAnswer.Saved, PreferenceSetupRepository.answer(profile))
        assertDialogGone()
        assertNotShownAgain()
    }

    @Test
    fun saving_without_changes_stores_the_neutral_defaults_and_leaves_the_play_mode_alone() {
        show()

        compose.onNodeWithText(text(Res.string.action_save)).performClick()

        assertEquals(true, SeasonDownloadDefaultsRepository.hasSaved(profile))
        assertEquals(SeasonDownloadDefaults(), SeasonDownloadDefaultsRepository.load(profile))
        assertEquals(emptyList(), modeChanges)
        assertEquals(PreferenceSetupAnswer.Saved, PreferenceSetupRepository.answer(profile))
    }

    @Test
    fun not_now_saves_nothing_and_the_dialog_does_not_come_back() {
        show()

        compose.onNodeWithContentDescription(text(Res.string.downloads_season_quality_heading)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_season_quality_720)).performClick()
        radio(Res.string.settings_preferences_play_best).performClick()
        compose.onNodeWithText(text(Res.string.settings_preferences_setup_skip)).performClick()

        assertEquals(false, SeasonDownloadDefaultsRepository.hasSaved(profile))
        assertEquals(SeasonDownloadDefaults(), SeasonDownloadDefaultsRepository.load(profile))
        assertEquals(emptyList(), modeChanges)
        assertEquals(PreferenceSetupAnswer.Skipped, PreferenceSetupRepository.answer(profile))
        assertDialogGone()
        assertNotShownAgain()
    }

    @Test
    fun a_profile_with_saved_download_choices_is_not_interrupted_and_keeps_its_choices() {
        val existing = SeasonDownloadDefaults(BatchQuality.P720, maxBitrateMbps = 8)
        SeasonDownloadDefaultsRepository.save(profile, existing)

        show()
        compose.waitForIdle()

        assertDialogGone()
        assertEquals(PreferenceSetupAnswer.AlreadyConfigured, PreferenceSetupRepository.answer(profile))
        assertEquals(existing, SeasonDownloadDefaultsRepository.load(profile))
        assertNotShownAgain()
    }

    @Test
    fun the_dialog_waits_while_the_app_is_not_at_rest_and_then_appears() {
        canInterrupt = false
        show()

        assertDialogGone()
        assertEquals(PreferenceSetupAnswer.NotAsked, PreferenceSetupRepository.answer(profile))

        compose.runOnIdle { canInterrupt = true }
        compose.waitForIdle()

        compose.onNodeWithText(text(Res.string.settings_preferences_setup_title)).assertIsDisplayed()
    }

    @Test
    fun the_dialog_goes_away_without_an_answer_when_something_takes_over_the_screen() {
        show()
        compose.onNodeWithText(text(Res.string.settings_preferences_setup_title)).assertIsDisplayed()

        compose.runOnIdle { canInterrupt = false }
        compose.waitForIdle()

        assertDialogGone()
        assertEquals(PreferenceSetupAnswer.NotAsked, PreferenceSetupRepository.answer(profile))
        assertEquals(false, SeasonDownloadDefaultsRepository.hasSaved(profile))
    }

    @Test
    fun another_play_mode_is_shown_and_kept_unless_the_user_picks_an_option() {
        playMode = StreamAutoPlayMode.FIRST_STREAM
        show()

        radio(Res.string.settings_preferences_play_ask).assertIsNotSelected()
        radio(Res.string.settings_preferences_play_best).assertIsNotSelected()
        compose.onNodeWithText(text(Res.string.action_save)).performClick()

        assertEquals(emptyList(), modeChanges)
        assertEquals(PreferenceSetupAnswer.Saved, PreferenceSetupRepository.answer(profile))
    }

    @Test
    fun picking_an_option_over_another_play_mode_replaces_it_on_save() {
        playMode = StreamAutoPlayMode.REGEX_MATCH
        show()

        radio(Res.string.settings_preferences_play_ask).performClick()
        compose.onNodeWithText(text(Res.string.action_save)).performClick()

        assertEquals(listOf(StreamAutoPlayMode.MANUAL), modeChanges)
    }

    private var playbackLanguage = "default"

    private fun show() {
        compose.setContent {
            NuvioTheme {
                key(hostGeneration) {
                    PreferenceSetupHost(
                        profileId = profile,
                        canInterrupt = canInterrupt,
                        playMode = playMode,
                        onPlayModeChange = { modeChanges += it },
                        playbackAudioLanguage = playbackLanguage,
                    )
                }
            }
        }
    }

    private fun assertDialogGone() {
        compose.onAllNodesWithText(text(Res.string.settings_preferences_setup_title)).assertCountEquals(0)
    }

    /** After an answer the dialog stays away, also when the host is built again or the app comes to rest again. */
    private fun assertNotShownAgain() {
        compose.runOnIdle { canInterrupt = false }
        compose.runOnIdle { canInterrupt = true }
        compose.runOnIdle { hostGeneration++ }
        compose.waitForIdle()
        assertDialogGone()
    }

    private fun radio(label: StringResource) =
        compose.onNode(hasText(text(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

    private fun text(resource: StringResource): String = runBlocking { getString(resource) }
}

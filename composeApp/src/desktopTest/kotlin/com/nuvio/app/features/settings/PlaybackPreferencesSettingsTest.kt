package com.nuvio.app.features.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.downloads.AutoDownloadController
import com.nuvio.app.features.downloads.AutoDownloadRemoval
import com.nuvio.app.features.downloads.AutoDownloadRule
import com.nuvio.app.features.downloads.AutoDownloadScope
import com.nuvio.app.features.downloads.AutoDownloadStatus
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.SeasonDownloadChoices
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsRepository
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsStorage
import com.nuvio.app.features.streams.StreamAutoPlayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_preferred_audio_language
import nuvio.composeapp.generated.resources.downloads_season_audio_language_prefilled
import nuvio.composeapp.generated.resources.downloads_season_audio_language_description
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_none
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_value
import nuvio.composeapp.generated.resources.downloads_season_audio_language
import nuvio.composeapp.generated.resources.downloads_season_audio_language_any
import nuvio.composeapp.generated.resources.downloads_season_bitrate_no_limit
import nuvio.composeapp.generated.resources.downloads_season_bitrate_value
import nuvio.composeapp.generated.resources.downloads_season_max_bitrate
import nuvio.composeapp.generated.resources.downloads_season_quality_1080
import nuvio.composeapp.generated.resources.downloads_season_quality_720
import nuvio.composeapp.generated.resources.downloads_season_quality_any
import nuvio.composeapp.generated.resources.downloads_season_quality_heading
import nuvio.composeapp.generated.resources.settings_playback_stream_selection_mode_first_stream
import nuvio.composeapp.generated.resources.settings_playback_stream_selection_mode_regex
import nuvio.composeapp.generated.resources.settings_preferences_explainer
import nuvio.composeapp.generated.resources.settings_preferences_open_stream_auto_play
import nuvio.composeapp.generated.resources.settings_preferences_play_ask
import nuvio.composeapp.generated.resources.settings_preferences_play_best
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackPreferencesSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    // Profile ids no real profile uses, so stored preferences of real profiles are never touched.
    private val profile = 9_900_011
    private val otherProfile = 9_900_012

    private val saved = SeasonDownloadDefaults(BatchQuality.P1080, maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN)
    private var playMode by mutableStateOf(StreamAutoPlayMode.MANUAL)
    private val modeChanges = mutableListOf<StreamAutoPlayMode>()
    private var streamAutoPlayOpened = 0
    private val autoDownloads = FakeAutoDownloads()

    @Before
    @After
    fun clear_test_profiles() {
        SeasonDownloadDefaultsStorage.savePayload(profile, "")
        SeasonDownloadDefaultsStorage.savePayload(otherProfile, "")
    }

    @Test
    fun the_page_shows_the_stored_choices_of_the_profile() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        show()

        compose.onNodeWithText(text(Res.string.downloads_season_quality_1080)).assertIsDisplayed()
        compose.onNodeWithText(bitrate(10)).assertIsDisplayed()
        compose.onNodeWithText(DebridStreamLanguage.EN.label).assertIsDisplayed()
    }

    @Test
    fun the_page_shows_the_neutral_defaults_when_nothing_is_saved() {
        show()

        compose.onNodeWithText(text(Res.string.downloads_season_quality_any)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_season_bitrate_no_limit)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_season_audio_language_any)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.settings_preferences_play_ask)).assertIsSelected()
    }

    @Test
    fun the_two_audio_languages_have_distinct_names_and_the_download_one_says_how_they_differ() {
        show()

        val playback = text(Res.string.settings_playback_preferred_audio_language)
        val download = text(Res.string.downloads_season_audio_language)
        assertEquals("Playback audio language", playback)
        assertEquals("Download audio language", download)
        compose.onNodeWithContentDescription(download).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_season_audio_language_description)).assertIsDisplayed()
        assertTrue(text(Res.string.downloads_season_audio_language_description).contains(playback))
    }

    @Test
    fun a_profile_that_never_saved_choices_starts_with_the_language_of_playback_and_nothing_is_saved_by_looking() {
        playbackLanguage = "fr"
        show()

        compose.onNodeWithText(DebridStreamLanguage.FR.label).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_season_audio_language_prefilled), substring = true).assertIsDisplayed()
        assertEquals(false, SeasonDownloadDefaultsRepository.hasSaved(profile))
    }

    @Test
    fun the_device_language_stands_in_when_playback_follows_the_device() {
        playbackLanguage = "device"
        deviceLanguages = listOf("de-DE")
        show()

        compose.onNodeWithText(DebridStreamLanguage.DE.label).assertIsDisplayed()
    }

    @Test
    fun changing_any_choice_saves_the_whole_page_including_the_language_taken_from_playback() {
        playbackLanguage = "fr"
        show()

        openPicker(Res.string.downloads_season_quality_heading)
        compose.onNodeWithText(text(Res.string.downloads_season_quality_720)).performClick()

        assertEquals(SeasonDownloadDefaults(BatchQuality.P720, audioLanguage = DebridStreamLanguage.FR), SeasonDownloadDefaultsRepository.load(profile))
        compose.onNodeWithText(text(Res.string.downloads_season_audio_language_prefilled), substring = true).assertDoesNotExist()
    }

    @Test
    fun a_saved_choice_is_never_replaced_by_the_playback_language_not_even_a_saved_any_language() {
        playbackLanguage = "fr"
        SeasonDownloadDefaultsRepository.save(profile, SeasonDownloadDefaults(BatchQuality.P1080))
        show()

        compose.onNodeWithText(text(Res.string.downloads_season_audio_language_any)).assertIsDisplayed()
        compose.onNodeWithText(DebridStreamLanguage.FR.label).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_season_audio_language_prefilled), substring = true).assertDoesNotExist()
    }

    @Test
    fun a_playback_language_without_a_download_equivalent_leaves_any_language() {
        playbackLanguage = "original"
        show()

        compose.onNodeWithText(text(Res.string.downloads_season_audio_language_any)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_season_audio_language_prefilled), substring = true).assertDoesNotExist()
    }

    @Test
    fun the_page_says_that_the_choices_are_used_for_downloads_and_for_play() {
        show()

        val explainer = text(Res.string.settings_preferences_explainer)
        compose.onNodeWithText(explainer).assertIsDisplayed()
        assertTrue("downloads" in explainer && "Play" in explainer)
    }

    @Test
    fun changing_the_quality_saves_it_for_the_profile_and_the_season_dialog_starts_from_it() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        show()

        openPicker(Res.string.downloads_season_quality_heading)
        compose.onNodeWithText(text(Res.string.downloads_season_quality_720)).performClick()

        assertEquals(saved.copy(quality = BatchQuality.P720), SeasonDownloadDefaultsRepository.load(profile))
        compose.onNodeWithText(text(Res.string.downloads_season_quality_720)).assertIsDisplayed()
        assertEquals(BatchQuality.P720, SeasonDownloadChoices(profile).quality)
        assertEquals(SeasonDownloadDefaults(), SeasonDownloadDefaultsRepository.load(otherProfile))
    }

    @Test
    fun changing_the_maximum_bitrate_saves_it() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        show()

        openPicker(Res.string.downloads_season_max_bitrate)
        compose.onNodeWithText(bitrate(25)).performClick()

        assertEquals(saved.copy(maxBitrateMbps = 25), SeasonDownloadDefaultsRepository.load(profile))
        assertEquals(25, SeasonDownloadChoices(profile).maxBitrateMbps)
    }

    @Test
    fun changing_the_audio_language_saves_it() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        show()

        openPicker(Res.string.downloads_season_audio_language)
        compose.onNodeWithText(DebridStreamLanguage.FR.label).performClick()

        assertEquals(saved.copy(audioLanguage = DebridStreamLanguage.FR), SeasonDownloadDefaultsRepository.load(profile))
        assertEquals(DebridStreamLanguage.FR, SeasonDownloadChoices(profile).audioLanguage)
    }

    @Test
    fun a_choice_confirmed_in_the_season_dialog_is_what_the_page_shows() {
        SeasonDownloadChoices(profile).apply {
            quality = BatchQuality.P720
            maxBitrateMbps = 4
            confirm()
        }

        show()

        compose.onNodeWithText(text(Res.string.downloads_season_quality_720)).assertIsDisplayed()
        compose.onNodeWithText(bitrate(4)).assertIsDisplayed()
    }

    @Test
    fun the_page_follows_the_profile_it_is_shown_for() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        SeasonDownloadDefaultsRepository.save(otherProfile, SeasonDownloadDefaults(BatchQuality.P720))
        var shown by mutableStateOf(profile)
        compose.setContent {
            NuvioTheme {
                PlaybackPreferencesContent(
                    isTablet = false,
                    profileId = shown,
                    playMode = playMode,
                    onPlayModeChange = {},
                    onOpenStreamAutoPlay = {},
                    autoDownloads = autoDownloads,
                    showStorageLimit = false,
                )
            }
        }
        compose.onNodeWithText(text(Res.string.downloads_season_quality_1080)).assertIsDisplayed()

        compose.runOnIdle { shown = otherProfile }
        compose.waitForIdle()

        compose.onNodeWithText(text(Res.string.downloads_season_quality_720)).assertIsDisplayed()
    }

    @Test
    fun the_play_options_are_radio_buttons_and_follow_the_stored_mode() {
        playMode = StreamAutoPlayMode.PREFERRED
        show()

        radio(Res.string.settings_preferences_play_best).assertIsSelected()
        radio(Res.string.settings_preferences_play_ask).assertIsNotSelected()
    }

    @Test
    fun picking_an_option_sets_manual_or_preferred() {
        show()

        radio(Res.string.settings_preferences_play_best).performClick()
        assertEquals(listOf(StreamAutoPlayMode.PREFERRED), modeChanges)
        radio(Res.string.settings_preferences_play_best).assertIsSelected()

        radio(Res.string.settings_preferences_play_ask).performClick()
        assertEquals(listOf(StreamAutoPlayMode.PREFERRED, StreamAutoPlayMode.MANUAL), modeChanges)
        radio(Res.string.settings_preferences_play_ask).assertIsSelected()
    }

    @Test
    fun picking_the_option_that_is_already_selected_changes_nothing() {
        show()

        radio(Res.string.settings_preferences_play_ask).performClick()

        assertEquals(emptyList(), modeChanges)
    }

    @Test
    fun another_mode_is_shown_as_the_current_state_and_is_left_alone_until_an_option_is_picked() {
        playMode = StreamAutoPlayMode.FIRST_STREAM
        show()

        radio(Res.string.settings_preferences_play_ask).assertIsNotSelected()
        radio(Res.string.settings_preferences_play_best).assertIsNotSelected()
        compose.onNode(hasText(text(Res.string.settings_playback_stream_selection_mode_first_stream), substring = true)).assertIsDisplayed()
        assertEquals(emptyList(), modeChanges)

        compose.onNodeWithContentDescription(text(Res.string.settings_preferences_open_stream_auto_play)).performClick()
        assertEquals(1, streamAutoPlayOpened)
        assertEquals(emptyList(), modeChanges)

        radio(Res.string.settings_preferences_play_best).performClick()
        assertEquals(listOf(StreamAutoPlayMode.PREFERRED), modeChanges)
    }

    @Test
    fun a_regex_mode_is_named_and_left_alone_too() {
        playMode = StreamAutoPlayMode.REGEX_MATCH
        show()

        compose.onNode(hasText(text(Res.string.settings_playback_stream_selection_mode_regex), substring = true)).assertIsDisplayed()
        radio(Res.string.settings_preferences_play_ask).assertIsNotSelected()
        assertEquals(emptyList(), modeChanges)
    }

    @Test
    fun the_open_button_is_not_shown_for_the_two_modes_of_this_page() {
        show()

        compose.onAllNodesWithText(text(Res.string.settings_preferences_open_stream_auto_play)).assertCountEquals(0)
    }

    @Test
    fun the_storage_limit_is_set_through_the_automatic_download_controller() {
        show(showStorageLimit = true)

        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_value, 50)).assertIsDisplayed()
        openPicker(Res.string.downloads_auto_storage_limit)
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_value, 100)).performClick()

        assertEquals(listOf<Pair<Int, Int?>>(profile to 100), autoDownloads.limitChanges)
    }

    @Test
    fun the_storage_limit_can_be_turned_off() {
        show(showStorageLimit = true)

        openPicker(Res.string.downloads_auto_storage_limit)
        compose.onNode(hasText(text(Res.string.downloads_auto_storage_limit_none)) and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf<Pair<Int, Int?>>(profile to null), autoDownloads.limitChanges)
    }

    @Test
    fun the_storage_limit_is_left_out_when_downloads_are_not_available() {
        show(showStorageLimit = false)

        compose.onAllNodesWithText(text(Res.string.downloads_auto_storage_limit)).assertCountEquals(0)
    }

    @Test
    fun the_play_mapping_names_the_two_options_and_the_others() {
        assertEquals(PlayChoice.AskEveryTime, StreamAutoPlayMode.MANUAL.playChoice())
        assertEquals(PlayChoice.BestMatch, StreamAutoPlayMode.PREFERRED.playChoice())
        assertEquals(PlayChoice.SetElsewhere, StreamAutoPlayMode.FIRST_STREAM.playChoice())
        assertEquals(PlayChoice.SetElsewhere, StreamAutoPlayMode.REGEX_MATCH.playChoice())
    }

    private var playbackLanguage = "default"
    private var deviceLanguages = emptyList<String>()

    private fun show(showStorageLimit: Boolean = false) {
        compose.setContent {
            NuvioTheme {
                PlaybackPreferencesContent(
                    isTablet = false,
                    profileId = profile,
                    playbackAudioLanguage = playbackLanguage,
                    deviceLanguages = deviceLanguages,
                    playMode = playMode,
                    onPlayModeChange = {
                        modeChanges += it
                        playMode = it
                    },
                    onOpenStreamAutoPlay = { streamAutoPlayOpened++ },
                    autoDownloads = autoDownloads,
                    showStorageLimit = showStorageLimit,
                )
            }
        }
    }

    /** The row that opens the picker with this title. */
    private fun openPicker(title: StringResource) {
        compose.onNodeWithContentDescription(text(title)).performClick()
    }

    private fun radio(label: StringResource) =
        compose.onNode(hasText(text(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

    private class FakeAutoDownloads : AutoDownloadController {
        val limitChanges = mutableListOf<Pair<Int, Int?>>()
        override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
        override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
        override val storageLimitGb = MutableStateFlow<Int?>(50)
        override val removals = MutableStateFlow(emptyList<AutoDownloadRemoval>())
        override val usesSystemTrash = true

        override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope, deleteAfterWatching: Boolean) = Unit

        override fun keepDownload(profileId: Int, downloadId: String) = Unit

        override fun deleteDownloadNow(profileId: Int, downloadId: String) = Unit

        override fun turnOff(profileId: Int, ruleKey: String) = Unit

        override fun setStorageLimit(profileId: Int, limitGb: Int?) {
            limitChanges += profileId to limitGb
            storageLimitGb.value = limitGb
        }

        override fun checkNow() = Unit
    }

    private fun bitrate(megabitsPerSecond: Int): String = text(Res.string.downloads_season_bitrate_value, megabitsPerSecond)

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }
}

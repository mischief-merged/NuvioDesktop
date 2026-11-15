package com.nuvio.app.features.details

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.details.components.SeasonDownloadButton
import com.nuvio.app.features.downloads.AutoDownloadController
import com.nuvio.app.features.downloads.AutoDownloadRemoval
import com.nuvio.app.features.downloads.AutoDownloadRule
import com.nuvio.app.features.downloads.AutoDownloadScope
import com.nuvio.app.features.downloads.AutoDownloadStatus
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.SeasonBatchRequest
import com.nuvio.app.features.downloads.SeasonBatchStart
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsRepository
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.downloads_choices_just_this_time
import nuvio.composeapp.generated.resources.downloads_choices_saved_note
import nuvio.composeapp.generated.resources.downloads_season_audio_language_any
import nuvio.composeapp.generated.resources.downloads_season_bitrate_no_limit
import nuvio.composeapp.generated.resources.downloads_season_bitrate_value
import nuvio.composeapp.generated.resources.downloads_season_dialog_title
import nuvio.composeapp.generated.resources.downloads_season_max_bitrate
import nuvio.composeapp.generated.resources.downloads_season_max_bitrate_description
import nuvio.composeapp.generated.resources.downloads_season_quality_1080
import nuvio.composeapp.generated.resources.downloads_season_quality_720
import nuvio.composeapp.generated.resources.downloads_season_quality_any
import nuvio.composeapp.generated.resources.downloads_season_button
import nuvio.composeapp.generated.resources.downloads_season_start
import nuvio.composeapp.generated.resources.episodes_season
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeasonDownloadDialogTest {
    @get:Rule
    val compose = createComposeRule()

    // Profile ids no real profile uses, so stored preferences of real profiles are never touched.
    private val profile = 9_900_003
    private val otherProfile = 9_900_004
    private var shownProfile by mutableIntStateOf(profile)

    private val saved = SeasonDownloadDefaults(BatchQuality.P1080, maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN)
    private val requests = mutableListOf<SeasonBatchRequest>()

    private val show = MetaDetails(
        id = "dialog-test",
        type = "series",
        name = "Show",
        videos = listOf(
            MetaVideo(id = "dialog-test:1:1", title = "One", released = "2020-01-01T00:00:00Z", season = 1, episode = 1, runtime = 45),
            MetaVideo(id = "dialog-test:1:2", title = "Two", released = "2020-01-08T00:00:00Z", season = 1, episode = 2, runtime = 50),
        ),
    )

    @Before
    @After
    fun clear_test_profiles() {
        SeasonDownloadDefaultsStorage.savePayload(profile, "")
        SeasonDownloadDefaultsStorage.savePayload(otherProfile, "")
    }

    @Test
    fun the_dialog_opens_with_the_saved_choices() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        showButton()

        openDialog()

        compose.onNodeWithText(text(Res.string.downloads_season_quality_1080)).assertIsDisplayed()
        compose.onNodeWithText(bitrate(10)).assertIsDisplayed()
        compose.onNodeWithText(DebridStreamLanguage.EN.label).assertIsDisplayed()
    }

    @Test
    fun the_dialog_opens_with_neutral_choices_when_nothing_is_saved() {
        showButton()

        openDialog()

        compose.onNodeWithText(text(Res.string.downloads_season_quality_any)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_season_bitrate_no_limit)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_season_audio_language_any)).assertIsDisplayed()
    }

    @Test
    fun a_choice_changed_and_then_cancelled_is_not_saved_and_not_shown_when_the_dialog_reopens() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        showButton()
        openDialog()

        compose.onNodeWithText(text(Res.string.downloads_season_quality_1080)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_season_quality_720)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_season_quality_720)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.action_cancel)).performClick()

        assertEquals(saved, SeasonDownloadDefaultsRepository.load(profile))
        assertEquals(emptyList(), requests)
        openDialog()
        compose.onNodeWithText(text(Res.string.downloads_season_quality_1080)).assertIsDisplayed()
    }

    @Test
    fun confirming_saves_the_choices_and_starts_the_season_with_them() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        showButton()
        openDialog()

        compose.onNodeWithText(bitrate(10)).performClick()
        compose.onNodeWithText(bitrate(25)).performClick()
        confirmButton().performClick()

        assertEquals(saved.copy(maxBitrateMbps = 25), SeasonDownloadDefaultsRepository.load(profile))
        val request = requests.single()
        assertEquals(BatchQuality.P1080, request.quality)
        assertEquals(25, request.maxBitrateMbps)
        assertEquals(DebridStreamLanguage.EN, request.audioLanguage)
        assertEquals("dialog-test", request.parentMetaId)
        assertEquals(1, request.seasonNumber)
        assertEquals(listOf(1 to 45, 2 to 50), request.episodes.map { it.episodeNumber to it.runtimeMinutes })
    }

    @Test
    fun the_dialog_says_the_choices_are_saved_and_where_they_are_used() {
        showButton()
        openDialog()

        compose.onNodeWithText(text(Res.string.downloads_choices_saved_note)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_choices_just_this_time)).assertIsDisplayed()
        compose.onNode(hasContentDescription(text(Res.string.downloads_choices_just_this_time)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertIsNotSelected()
    }

    @Test
    fun the_bitrate_choice_names_its_unit_and_says_what_lower_means() {
        showButton()
        openDialog()

        val title = text(Res.string.downloads_season_max_bitrate)
        assertTrue(title.contains("megabits per second"))
        compose.onNodeWithText(text(Res.string.downloads_season_max_bitrate_description)).assertIsDisplayed()
        assertTrue(text(Res.string.downloads_season_max_bitrate_description).contains("smaller files"))
        assertTrue(bitrate(10).contains("megabits per second"))
    }

    @Test
    fun just_this_time_starts_the_season_with_the_choices_and_keeps_the_saved_ones() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        showButton()
        openDialog()

        compose.onNodeWithText(bitrate(10)).performClick()
        compose.onNodeWithText(bitrate(25)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_choices_just_this_time)).performClick()
        compose.onNode(hasContentDescription(text(Res.string.downloads_choices_just_this_time))).assertIsSelected()
        confirmButton().performClick()

        assertEquals(saved, SeasonDownloadDefaultsRepository.load(profile))
        assertEquals(25, requests.single().maxBitrateMbps)
    }

    @Test
    fun a_profile_switch_while_the_dialog_is_open_keeps_the_edits_with_the_profile_it_was_opened_for() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        val otherSaved = SeasonDownloadDefaults(BatchQuality.P480, maxBitrateMbps = 4)
        SeasonDownloadDefaultsRepository.save(otherProfile, otherSaved)
        showButton()
        openDialog()
        compose.onNodeWithText(text(Res.string.downloads_season_quality_1080)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_season_quality_720)).performClick()

        compose.runOnIdle { shownProfile = otherProfile }
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.downloads_season_quality_720)).assertIsDisplayed()
        confirmButton().performClick()

        assertEquals(saved.copy(quality = BatchQuality.P720), SeasonDownloadDefaultsRepository.load(profile))
        assertEquals(otherSaved, SeasonDownloadDefaultsRepository.load(otherProfile))
        assertEquals(BatchQuality.P720, requests.single().quality)
        assertEquals(10, requests.single().maxBitrateMbps)
    }

    private fun showButton() {
        compose.setContent {
            NuvioTheme {
                SeasonDownloadButton(
                    meta = show,
                    seasonNumber = 1,
                    profileId = shownProfile,
                    startBatch = { request ->
                        requests += request
                        SeasonBatchStart.Started
                    },
                    autoDownloads = NoAutoDownloads,
                )
            }
        }
    }

    private fun openDialog() {
        val season = text(Res.string.episodes_season, 1)
        compose.onNodeWithText(text(Res.string.downloads_season_start)).performClick()
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_dialog_title, season)).performClick()
    }

    /** The dialog's "Download season" button. The menu entry that opens the dialog has the same label. */
    private fun confirmButton() =
        compose.onNode(hasText(text(Res.string.downloads_season_button)) and hasAnyAncestor(isDialog()))

    private object NoAutoDownloads : AutoDownloadController {
        override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
        override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
        override val storageLimitGb = MutableStateFlow<Int?>(50)
        override val removals = MutableStateFlow(emptyList<AutoDownloadRemoval>())
        override var usesSystemTrash = true

        override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope, deleteAfterWatching: Boolean) = Unit

        override fun keepDownload(profileId: Int, downloadId: String) = Unit

        override fun deleteDownloadNow(profileId: Int, downloadId: String) = Unit

        override fun turnOff(profileId: Int, ruleKey: String) = Unit

        override fun setStorageLimit(profileId: Int, limitGb: Int?) = Unit

        override fun checkNow() = Unit
    }

    private fun bitrate(megabitsPerSecond: Int): String = text(Res.string.downloads_season_bitrate_value, megabitsPerSecond)

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }
}

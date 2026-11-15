package com.nuvio.app.features.details

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.details.components.DetailActionButtons
import com.nuvio.app.features.details.components.DetailActions
import com.nuvio.app.features.details.components.rememberMovieDownloadAction
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.MovieBatchSeasonNumber
import com.nuvio.app.features.downloads.SeasonBatchProgress
import com.nuvio.app.features.downloads.SeasonBatchRequest
import com.nuvio.app.features.downloads.SeasonBatchStart
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsRepository
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsStorage
import com.nuvio.app.features.downloads.isSingleMovie
import com.nuvio.app.features.downloads.seasonBatchKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.details_actions_menu_label
import nuvio.composeapp.generated.resources.downloads_movie_already_running
import nuvio.composeapp.generated.resources.downloads_movie_button
import nuvio.composeapp.generated.resources.downloads_movie_dialog_message
import nuvio.composeapp.generated.resources.downloads_movie_started
import nuvio.composeapp.generated.resources.downloads_movie_stop
import nuvio.composeapp.generated.resources.downloads_season_bitrate_value
import nuvio.composeapp.generated.resources.downloads_season_dialog_title
import nuvio.composeapp.generated.resources.downloads_season_max_bitrate
import nuvio.composeapp.generated.resources.downloads_season_quality_1080
import nuvio.composeapp.generated.resources.downloads_season_quality_heading
import nuvio.composeapp.generated.resources.downloads_season_start
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MovieDownloadActionTest {
    @get:Rule
    val compose = createComposeRule()

    // A profile id no real profile uses, so stored preferences of real profiles are never touched.
    private val profile = 9_900_010
    private val running = MutableStateFlow(emptyMap<String, SeasonBatchProgress>())
    private val requests = mutableListOf<SeasonBatchRequest>()
    private val stopped = mutableListOf<Pair<String, Int>>()
    private var startResult = SeasonBatchStart.Started

    private val movie = MetaDetails(id = "tt200", type = "movie", name = "Spirited Away", runtime = "125 min")
    private val saved = SeasonDownloadDefaults(BatchQuality.P1080, maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN)

    private val dialogTitle get() = text(Res.string.downloads_season_dialog_title, "Spirited Away")

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun downloadAction() = compose.onNodeWithContentDescription(text(Res.string.downloads_movie_button))

    private fun stopAction() = compose.onNodeWithContentDescription(text(Res.string.downloads_movie_stop))

    private fun confirmButton() = compose.onNode(hasText(text(Res.string.downloads_movie_button)) and hasAnyAncestor(isDialog()))

    @Before
    @After
    fun clear_the_test_profile() {
        SeasonDownloadDefaultsStorage.savePayload(profile, "")
        NuvioToastController.dismiss()
    }

    @Test
    fun the_action_is_a_named_button_that_opens_the_dialog_with_the_saved_choices() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        show(movie)

        downloadAction().assertIsDisplayed().assert(isButton()).assertHasClickAction().performClick()

        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_movie_dialog_message)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_quality_heading))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(Res.string.downloads_season_quality_1080)))
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_max_bitrate))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(Res.string.downloads_season_bitrate_value, 10)))
        assertEquals(emptyList(), requests)
    }

    @Test
    fun confirming_saves_the_choices_and_starts_a_download_of_this_one_movie_with_them() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        show(movie)
        downloadAction().performClick()

        compose.onNodeWithContentDescription(text(Res.string.downloads_season_max_bitrate)).performClick()
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_bitrate_value, 25)).performClick()
        confirmButton().performClick()

        val request = requests.single()
        assertEquals(true, request.isSingleMovie)
        assertEquals("tt200", request.episodes.single().movie?.metaId)
        assertEquals(125, request.episodes.single().runtimeMinutes)
        assertEquals(BatchQuality.P1080, request.quality)
        assertEquals(25, request.maxBitrateMbps)
        assertEquals(DebridStreamLanguage.EN, request.audioLanguage)
        assertEquals(saved.copy(maxBitrateMbps = 25), SeasonDownloadDefaultsRepository.load(profile))
        assertEquals(text(Res.string.downloads_movie_started), NuvioToastController.currentToast.value?.message)
        compose.onNodeWithText(dialogTitle).assertDoesNotExist()
    }

    @Test
    fun cancel_starts_nothing_and_saves_nothing() {
        SeasonDownloadDefaultsRepository.save(profile, saved)
        show(movie)
        downloadAction().performClick()

        compose.onNodeWithContentDescription(text(Res.string.downloads_season_max_bitrate)).performClick()
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_bitrate_value, 25)).performClick()
        compose.onNodeWithText(text(Res.string.action_cancel)).performClick()

        assertEquals(emptyList(), requests)
        assertEquals(saved, SeasonDownloadDefaultsRepository.load(profile))
    }

    @Test
    fun while_the_movie_is_being_added_the_button_reads_stop_and_one_click_stops() {
        running.value = mapOf(seasonBatchKey("tt200", MovieBatchSeasonNumber) to SeasonBatchProgress(total = 1, finished = 0))
        show(movie)

        downloadAction().assertDoesNotExist()
        stopAction().assertIsDisplayed().assert(isButton()).performClick()

        assertEquals(listOf("tt200" to MovieBatchSeasonNumber), stopped)
        compose.onNodeWithText(dialogTitle).assertDoesNotExist()
        compose.runOnIdle { running.value = emptyMap() }
        downloadAction().assertIsDisplayed()
    }

    @Test
    fun a_download_of_another_movie_does_not_change_the_button() {
        running.value = mapOf(seasonBatchKey("tt999", MovieBatchSeasonNumber) to SeasonBatchProgress(total = 1, finished = 0))
        show(movie)

        downloadAction().assertIsDisplayed()
    }

    @Test
    fun a_movie_that_is_already_being_added_says_so() {
        startResult = SeasonBatchStart.AlreadyRunning
        show(movie)
        downloadAction().performClick()

        confirmButton().performClick()

        assertEquals(text(Res.string.downloads_movie_already_running), NuvioToastController.currentToast.value?.message)
    }

    @Test
    fun the_button_and_the_dialog_are_operated_with_the_keyboard() {
        show(movie)

        downloadAction().requestFocus().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
        confirmButton().requestFocus().performKeyInput { pressKey(Key.Enter) }

        assertEquals(1, requests.size)
    }

    @Test
    fun a_series_has_no_movie_download_button() {
        show(movie.copy(type = "series"))

        downloadAction().assertDoesNotExist()
        stopAction().assertDoesNotExist()
    }

    @Test
    fun the_page_actions_show_the_download_action_without_opening_the_menu() {
        showPageActions(shuffleEnabled = false)

        downloadAction().assertIsDisplayed().performClick()

        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
    }

    @Test
    fun when_shuffle_holds_the_place_beside_play_the_download_action_is_in_the_menu() {
        showPageActions(shuffleEnabled = true)
        downloadAction().assertDoesNotExist()

        compose.onNodeWithContentDescription(text(Res.string.details_actions_menu_label)).performClick()

        downloadAction().assertIsDisplayed().performClick()
        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
    }

    private fun showPageActions(shuffleEnabled: Boolean) {
        compose.setContent {
            NuvioTheme {
                DetailActions(
                    playLabel = "Play",
                    playEnabled = true,
                    isSaved = false,
                    isWatched = false,
                    isTablet = false,
                    shuffleEnabled = shuffleEnabled,
                    onPlayClick = {},
                    onPlayLongClick = null,
                    onShuffleClick = {},
                    onWatchedClick = {},
                    onSaveClick = {},
                    onSaveLongClick = null,
                    downloadAction = rememberMovieDownloadAction(
                        meta = movie,
                        profileId = profile,
                        startBatch = { SeasonBatchStart.Started },
                        running = running,
                        cancelBatch = { _, _ -> },
                    ),
                )
            }
        }
    }

    private fun show(meta: MetaDetails) {
        compose.setContent {
            NuvioTheme {
                DetailActionButtons(
                    pinnedAction = rememberMovieDownloadAction(
                        meta = meta,
                        profileId = profile,
                        startBatch = { request ->
                            requests += request
                            startResult
                        },
                        running = running,
                        cancelBatch = { id, season -> stopped += id to season },
                    ),
                )
            }
        }
    }

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }
}

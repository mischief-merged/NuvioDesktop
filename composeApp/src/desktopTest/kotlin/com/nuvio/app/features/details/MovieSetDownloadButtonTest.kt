package com.nuvio.app.features.details

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.components.MovieSetDownloadButton
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsUiState
import com.nuvio.app.features.downloads.MovieBatchSeasonNumber
import com.nuvio.app.features.downloads.SeasonBatchProgress
import com.nuvio.app.features.downloads.SeasonBatchRequest
import com.nuvio.app.features.downloads.SeasonBatchStart
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsRepository
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsStorage
import com.nuvio.app.features.downloads.movieSetBatchId
import com.nuvio.app.features.downloads.seasonBatchKey
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_movies_button
import nuvio.composeapp.generated.resources.downloads_movies_clear
import nuvio.composeapp.generated.resources.downloads_movies_confirm
import nuvio.composeapp.generated.resources.downloads_movies_list_heading
import nuvio.composeapp.generated.resources.downloads_movies_select_all
import nuvio.composeapp.generated.resources.downloads_movies_started
import nuvio.composeapp.generated.resources.downloads_movies_state_in_downloads
import nuvio.composeapp.generated.resources.downloads_movies_state_unreleased
import nuvio.composeapp.generated.resources.downloads_season_dialog_title
import nuvio.composeapp.generated.resources.downloads_season_quality_1080
import nuvio.composeapp.generated.resources.downloads_season_quality_heading
import nuvio.composeapp.generated.resources.downloads_season_stop
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MovieSetDownloadButtonTest {
    @get:Rule
    val compose = createComposeRule()

    // A profile id no real profile uses, so stored preferences of real profiles are never touched.
    private val profile = 9_900_011
    private val setName = "Star Wars Collection"
    private val running = MutableStateFlow(emptyMap<String, SeasonBatchProgress>())
    private val downloads = MutableStateFlow(DownloadsUiState())
    private val requests = mutableListOf<SeasonBatchRequest>()
    private val stopped = mutableListOf<Pair<String, Int>>()

    private fun movie(number: Int) = MetaPreview(id = "tmdb:$number", type = "movie", name = "Episode $number", releaseInfo = "1977")

    private val movies = (1..5).map(::movie)

    private fun downloaded(number: Int) = DownloadItem(
        id = "d$number", contentType = "movie", parentMetaId = "tmdb:$number", parentMetaType = "movie", videoId = "tmdb:$number",
        title = "Episode $number", streamTitle = "s", providerName = "A", sourceUrl = "https://x/$number.mp4", fileName = "$number.mp4",
        status = DownloadStatus.Completed, downloadedBytes = 2_000_000_000, createdAtEpochMs = 0, updatedAtEpochMs = 0,
    )

    private val dialogTitle get() = text(Res.string.downloads_season_dialog_title, setName)

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun checkbox(name: String) =
        compose.onNode(hasContentDescription(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))

    private fun confirm(count: Int) = compose.onNodeWithText(runBlocking { getPluralString(Res.plurals.downloads_movies_confirm, count, count) })

    private fun openDialog() = compose.onNodeWithContentDescription(dialogTitle).performClick()

    @Before
    @After
    fun clear_the_test_profile() {
        SeasonDownloadDefaultsStorage.savePayload(profile, "")
        NuvioToastController.dismiss()
    }

    @Test
    fun the_button_is_a_named_button_that_opens_the_dialog_with_the_saved_choices_and_the_movies_ticked() {
        SeasonDownloadDefaultsRepository.save(profile, SeasonDownloadDefaults(BatchQuality.P1080))
        show()

        compose.onNodeWithText(text(Res.string.downloads_movies_button)).assertIsDisplayed()
        compose.onNodeWithContentDescription(dialogTitle).assert(isButton()).assertHasClickAction().performClick()

        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_quality_heading))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(Res.string.downloads_season_quality_1080)))
        compose.onNodeWithText(text(Res.string.downloads_movies_list_heading))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        movies.take(3).forEach { checkbox(it.name).assertIsOn().assertIsEnabled().assertHasClickAction() }
        confirm(5).assertIsEnabled()
    }

    @Test
    fun a_movie_already_in_downloads_is_shown_as_such_and_cannot_be_ticked() {
        downloads.value = DownloadsUiState(listOf(downloaded(2)))
        show()
        openDialog()

        checkbox("Episode 2").assertIsOff().assertIsNotEnabled()
        compose.onNode(hasContentDescription("Episode 2") and hasText(text(Res.string.downloads_movies_state_in_downloads))).assertExists()
        confirm(4).assertIsEnabled()
    }

    @Test
    fun unticking_a_movie_changes_the_count_and_leaves_it_out_of_the_download() {
        SeasonDownloadDefaultsRepository.save(profile, SeasonDownloadDefaults(BatchQuality.P1080, maxBitrateMbps = 10))
        show()
        openDialog()

        checkbox("Episode 3").performClick()
        checkbox("Episode 3").assertIsOff()
        confirm(4).performClick()

        val request = requests.single()
        assertEquals(movieSetBatchId(movies), request.parentMetaId)
        assertEquals(listOf("tmdb:1", "tmdb:2", "tmdb:4", "tmdb:5"), request.episodes.map { it.movie?.metaId })
        assertEquals(BatchQuality.P1080, request.quality)
        assertEquals(10, request.maxBitrateMbps)
        assertEquals(text(Res.string.downloads_movies_started), NuvioToastController.currentToast.value?.message)
        compose.onNodeWithText(dialogTitle).assertDoesNotExist()
    }

    @Test
    fun clear_leaves_nothing_to_download_and_select_all_ticks_every_movie_again() {
        show()
        openDialog()

        compose.onNodeWithText(text(Res.string.downloads_movies_clear)).assert(isButton()).assertHasClickAction().performClick()
        movies.take(3).forEach { checkbox(it.name).assertIsOff() }
        confirm(0).assertIsNotEnabled()

        compose.onNodeWithText(text(Res.string.downloads_movies_select_all)).assert(isButton()).assertHasClickAction().performClick()
        movies.take(3).forEach { checkbox(it.name).assertIsOn() }
        confirm(5).assertIsEnabled()
    }

    @Test
    fun a_movie_that_is_not_released_yet_is_listed_but_cannot_be_ticked() {
        show(movies.take(2) + MetaPreview(id = "tmdb:99", type = "movie", name = "Episode 99", rawReleaseDate = "2999-01-01"))
        openDialog()

        checkbox("Episode 99").assertIsOff().assertIsNotEnabled()
        compose.onNode(hasContentDescription("Episode 99") and hasText(text(Res.string.downloads_movies_state_unreleased))).assertExists()
        confirm(2).assertIsEnabled()
    }

    @Test
    fun one_ticked_movie_is_counted_in_the_singular() {
        show()
        openDialog()
        compose.onNodeWithText(text(Res.string.downloads_movies_clear)).performClick()

        checkbox("Episode 2").performClick()

        confirm(1).assertIsEnabled().performClick()
        assertEquals(listOf("tmdb:2"), requests.single().episodes.map { it.videoId })
    }

    @Test
    fun a_movie_is_unticked_and_the_download_confirmed_with_the_keyboard() {
        show()
        compose.onNodeWithContentDescription(dialogTitle).requestFocus().performKeyInput { pressKey(Key.Enter) }

        checkbox("Episode 1").requestFocus().performKeyInput { pressKey(Key.Enter) }
        checkbox("Episode 1").assertIsOff()
        confirm(4).requestFocus().performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("tmdb:2", "tmdb:3", "tmdb:4", "tmdb:5"), requests.single().episodes.map { it.videoId })
    }

    @Test
    fun a_long_set_scrolls_to_its_last_movie() {
        val longSet = (1..80).map(::movie)
        show(longSet)
        openDialog()

        compose.onNode(hasContentDescription("Episode 80")).assertDoesNotExist()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Episode 80"))
        checkbox("Episode 80").assertIsDisplayed().assertIsOn().performClick()
        confirm(79).performClick()

        assertEquals(79, requests.single().episodes.size)
        assertEquals("tmdb:79", requests.single().episodes.last().videoId)
    }

    @Test
    fun while_the_set_is_being_added_the_button_shows_the_progress_and_one_click_stops() {
        running.value = mapOf(seasonBatchKey(movieSetBatchId(movies), MovieBatchSeasonNumber) to SeasonBatchProgress(total = 5, finished = 2))
        show()
        val stopLabel = text(Res.string.downloads_season_stop, 2, 5)

        compose.onNodeWithText(text(Res.string.downloads_movies_button)).assertDoesNotExist()
        compose.onNodeWithText(stopLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription(stopLabel).assert(isButton()).performClick()

        assertEquals(listOf(movieSetBatchId(movies) to MovieBatchSeasonNumber), stopped)
        compose.onNodeWithText(dialogTitle).assertDoesNotExist()
    }

    @Test
    fun a_group_without_movies_has_no_button() {
        show(listOf(MetaPreview(id = "tt9", type = "series", name = "Show")))

        compose.onNodeWithText(text(Res.string.downloads_movies_button)).assertDoesNotExist()
    }

    private fun show(set: List<MetaPreview> = movies) {
        compose.setContent {
            NuvioTheme {
                MovieSetDownloadButton(
                    setName = setName,
                    movies = set,
                    profileId = profile,
                    startBatch = { request ->
                        requests += request
                        SeasonBatchStart.Started
                    },
                    running = running,
                    cancelBatch = { id, season -> stopped += id to season },
                    downloads = downloads,
                )
            }
        }
    }

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }
}

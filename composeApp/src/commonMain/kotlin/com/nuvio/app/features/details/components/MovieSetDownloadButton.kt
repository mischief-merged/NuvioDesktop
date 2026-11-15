package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadsClock
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsUiState
import com.nuvio.app.features.downloads.MovieBatchSeasonNumber
import com.nuvio.app.features.downloads.MovieSetEntry
import com.nuvio.app.features.downloads.MovieSetEntryState
import com.nuvio.app.features.downloads.MovieSetSelection
import com.nuvio.app.features.downloads.SeasonBatchDownloads
import com.nuvio.app.features.downloads.SeasonBatchProgress
import com.nuvio.app.features.downloads.SeasonBatchRequest
import com.nuvio.app.features.downloads.SeasonBatchStart
import com.nuvio.app.features.downloads.SeasonDownloadChoices
import com.nuvio.app.features.downloads.movieSetBatchId
import com.nuvio.app.features.downloads.movieSetBatchRequest
import com.nuvio.app.features.downloads.movieSetEntries
import com.nuvio.app.features.downloads.seasonBatchKey
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.isDesktop
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * "Download movies" for a set of movies the app already shows as a group, such as the collection a
 * movie belongs to. Desktop only.
 *
 * It opens the season download dialog with the movies of the set listed below the choices, each
 * with a checkbox. Movies that can be downloaded start ticked; ones already in downloads or not
 * released yet are listed but cannot be ticked. The ticked movies are queued like the episodes of a
 * season. While they are being added the button reads "Stop (n/total)" and one click on it stops.
 */
@Composable
internal fun MovieSetDownloadButton(
    setName: String,
    movies: List<MetaPreview>,
    modifier: Modifier = Modifier,
    profileId: Int = ProfileRepository.activeProfileId,
    startBatch: (SeasonBatchRequest) -> SeasonBatchStart = SeasonBatchDownloads::start,
    running: StateFlow<Map<String, SeasonBatchProgress>> = SeasonBatchDownloads.running,
    cancelBatch: (parentMetaId: String, seasonNumber: Int) -> Unit = SeasonBatchDownloads::cancel,
    downloads: StateFlow<DownloadsUiState> = DownloadsRepository.uiState,
) {
    if (!isDesktop || !AppFeaturePolicy.downloadsEnabled || setName.isBlank()) return
    if (movies.none { it.type == "movie" }) return

    val tokens = MaterialTheme.nuvio
    val batchId = movieSetBatchId(movies)
    val runningBatches by running.collectAsState()
    val progress = runningBatches[seasonBatchKey(batchId, MovieBatchSeasonNumber)]
    // The open dialog: the choices, which keep the profile they were opened for, and the ticks.
    var openDialog by remember(batchId) { mutableStateOf<Pair<SeasonDownloadChoices, MovieSetSelection>?>(null) }
    val buttonLabel = if (progress != null) {
        stringResource(Res.string.downloads_season_stop, progress.finished, progress.total)
    } else {
        stringResource(Res.string.downloads_movies_button)
    }
    val dialogTitle = stringResource(Res.string.downloads_season_dialog_title, setName)
    val startedText = stringResource(Res.string.downloads_movies_started)
    val alreadyRunningText = stringResource(Res.string.downloads_movies_already_running)

    TextButton(
        modifier = modifier.semantics { contentDescription = if (progress != null) buttonLabel else dialogTitle },
        onClick = {
            if (progress != null) {
                cancelBatch(batchId, MovieBatchSeasonNumber)
            } else {
                openDialog = SeasonDownloadChoices(profileId) to MovieSetSelection(
                    movieSetEntries(
                        movies = movies.filter { it.type == "movie" },
                        downloads = downloads.value.items,
                        todayIsoDate = CurrentDateProvider.todayIsoDate(),
                        nowEpochMs = DownloadsClock.nowEpochMs(),
                    ),
                )
            }
        },
        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (progress != null) Icons.Rounded.Close else Icons.Rounded.Download,
                contentDescription = null,
                modifier = Modifier.size(tokens.icons.sm),
            )
            Text(text = buttonLabel, style = MaterialTheme.typography.labelLarge)
        }
    }

    openDialog?.let { (choices, selection) ->
        val count = selection.selectedIds.size
        DownloadChoicesDialog(
            choices = choices,
            title = dialogTitle,
            message = stringResource(Res.string.downloads_movies_dialog_message),
            confirmLabel = pluralStringResource(Res.plurals.downloads_movies_confirm, count, count),
            confirmEnabled = count > 0,
            onDismiss = { openDialog = null },
            onConfirm = { confirmed ->
                openDialog = null
                when (startBatch(movieSetBatchRequest(batchId, setName, selection.selectedMovies, confirmed))) {
                    SeasonBatchStart.Started -> NuvioToastController.show(startedText)
                    SeasonBatchStart.AlreadyRunning -> NuvioToastController.show(alreadyRunningText)
                    SeasonBatchStart.NothingToDownload -> Unit
                }
            },
        ) {
            MovieSetSelectionList(selection)
        }
    }
}

private val MovieSetListMaxHeight: Dp = 260.dp

/** The movies of the set, each a checkbox named by its title, with Select all and Clear above. */
@Composable
private fun MovieSetSelectionList(selection: MovieSetSelection) {
    val tokens = MaterialTheme.nuvio
    NuvioSectionLabel(
        text = stringResource(Res.string.downloads_movies_list_heading),
        modifier = Modifier.padding(top = NuvioTokens.Space.s8).semantics { heading() },
    )
    DialogButtons(modifier = Modifier.padding(bottom = NuvioTokens.Space.s8)) {
        DialogButton(text = stringResource(Res.string.downloads_movies_select_all), onClick = selection::selectAll)
        DialogButton(text = stringResource(Res.string.downloads_movies_clear), onClick = selection::clear)
    }
    // Lazy, so a long set costs only the rows on screen.
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = MovieSetListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        items(selection.entries, key = { it.movie.id }) { entry ->
            MovieSetRow(
                entry = entry,
                checked = selection.isSelected(entry.movie),
                onToggle = { selection.toggle(entry.movie) },
            )
        }
    }
}

@Composable
private fun MovieSetRow(entry: MovieSetEntry, checked: Boolean, onToggle: () -> Unit) {
    val note = when (entry.state) {
        MovieSetEntryState.Available -> entry.movie.releaseInfo?.takeIf { it.isNotBlank() }
        MovieSetEntryState.InDownloads -> stringResource(Res.string.downloads_movies_state_in_downloads)
        MovieSetEntryState.Unreleased -> stringResource(Res.string.downloads_movies_state_unreleased)
    }
    DialogOption(
        text = entry.movie.name,
        description = note,
        selected = checked,
        enabled = entry.state == MovieSetEntryState.Available,
        role = Role.Checkbox,
        modifier = Modifier.semantics {
            contentDescription = entry.movie.name
            toggleableState = ToggleableState(checked)
        },
        onClick = onToggle,
    )
}

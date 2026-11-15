package com.nuvio.app.features.details.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.downloads.MovieBatchSeasonNumber
import com.nuvio.app.features.downloads.SeasonBatchDownloads
import com.nuvio.app.features.downloads.SeasonBatchProgress
import com.nuvio.app.features.downloads.SeasonBatchRequest
import com.nuvio.app.features.downloads.SeasonBatchStart
import com.nuvio.app.features.downloads.SeasonDownloadChoices
import com.nuvio.app.features.downloads.movieBatchRequest
import com.nuvio.app.features.downloads.seasonBatchKey
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.isDesktop
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The "Download movie" action of a movie's details page, shown with the page's other actions.
 * Desktop only; null elsewhere and for anything that is not a movie.
 *
 * One click opens the dialog a season download uses, starting from the saved choices. Confirming
 * saves them and looks for a source of the movie with them. While that is going on the action reads
 * "Stop download" and one click on it stops.
 */
@Composable
internal fun rememberMovieDownloadAction(
    meta: MetaDetails,
    profileId: Int = ProfileRepository.activeProfileId,
    startBatch: (SeasonBatchRequest) -> SeasonBatchStart = SeasonBatchDownloads::start,
    running: StateFlow<Map<String, SeasonBatchProgress>> = SeasonBatchDownloads.running,
    cancelBatch: (parentMetaId: String, seasonNumber: Int) -> Unit = SeasonBatchDownloads::cancel,
): DetailSecondaryAction? {
    if (!isDesktop || !AppFeaturePolicy.downloadsEnabled || meta.type != "movie") return null

    val runningBatches by running.collectAsState()
    val isRunning = seasonBatchKey(meta.id, MovieBatchSeasonNumber) in runningBatches
    // The open dialog. It keeps the profile it was opened for.
    var openChoices by remember(meta.id) { mutableStateOf<SeasonDownloadChoices?>(null) }
    val startedText = stringResource(Res.string.downloads_movie_started)
    val alreadyRunningText = stringResource(Res.string.downloads_movie_already_running)

    openChoices?.let { choices ->
        DownloadChoicesDialog(
            choices = choices,
            title = stringResource(Res.string.downloads_season_dialog_title, meta.name),
            message = stringResource(Res.string.downloads_movie_dialog_message),
            onDismiss = { openChoices = null },
            onConfirm = { confirmed ->
                openChoices = null
                when (startBatch(meta.movieBatchRequest(confirmed))) {
                    SeasonBatchStart.Started -> NuvioToastController.show(startedText)
                    SeasonBatchStart.AlreadyRunning -> NuvioToastController.show(alreadyRunningText)
                    SeasonBatchStart.NothingToDownload -> Unit
                }
            },
        )
    }

    return DetailSecondaryAction(
        label = stringResource(if (isRunning) Res.string.downloads_movie_stop else Res.string.downloads_movie_button),
        icon = if (isRunning) Icons.Rounded.Close else Icons.Rounded.Download,
        isActive = isRunning,
        onClick = {
            if (isRunning) {
                cancelBatch(meta.id, MovieBatchSeasonNumber)
            } else {
                openChoices = SeasonDownloadChoices(profileId)
            }
        },
    )
}

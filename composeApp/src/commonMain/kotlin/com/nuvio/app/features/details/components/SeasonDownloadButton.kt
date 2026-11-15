package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.SeasonBatchDownloads
import com.nuvio.app.features.downloads.SeasonBatchProgress
import com.nuvio.app.features.downloads.SeasonBatchRequest
import com.nuvio.app.features.downloads.SeasonBatchStart
import com.nuvio.app.features.downloads.seasonBatchEpisodes
import com.nuvio.app.features.downloads.seasonBatchKey
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.isDesktop
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * "Download season" action for the season header. Desktop only. Asks for a quality once, then adds
 * every released episode of the season to the normal downloads queue. While the season is being
 * added the button itself shows how far it is and one click on it stops.
 */
@Composable
internal fun SeasonDownloadButton(
    meta: MetaDetails,
    seasonNumber: Int,
    modifier: Modifier = Modifier,
    running: StateFlow<Map<String, SeasonBatchProgress>> = SeasonBatchDownloads.running,
    cancelBatch: (parentMetaId: String, seasonNumber: Int) -> Unit = SeasonBatchDownloads::cancel,
) {
    if (!isDesktop || !AppFeaturePolicy.downloadsEnabled || meta.type != "series") return

    val tokens = MaterialTheme.nuvio
    val runningBatches by running.collectAsState()
    val progress = runningBatches[seasonBatchKey(meta.id, seasonNumber)]
    var showDialog by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(BatchQuality.BestAvailable) }
    val seasonLabel = if (seasonNumber <= 0) {
        stringResource(Res.string.episodes_specials)
    } else {
        stringResource(Res.string.episodes_season, seasonNumber)
    }
    val dialogTitle = stringResource(Res.string.downloads_season_dialog_title, seasonLabel)
    val buttonLabel = if (progress != null) {
        stringResource(Res.string.downloads_season_stop, progress.finished, progress.total)
    } else {
        stringResource(Res.string.downloads_season_button)
    }
    val buttonDescription = if (progress != null) buttonLabel else dialogTitle
    val startedText = stringResource(Res.string.downloads_season_started)
    val nothingToDownloadText = stringResource(Res.string.downloads_season_nothing)
    val alreadyRunningText = stringResource(Res.string.downloads_season_already_running)

    TextButton(
        modifier = modifier.semantics { contentDescription = buttonDescription },
        onClick = {
            if (progress != null) {
                cancelBatch(meta.id, seasonNumber)
            } else {
                showDialog = true
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

    if (showDialog) {
        DialogSurface(
            onDismissRequest = { showDialog = false },
            title = dialogTitle,
            message = stringResource(Res.string.downloads_season_dialog_message),
        ) {
            NuvioSectionLabel(
                text = stringResource(Res.string.downloads_season_quality_heading),
                modifier = Modifier.semantics { heading() },
            )
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
            ) {
                BatchQuality.entries.forEach { option ->
                    val label = batchQualityLabel(option)
                    DialogOption(
                        text = label,
                        modifier = Modifier.semantics { contentDescription = label },
                        selected = option == quality,
                        onClick = { quality = option },
                    )
                }
            }
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_cancel),
                    onClick = { showDialog = false },
                )
                DialogButton(
                    text = stringResource(Res.string.downloads_season_start),
                    style = DialogButtonStyle.Primary,
                    onClick = {
                        showDialog = false
                        val result = SeasonBatchDownloads.start(
                            SeasonBatchRequest(
                                contentType = meta.type,
                                parentMetaId = meta.id,
                                parentMetaType = meta.type,
                                title = meta.name,
                                logo = meta.logo,
                                poster = meta.poster,
                                background = meta.background,
                                seasonNumber = seasonNumber,
                                episodes = meta.seasonBatchEpisodes(seasonNumber, CurrentDateProvider.todayIsoDate()),
                                quality = quality,
                            ),
                        )
                        NuvioToastController.show(
                            when (result) {
                                SeasonBatchStart.Started -> startedText
                                SeasonBatchStart.NothingToDownload -> nothingToDownloadText
                                SeasonBatchStart.AlreadyRunning -> alreadyRunningText
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun batchQualityLabel(quality: BatchQuality): String = when (quality) {
    BatchQuality.BestAvailable -> stringResource(Res.string.downloads_season_quality_any)
    BatchQuality.P2160 -> stringResource(Res.string.downloads_season_quality_2160)
    BatchQuality.P1080 -> stringResource(Res.string.downloads_season_quality_1080)
    BatchQuality.P720 -> stringResource(Res.string.downloads_season_quality_720)
    BatchQuality.P480 -> stringResource(Res.string.downloads_season_quality_480)
}

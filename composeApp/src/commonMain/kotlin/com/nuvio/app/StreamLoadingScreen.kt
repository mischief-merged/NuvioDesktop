package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.OpeningOverlay
import com.nuvio.app.features.player.subtitleLoadingStatusMessage
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.autoPlayChoiceMessage
import com.nuvio.app.features.streams.autoPlayChoiceOrNull
import com.nuvio.app.features.streams.StreamsUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.debrid_resolving_stream
import nuvio.composeapp.generated.resources.player_loading_preparing
import nuvio.composeapp.generated.resources.streams_finding_source
import nuvio.composeapp.generated.resources.streams_loading_subtitles
import nuvio.composeapp.generated.resources.youtube_resolving_stream
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun StreamLoadingScreen(
    launch: StreamLaunch,
    state: StreamsUiState,
    showStatus: Boolean,
    resolvingDebridStream: Boolean,
    resolvingYouTubeStream: Boolean = false,
    onBack: () -> Unit,
    preparingPlayback: Boolean = false,
) {
    val baseMessage = when {
        !showStatus -> null
        resolvingDebridStream -> stringResource(Res.string.debrid_resolving_stream)
        resolvingYouTubeStream -> stringResource(Res.string.youtube_resolving_stream)
        preparingPlayback -> stringResource(Res.string.player_loading_preparing)
        state.overlayMessage == stringResource(Res.string.streams_loading_subtitles) -> subtitleLoadingStatusMessage()
        state.overlayMessage != null -> state.overlayMessage
        state.autoPlayStream != null -> stringResource(Res.string.player_loading_preparing)
        else -> stringResource(Res.string.streams_finding_source)
    }
    // The source that was picked automatically is named under the status, so it is clear what is
    // about to play. It follows the status setting: no status, no naming.
    val chosen = if (showStatus) state.autoPlayChoiceOrNull()?.let { autoPlayChoiceMessage(it) } else null
    val message = if (baseMessage != null && chosen != null) "$baseMessage\n$chosen" else baseMessage ?: chosen
    OpeningOverlay(
        artwork = launch.background ?: launch.poster,
        logo = launch.logo,
        title = launch.title,
        onBack = onBack,
        horizontalSafePadding = 0.dp,
        message = message,
    )
}

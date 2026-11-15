package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedEpisodeTitle
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.isPlayableNow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episode_play_button_description
import org.jetbrains.compose.resources.stringResource

/**
 * A Play button that is always drawn on an episode thumbnail. It is its own focus stop with its own
 * spoken label, so the rest of the card can open the episode page. Not drawn for an episode that
 * cannot be played yet.
 */
@Composable
internal fun EpisodeThumbnailPlayButton(
    video: MetaVideo,
    onPlay: (MetaVideo) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!video.isPlayableNow()) return
    val title = localizedEpisodeTitle(video.title, video.episode) ?: video.title
    val label = stringResource(Res.string.episode_play_button_description, title)
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.62f))
            .border(width = 1.5.dp, color = Color.White.copy(alpha = 0.85f), shape = CircleShape)
            .clickable(role = Role.Button) { onPlay(video) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

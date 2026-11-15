package com.nuvio.app.features.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_auto_bitrate_skipped
import nuvio.composeapp.generated.resources.streams_auto_change_preferences
import nuvio.composeapp.generated.resources.streams_auto_chosen
import nuvio.composeapp.generated.resources.streams_auto_chosen_within_bitrate
import nuvio.composeapp.generated.resources.streams_auto_no_match
import nuvio.composeapp.generated.resources.streams_auto_no_match_plain
import nuvio.composeapp.generated.resources.streams_auto_preference_bitrate
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** "Chosen automatically: source • 1080p • English", for the loading screen. */
@Composable
internal fun autoPlayChoiceMessage(choice: AutoPlayChoice): String {
    val parts = buildList {
        choice.sourceName?.let(::add)
        choice.resolution?.let { add(it.label) }
        choice.language?.let { add(it.label) }
        choice.withinBitrateMbps?.let { add(stringResource(Res.string.streams_auto_chosen_within_bitrate, it)) }
    }
    return stringResource(Res.string.streams_auto_chosen, parts.joinToString(" • "))
}

/** The preferences that were applied, in words: "1080p, up to 10 megabits per second, English". */
@Composable
internal fun autoPlayPreferencesText(context: AutoPlayContext): String {
    val words = mutableListOf<String>()
    for (part in context.preferenceParts()) {
        words += when (part) {
            is AutoPlayPreferencePart.Quality -> part.resolution.label
            is AutoPlayPreferencePart.MaxBitrate ->
                stringResource(Res.string.streams_auto_preference_bitrate, part.megabitsPerSecond)
            is AutoPlayPreferencePart.Language -> part.language.label
        }
    }
    return words.joinToString(", ")
}

/**
 * The banner above the source list when best match found no source that fits: says how many sources
 * there were and which preferences they were checked against, and links to the preferences page. A
 * bitrate limit that could not be applied is said so, not named as a reason.
 */
@Composable
internal fun AutoPlayNoMatchBanner(
    context: AutoPlayContext,
    sourceCount: Int,
    onOpenPreferences: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val message = if (context.hasAppliedPreferences) {
        pluralStringResource(
            Res.plurals.streams_auto_no_match,
            sourceCount,
            sourceCount,
            autoPlayPreferencesText(context),
        )
    } else {
        stringResource(Res.string.streams_auto_no_match_plain)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (context.bitrateLimitSkipped) {
            Text(
                text = stringResource(Res.string.streams_auto_bitrate_skipped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onOpenPreferences != null) {
            val label = stringResource(Res.string.streams_auto_change_preferences)
            TextButton(
                modifier = Modifier.semantics { contentDescription = label },
                onClick = onOpenPreferences,
            ) {
                Text(text = label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

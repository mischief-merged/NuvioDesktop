package com.nuvio.app.features.updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_channel_private
import nuvio.composeapp.generated.resources.updates_channel_public
import nuvio.composeapp.generated.resources.updates_channel_short_private
import nuvio.composeapp.generated.resources.updates_channel_short_public
import nuvio.composeapp.generated.resources.updates_no_release_notes
import nuvio.composeapp.generated.resources.updates_release_notes_current_badge
import nuvio.composeapp.generated.resources.updates_release_notes_version_header
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun formatFeedChannelLabel(feedInfo: UpdateFeedInfo?): String = when (feedInfo?.kind) {
    UpdateFeedKind.PRIVATE_MANIFEST -> stringResource(Res.string.updates_channel_private, feedInfo.location)
    UpdateFeedKind.PUBLIC_GITHUB, null -> stringResource(Res.string.updates_channel_public)
}

@Composable
internal fun formatFeedChannelShortLabel(feedInfo: UpdateFeedInfo?): String = when (feedInfo?.kind) {
    UpdateFeedKind.PRIVATE_MANIFEST -> stringResource(Res.string.updates_channel_short_private)
    UpdateFeedKind.PUBLIC_GITHUB, null -> stringResource(Res.string.updates_channel_short_public)
}

@Composable
internal fun ReleaseNotesVersionList(
    entries: List<ReleaseNoteEntry>,
    currentVersionDisplay: String? = null,
    showSingleHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Text(
            text = stringResource(Res.string.updates_no_release_notes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            ReleaseNotesVersionBlock(
                entry = entry,
                isCurrentVersion = currentVersionDisplay != null && entry.versionDisplay == currentVersionDisplay,
                showHeader = showSingleHeader || entries.size > 1,
            )
        }
    }
}

@Composable
internal fun ReleaseNotesVersionBlock(
    entry: ReleaseNoteEntry,
    isCurrentVersion: Boolean = false,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val parsed = ReleaseNotesParser.parse(entry.notes)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.updates_release_notes_version_header, entry.versionDisplay),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (isCurrentVersion) {
                    Surface(
                        color = tokens.colors.accent.copy(alpha = tokens.opacity.pressed),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.updates_release_notes_current_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        if (parsed.sections.isEmpty()) {
            Text(
                text = stringResource(Res.string.updates_no_release_notes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            parsed.sections.forEach { section ->
                ReleaseNotesSectionBlock(section = section)
            }
        }
    }
}

@Composable
internal fun ReleaseNotesSectionBlock(
    section: ReleaseNoteSection,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (section.kind != ReleaseNoteSectionKind.OTHER || section.title != ReleaseNoteSectionKind.OTHER.title) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.labelLarge,
                color = if (section.kind == ReleaseNoteSectionKind.KNOWN_ISSUES) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    tokens.colors.accent
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
        section.items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

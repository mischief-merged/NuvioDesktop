package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.LocalReleaseNotesHistory
import com.nuvio.app.features.updater.ReleaseNoteEntry
import com.nuvio.app.features.updater.ReleaseNotesVersionBlock
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_no_release_notes
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.releaseNotesSettingsContent(
    isTablet: Boolean,
    entries: List<ReleaseNoteEntry> = LocalReleaseNotesHistory.entries,
    currentVersionDisplay: String = AppUpdaterPlatform.currentVersionName,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp

    if (entries.isEmpty()) {
        item(key = "release-notes-empty") {
            SettingsGroup(isTablet = isTablet) {
                Text(
                    text = stringResource(Res.string.updates_no_release_notes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
                )
            }
        }
    } else {
        entries.forEach { entry ->
            item(key = "release-notes-${entry.tag}") {
                SettingsGroup(isTablet = isTablet) {
                    ReleaseNotesVersionBlock(
                        entry = entry,
                        isCurrentVersion = entry.versionDisplay == currentVersionDisplay,
                        showHeader = true,
                        modifier = Modifier.padding(
                            horizontal = horizontalPadding,
                            vertical = verticalPadding,
                        ),
                    )
                }
            }
        }
    }
    settingsFooterContent(isTablet = isTablet)
}

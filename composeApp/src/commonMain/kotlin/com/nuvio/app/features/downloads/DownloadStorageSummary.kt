package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** A size said for a screen reader: "48.2 gigabytes". Same rounding as [formatBytes]. */
@Composable
internal fun spokenBytes(bytes: Long): String {
    val kib = 1024.0
    val value = bytes.toDouble()
    fun tenth(v: Double) = ((v * 10.0).toInt() / 10.0)
    return when {
        bytes <= 0L -> "0 " + stringResource(Res.string.downloads_unit_bytes)
        value >= kib * kib * kib -> "${tenth(value / (kib * kib * kib))} " + stringResource(Res.string.downloads_unit_gigabytes)
        value >= kib * kib -> "${tenth(value / (kib * kib))} " + stringResource(Res.string.downloads_unit_megabytes)
        value >= kib -> "${tenth(value / kib)} " + stringResource(Res.string.downloads_unit_kilobytes)
        else -> "$bytes " + stringResource(Res.string.downloads_unit_bytes)
    }
}

/**
 * The top of the Downloads screen: how much the downloads take on disk and how much room is left, as
 * one readable line; progress against the limit for automatic downloads when there is one; and, with
 * more than one location, a compact list of them that opens and closes. Locations are managed in
 * the setting, which [onManageLocations] opens.
 */
@Composable
internal fun DownloadStorageSummary(
    state: DownloadFolderState,
    autoLimitGb: Int?,
    onManageLocations: (() -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val used = state.totalUsedBytes
    val free = state.totalFreeBytes
    val headline = when {
        !state.usageMeasured -> stringResource(Res.string.downloads_storage_measuring)
        free != null -> stringResource(Res.string.downloads_storage_summary, formatBytes(used), formatBytes(free))
        else -> stringResource(Res.string.downloads_storage_summary_no_free, formatBytes(used))
    }
    val spoken = when {
        !state.usageMeasured -> headline
        free != null -> stringResource(Res.string.downloads_storage_summary_spoken, spokenBytes(used), spokenBytes(free))
        else -> stringResource(Res.string.downloads_storage_summary_spoken_no_free, spokenBytes(used))
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // One item for a screen reader, with the units spoken out.
            Text(
                text = headline,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
                style = MaterialTheme.typography.titleSmall,
            )
            if (autoLimitGb != null && autoLimitGb > 0) {
                val limitBytes = autoLimitGb * BytesPerGigabyte
                val autoUsed = state.autoDownloadUsedBytes
                val fraction = (autoUsed.toDouble() / limitBytes).toFloat().coerceIn(0f, 1f)
                val label = stringResource(Res.string.downloads_storage_auto, formatBytes(autoUsed), formatBytes(limitBytes))
                val spokenAuto = stringResource(Res.string.downloads_storage_auto_spoken, spokenBytes(autoUsed), spokenBytes(limitBytes))
                Text(
                    text = label,
                    modifier = Modifier.semantics { contentDescription = spokenAuto },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = spokenAuto
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    },
                )
            }
            if (state.inUse.size > 1) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) Res.string.downloads_storage_hide_locations else Res.string.downloads_storage_show_locations,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (expanded) {
                    state.inUse.forEach { location ->
                        val line = if (location.availability == FolderAvailability.Available) {
                            stringResource(
                                Res.string.downloads_storage_location_line,
                                location.name, formatBytes(location.usedBytes), location.freeBytes?.let(::formatBytes) ?: "?",
                            )
                        } else {
                            stringResource(Res.string.downloads_storage_location_line_away, location.name, formatBytes(location.usedBytes))
                        }
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onManageLocations != null) {
                        TextButton(onClick = onManageLocations) {
                            Text(stringResource(Res.string.downloads_storage_manage), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

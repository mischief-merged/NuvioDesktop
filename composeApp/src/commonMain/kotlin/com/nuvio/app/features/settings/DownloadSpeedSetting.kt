package com.nuvio.app.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.components.DownloadChoiceDialog
import com.nuvio.app.features.downloads.DownloadBandwidth
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** The speeds offered, in megabytes per second. 0 is no limit. */
internal val DownloadSpeedOptionsMbPerSecond = listOf(0, 1, 2, 5, 10, 25)

/**
 * Settings > Playback > Download speed limit: a row that says what it does and shows the current
 * limit, and a picker that works like the other download pickers: a choice is made and the picker
 * closes.
 */
@Composable
internal fun DownloadSpeedSetting(isTablet: Boolean) {
    val cap by remember {
        DownloadBandwidth.applyStoredLimit()
        DownloadBandwidth.userCapBytesPerSecond
    }.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    val title = stringResource(Res.string.settings_download_speed_limit_title)
    val selected = cap?.div(1_000_000L)?.toInt() ?: 0
    SettingsNavigationRow(
        title = title,
        description = stringResource(Res.string.settings_download_speed_limit_description),
        isTablet = isTablet,
        trailingContent = {
            Text(
                text = downloadSpeedLabel(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
        },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        DownloadChoiceDialog(
            title = title,
            options = DownloadSpeedOptionsMbPerSecond,
            selected = selected,
            label = { downloadSpeedLabel(it) },
            onSelect = { option ->
                DownloadBandwidth.setUserCap(option.takeIf { it > 0 }?.toLong()?.times(1_000_000L))
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
internal fun downloadSpeedLabel(megabytesPerSecond: Int): String =
    if (megabytesPerSecond == 0) stringResource(Res.string.settings_download_speed_limit_unlimited)
    else stringResource(Res.string.settings_download_speed_limit_value, megabytesPerSecond)

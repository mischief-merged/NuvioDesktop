package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadBandwidth
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

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
        description = downloadSpeedLabel(selected),
        isTablet = isTablet,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        // Every option is a radio button of one group and is named by its label, so assistive
        // technology reads the speed and whether it is the chosen one.
        DialogSurface(onDismissRequest = { showDialog = false }, title = title) {
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.controlGap),
            ) {
                listOf(0, 1, 2, 5, 10, 25).forEach { option ->
                    val label = downloadSpeedLabel(option)
                    DialogOption(
                        text = label,
                        modifier = Modifier.semantics { contentDescription = label },
                        selected = option == selected,
                        onClick = {
                            DownloadBandwidth.setUserCap(option.takeIf { it > 0 }?.toLong()?.times(1_000_000L))
                            showDialog = false
                        },
                    )
                }
            }
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_done),
                    onClick = { showDialog = false },
                )
            }
        }
    }
}

@Composable
private fun downloadSpeedLabel(megabytesPerSecond: Int): String =
    if (megabytesPerSecond == 0) stringResource(Res.string.settings_download_speed_limit_unlimited)
    else stringResource(Res.string.settings_download_speed_limit_value, megabytesPerSecond)

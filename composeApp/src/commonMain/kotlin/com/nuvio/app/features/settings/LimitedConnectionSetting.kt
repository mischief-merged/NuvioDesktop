package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.downloads.ConnectionHold
import com.nuvio.app.features.downloads.LimitedConnections
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Playback > Pause downloads on hotspots and Low Data Mode networks. On by default; while it
 * is on, downloads wait on such a network and carry on by themselves when it is left.
 */
@Composable
internal fun LimitedConnectionSetting(isTablet: Boolean, hold: ConnectionHold = LimitedConnections.hold) {
    val state by hold.state.collectAsStateWithLifecycle()
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_pause_on_limited_title),
        description = stringResource(Res.string.settings_pause_on_limited_description),
        checked = state.enabled,
        isTablet = isTablet,
        onCheckedChange = hold::setEnabled,
    )
}

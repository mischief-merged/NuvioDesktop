package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadFolderController
import com.nuvio.app.features.downloads.DownloadFolders
import com.nuvio.app.features.downloads.DownloadLocationInfo
import com.nuvio.app.features.downloads.DownloadMoveFlow
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.FolderAvailability
import com.nuvio.app.features.downloads.MoveRequest
import com.nuvio.app.features.downloads.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Playback > Download folder: where new downloads go and how much room is left there,
 * the actions to change it, and the list of every location in use with what is in each.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadFolderSetting(
    isTablet: Boolean,
    controller: DownloadFolderController = DownloadFolders.controller,
) {
    if (!controller.supported) return
    val folderState by controller.state.collectAsStateWithLifecycle()
    remember { DownloadsRepository.ensureLoaded(); controller.refresh() }
    val downloads by DownloadsRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf<MoveRequest?>(null) }
    val showFailed = stringResource(Res.string.settings_download_folder_show_failed)
    val busyText = stringResource(Res.string.settings_download_folder_busy)
    val current = folderState.defaultLocation
    val title = stringResource(Res.string.settings_download_folder_title)

    fun startChange(path: String) {
        if (folderState.moving) NuvioToastController.show(busyText) else request = MoveRequest.ChangeDefault(path)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (current != null) {
            val free = current.freeBytes?.let { stringResource(Res.string.settings_download_folder_free, formatBytes(it)) }
                ?: stringResource(Res.string.settings_download_folder_free_unknown)
            val state = availabilityText(current.availability)
            val summary = listOfNotNull(current.path, free, state).joinToString(", ")
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$title, $summary" },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(Res.string.settings_download_folder_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
                Text(current.path, style = MaterialTheme.typography.bodyMedium)
                Text(free, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textSecondary)
                if (state != null) {
                    Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        scope.launch {
                            val chosen = withContext(Dispatchers.Default) { controller.pickFolder() }
                            if (chosen != null) startChange(chosen)
                        }
                    },
                ) { Text(stringResource(Res.string.settings_download_folder_change)) }
                TextButton(
                    enabled = !current.isAppFolder,
                    onClick = { startChange(controller.appFolderPath()) },
                ) { Text(stringResource(Res.string.settings_download_folder_use_default)) }
                ShowInFileManagerButton(folderState.isMac, enabled = current.availability == FolderAvailability.Available) {
                    scope.launch {
                        val shown = withContext(Dispatchers.Default) { controller.showInFileManager(current.id) }
                        if (!shown) NuvioToastController.show(showFailed)
                    }
                }
            }
        }

        val inUse = folderState.inUse
        if (inUse.isNotEmpty()) {
            Text(
                stringResource(Res.string.settings_download_locations_title),
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            inUse.forEach { location ->
                LocationRow(
                    location = location,
                    isMac = folderState.isMac,
                    canMoveHere = inUse.any { it.id != location.id && it.itemCount > 0 } && location.availability == FolderAvailability.Available,
                    canMoveAway = location.itemCount > 0 && location.availability == FolderAvailability.Available,
                    onShow = {
                        scope.launch {
                            val shown = withContext(Dispatchers.Default) { controller.showInFileManager(location.id) }
                            if (!shown) NuvioToastController.show(showFailed)
                        }
                    },
                    onMoveHere = { if (folderState.moving) NuvioToastController.show(busyText) else request = MoveRequest.MoveAllTo(location.path) },
                    onMoveAway = {
                        if (folderState.moving) {
                            NuvioToastController.show(busyText)
                        } else {
                            request = MoveRequest.MoveItems(downloads.items.filter { controller.locationIdOf(it) == location.id }.map { it.id }.toSet())
                        }
                    },
                )
            }
        }
    }

    request?.let { active ->
        DownloadMoveFlow(
            request = active,
            controller = controller,
            items = downloads.items,
            onFinished = { request = null },
        )
    }
}

@Composable
private fun availabilityText(availability: FolderAvailability): String? = when (availability) {
    FolderAvailability.Available -> null
    FolderAvailability.NotConnected -> stringResource(Res.string.settings_download_folder_not_connected)
    FolderAvailability.NotFound -> stringResource(Res.string.settings_download_folder_not_found)
}

@Composable
private fun ShowInFileManagerButton(isMac: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            stringResource(
                if (isMac) Res.string.settings_download_folder_show_finder else Res.string.settings_download_folder_show_folder,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocationRow(
    location: DownloadLocationInfo,
    isMac: Boolean,
    canMoveHere: Boolean,
    canMoveAway: Boolean,
    onShow: () -> Unit,
    onMoveHere: () -> Unit,
    onMoveAway: () -> Unit,
) {
    val summary = pluralStringResource(Res.plurals.settings_download_location_summary, location.itemCount, location.itemCount, formatBytes(location.totalBytes))
    val free = location.freeBytes?.let { stringResource(Res.string.settings_download_location_free, formatBytes(it)) }
    val state = availabilityText(location.availability)
    val defaultTag = if (location.isDefault) stringResource(Res.string.settings_download_location_default_tag) else null
    val description = listOfNotNull(location.name, location.path, summary, free, state, defaultTag).joinToString(", ")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
            Text(location.name, style = MaterialTheme.typography.titleSmall)
            Text(location.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textSecondary)
            Text(
                listOfNotNull(summary, free).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
            if (defaultTag != null) Text(defaultTag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textSecondary)
            if (state != null) Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val showLabel = stringResource(Res.string.settings_download_location_show_description, location.name)
            TextButton(
                onClick = onShow,
                enabled = location.availability == FolderAvailability.Available,
                modifier = Modifier.semantics { contentDescription = showLabel },
            ) {
                Text(
                    stringResource(
                        if (isMac) Res.string.settings_download_folder_show_finder else Res.string.settings_download_folder_show_folder,
                    ),
                )
            }
            val hereLabel = stringResource(Res.string.settings_download_location_move_here_description, location.name)
            TextButton(onClick = onMoveHere, enabled = canMoveHere, modifier = Modifier.semantics { contentDescription = hereLabel }) {
                Text(stringResource(Res.string.settings_download_location_move_here))
            }
            val awayLabel = stringResource(Res.string.settings_download_location_move_away_description, location.name)
            TextButton(onClick = onMoveAway, enabled = canMoveAway, modifier = Modifier.semantics { contentDescription = awayLabel }) {
                Text(stringResource(Res.string.settings_download_location_move_away))
            }
        }
    }
}

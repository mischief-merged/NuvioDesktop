package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.components.DownloadChoiceDialog
import com.nuvio.app.features.details.components.SeasonDownloadChoiceRow
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.SettingsChoiceDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The shows with an automatic-download rule, each with what its last check found, and a button
 * that checks them all now. Shows nothing when there is no rule.
 */
@Composable
internal fun AutoDownloadSection(
    modifier: Modifier = Modifier,
    controller: AutoDownloadController = AutoDownloads,
    profileId: Int = ProfileRepository.activeProfileId,
    downloads: StateFlow<DownloadsUiState> = DownloadsRepository.uiState,
    nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
) {
    val rules by controller.rules.collectAsState()
    val statuses by controller.statuses.collectAsState()
    val storageLimitGb by controller.storageLimitGb.collectAsState()
    val removals by controller.removals.collectAsState()
    val recentlyDeleted by controller.recentlyDeleted.collectAsState()
    val downloadsState by downloads.collectAsState()
    if (rules.isEmpty() && recentlyDeletedShown(recentlyDeleted, nowEpochMs()).isEmpty()) return
    val tokens = MaterialTheme.nuvio
    val checkingText = stringResource(Res.string.downloads_auto_checking_toast)
    val checkNowText = stringResource(Res.string.downloads_auto_check_now)
    // The profile the storage limit picker was opened for, while it is open.
    var storageLimitPickerProfileId by remember { mutableStateOf<Int?>(null) }
    // The episode "Delete now" asks about, with the profile it was pressed for, until it is answered.
    var deletionToConfirm by remember { mutableStateOf<Pair<Int, DownloadItem>?>(null) }
    val pending = removals.mapNotNull { removal ->
        downloadsState.items.firstOrNull { it.id == removal.downloadId }?.let { removal to it }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s8),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioSectionLabel(
                text = stringResource(Res.string.downloads_auto_section_title),
                modifier = Modifier.semantics { heading() },
            )
            TextButton(
                modifier = Modifier.semantics { contentDescription = checkNowText },
                onClick = {
                    controller.checkNow()
                    NuvioToastController.show(checkingText)
                },
            ) {
                Text(text = checkNowText, style = MaterialTheme.typography.labelLarge)
            }
        }
        SeasonDownloadChoiceRow(
            title = stringResource(Res.string.downloads_auto_storage_limit),
            description = stringResource(Res.string.downloads_auto_storage_limit_description),
            value = autoDownloadStorageLimitLabel(storageLimitGb),
            onClick = { storageLimitPickerProfileId = profileId },
        )
        rules.forEach { rule ->
            Column(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
            ) {
                Text(
                    text = stringResource(
                        Res.string.downloads_auto_rule_summary,
                        rule.title,
                        pluralStringResource(Res.plurals.downloads_auto_keep_option, rule.keepCount, rule.keepCount),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    text = autoDownloadStatusLabel(statuses[rule.key] ?: AutoDownloadStatus.NotChecked),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        // Read again every minute, so the hours left stay right while the screen is open.
        val now by produceState(initialValue = nowEpochMs()) {
            while (true) {
                delay(MillisPerMinute)
                value = nowEpochMs()
            }
        }
        if (pending.isNotEmpty()) {
            NuvioSectionLabel(
                text = stringResource(Res.string.downloads_auto_pending_heading),
                modifier = Modifier
                    .padding(top = NuvioTokens.Space.s8)
                    .semantics { heading() },
            )
            pending.forEach { (removal, item) ->
                val season = item.seasonNumber ?: 0
                val episode = item.episodeNumber ?: 0
                val hoursLeft = removalHoursLeft(removal.dueAtEpochMs, now)
                val due = if (hoursLeft < 1) {
                    stringResource(Res.string.downloads_auto_pending_soon)
                } else {
                    pluralStringResource(Res.plurals.downloads_auto_pending_in_hours, hoursLeft, hoursLeft)
                }
                val keepDescription = stringResource(Res.string.downloads_auto_keep_description, item.title, season, episode)
                val deleteDescription = stringResource(Res.string.downloads_auto_delete_now_description, item.title, season, episode)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.downloads_auto_pending_row, item.title, season, episode, due),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                    )
                    TextButton(
                        modifier = Modifier.semantics { contentDescription = keepDescription },
                        onClick = { controller.keepDownload(profileId, item.id) },
                    ) {
                        Text(text = stringResource(Res.string.downloads_auto_keep), style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(
                        modifier = Modifier.semantics { contentDescription = deleteDescription },
                        onClick = { deletionToConfirm = profileId to item },
                    ) {
                        Text(text = stringResource(Res.string.downloads_auto_delete_now), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        val recent = recentlyDeletedShown(recentlyDeleted, now)
        if (recent.isNotEmpty()) {
            NuvioSectionLabel(
                text = stringResource(Res.string.downloads_auto_recent_heading),
                modifier = Modifier
                    .padding(top = NuvioTokens.Space.s8)
                    .semantics { heading() },
            )
            recent.forEach { entry ->
                val days = daysSinceDeleted(entry, now)
                val ago = if (days < 1) {
                    stringResource(Res.string.downloads_auto_recent_today)
                } else {
                    pluralStringResource(Res.plurals.downloads_auto_recent_days_ago, days, days)
                }
                Text(
                    text = stringResource(
                        Res.string.downloads_auto_recent_row,
                        entry.title,
                        entry.seasonNumber ?: 0,
                        entry.episodeNumber ?: 0,
                        ago,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                )
            }
        }
    }

    deletionToConfirm?.let { (confirmProfileId, item) ->
        NuvioStatusModal(
            title = stringResource(Res.string.downloads_auto_delete_now_title),
            message = stringResource(
                if (controller.usesSystemTrash) {
                    Res.string.downloads_auto_delete_now_message_trash
                } else {
                    Res.string.downloads_auto_delete_now_message_held
                },
                item.title,
                item.seasonNumber ?: 0,
                item.episodeNumber ?: 0,
            ),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.downloads_auto_delete_now),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                controller.deleteDownloadNow(confirmProfileId, item.id)
                deletionToConfirm = null
            },
            onDismiss = { deletionToConfirm = null },
        )
    }

    storageLimitPickerProfileId?.let { pickerProfileId ->
        DownloadChoiceDialog(
            title = stringResource(Res.string.downloads_auto_storage_limit),
            options = AutoDownloadStore.storageLimitOptionsGb,
            selected = storageLimitGb,
            label = { autoDownloadStorageLimitLabel(it) },
            onSelect = {
                controller.setStorageLimit(pickerProfileId, it)
                storageLimitPickerProfileId = null
            },
            onDismiss = { storageLimitPickerProfileId = null },
        )
    }
}

private const val MillisPerMinute = 60_000L

@Composable
internal fun autoDownloadStorageLimitLabel(limitGb: Int?): String =
    if (limitGb == null) {
        stringResource(Res.string.downloads_auto_storage_limit_none)
    } else {
        stringResource(Res.string.downloads_auto_storage_limit_value, limitGb)
    }

@Composable
internal fun autoDownloadStatusLabel(status: AutoDownloadStatus): String = stringResource(
    when (status) {
        AutoDownloadStatus.NotChecked -> Res.string.downloads_auto_status_not_checked
        AutoDownloadStatus.Checking -> Res.string.downloads_auto_status_checking
        AutoDownloadStatus.UpToDate -> Res.string.downloads_auto_status_up_to_date
        AutoDownloadStatus.Pending -> Res.string.downloads_auto_status_pending
        AutoDownloadStatus.Downloading -> Res.string.downloads_auto_status_downloading
        AutoDownloadStatus.Paused -> Res.string.downloads_auto_status_paused
        AutoDownloadStatus.CouldNotCheck -> Res.string.downloads_auto_status_could_not_check
        AutoDownloadStatus.NoSource -> Res.string.downloads_auto_status_no_source
        AutoDownloadStatus.PrivateSourcesSkipped -> Res.string.downloads_auto_status_private_sources
        AutoDownloadStatus.Failed -> Res.string.downloads_auto_status_failed
        AutoDownloadStatus.Stopped -> Res.string.downloads_auto_status_stopped
        AutoDownloadStatus.StorageLimitReached -> Res.string.downloads_auto_status_storage_limit
        AutoDownloadStatus.LowDiskSpace -> Res.string.downloads_auto_status_low_disk_space
    },
)

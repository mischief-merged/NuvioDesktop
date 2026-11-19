package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.LocalAppUpdaterController
import com.nuvio.app.features.updater.UpdateFeedInfo
import com.nuvio.app.features.updater.UpdateFeedKind
import com.nuvio.app.features.updater.UpdateFeedSelector
import com.nuvio.app.features.updater.UpdatePreferences
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.settingsRootContent(
    isTablet: Boolean,
    onPageClick: (SettingsPage) -> Unit,
    onSwitchProfileClick: (() -> Unit)? = null,
    onWatchPartyClick: (() -> Unit)? = null,
    showNotificationsEntry: Boolean = true,
    category: SettingsCategory? = null,
    onCheckForUpdatesClick: (() -> Unit)? = null,
) {
    SettingsCategory.entries.filter { category == null || it == category }.forEach { group ->
        item(key = group.name) {
            SettingsGroup(isTablet = isTablet) {
                when (group) {
                    SettingsCategory.You -> {
                        SettingsPageRow(
                            page = SettingsPage.Account,
                            description = Res.string.compose_settings_root_account_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                        if (onSwitchProfileClick != null) {
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsNavigationRow(
                                title = stringResource(Res.string.compose_settings_root_switch_profile_title),
                                description = stringResource(Res.string.compose_settings_root_switch_profile_description),
                                icon = Icons.Default.People,
                                isTablet = isTablet,
                                onClick = onSwitchProfileClick,
                            )
                        }
                        if (onWatchPartyClick != null) {
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsNavigationRow(
                                title = stringResource(Res.string.party_title),
                                description = stringResource(Res.string.party_settings_description),
                                icon = Icons.Rounded.Groups,
                                isTablet = isTablet,
                                onClick = onWatchPartyClick,
                            )
                        }
                    }
                    SettingsCategory.Look -> {
                        SettingsPageRow(
                            page = SettingsPage.Appearance,
                            description = Res.string.settings_appearance_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsPageRow(
                            page = SettingsPage.Layout,
                            description = Res.string.compose_settings_root_layout_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                    }
                    SettingsCategory.Watch -> {
                        SettingsPageRow(
                            page = SettingsPage.ContentDiscovery,
                            description = Res.string.compose_settings_root_content_discovery_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsPageRow(
                            page = SettingsPage.Playback,
                            description = Res.string.settings_playback_subtitle,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                    }
                    SettingsCategory.Services -> {
                        SettingsPageRow(
                            page = SettingsPage.Integrations,
                            description = Res.string.compose_settings_root_integrations_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsPageRow(
                            page = SettingsPage.TraktAuthentication,
                            description = Res.string.compose_settings_root_tracking_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                    }
                    SettingsCategory.System -> {
                        if (showNotificationsEntry) {
                            SettingsPageRow(
                                page = SettingsPage.Notifications,
                                description = Res.string.compose_settings_root_notifications_description,
                                isTablet = isTablet,
                                onPageClick = onPageClick,
                            )
                            SettingsGroupDivider(isTablet = isTablet)
                        }
                        SettingsPageRow(
                            page = SettingsPage.Advanced,
                            description = Res.string.compose_settings_root_advanced_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsPageRow(
                            page = SettingsPage.About,
                            description = Res.string.compose_settings_root_about_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                    }
                }
            }
            if (
                group == SettingsCategory.System &&
                AppUpdaterPlatform.usesForkUpdateFeed &&
                onCheckForUpdatesClick != null
            ) {
                Spacer(modifier = Modifier.height(if (isTablet) 18.dp else 14.dp))
                SystemUpdatesSection(
                    isTablet = isTablet,
                    onCheckForUpdatesClick = onCheckForUpdatesClick,
                    onReleaseNotesClick = { onPageClick(SettingsPage.ReleaseNotes) },
                )
            } else if (
                group == SettingsCategory.System &&
                AppUpdaterPlatform.usesForkUpdateFeed
            ) {
                Spacer(modifier = Modifier.height(if (isTablet) 18.dp else 14.dp))
                SettingsSection(
                    title = stringResource(Res.string.updates_section_title),
                    isTablet = isTablet,
                ) {
                    SettingsGroup(isTablet = isTablet) {
                        SettingsPageRow(
                            page = SettingsPage.ReleaseNotes,
                            description = Res.string.updates_release_notes_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                    }
                }
            }
        }
    }
    settingsFooterContent(isTablet = isTablet)
}

@Composable
private fun SystemUpdatesSection(
    isTablet: Boolean,
    onCheckForUpdatesClick: () -> Unit,
    onReleaseNotesClick: () -> Unit,
) {
    val controller = LocalAppUpdaterController.current
    val uiState = controller?.uiState?.collectAsStateWithLifecycle()?.value
    val prefAutoCheck by UpdatePreferences.shared.autoDailyCheckEnabled.collectAsStateWithLifecycle()
    val prefLastCheck by UpdatePreferences.shared.lastCheckEpochMs.collectAsStateWithLifecycle()

    val isChecking = uiState?.isChecking ?: false
    val availableUpdate = uiState?.update?.takeIf { uiState.isUpdateAvailable }
    val lastCheckEpochMs = uiState?.lastCheckEpochMs ?: prefLastCheck
    val autoDailyCheckEnabled = uiState?.autoDailyCheckEnabled ?: prefAutoCheck
    val currentVersion = uiState?.currentVersionDisplay ?: AppUpdaterPlatform.currentVersionName
    val currentBuild = uiState?.currentBuildNumber ?: AppUpdaterPlatform.currentBuildNumber
    val feedInfo = uiState?.feedInfo ?: remember { UpdateFeedSelector.currentFeed().info }

    val channelLabel = updateFeedChannelLabel(feedInfo)
    val lastCheckedLabel = formatLastCheckedLabel(lastCheckEpochMs)
    val description = stringResource(
        Res.string.updates_settings_summary,
        currentVersion,
        currentBuild,
        channelLabel,
        lastCheckedLabel,
    )
    val tokens = MaterialTheme.nuvio
    val statusText = when {
        isChecking -> stringResource(Res.string.updates_settings_checking)
        availableUpdate != null -> stringResource(
            Res.string.updates_settings_update_available,
            availableUpdate.versionDisplay,
        )
        lastCheckEpochMs != null -> stringResource(Res.string.updates_settings_up_to_date)
        else -> null
    }

    SettingsSection(
        title = stringResource(Res.string.updates_section_title),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.compose_settings_root_check_updates_title),
                description = description,
                icon = SettingsSearchTarget.CheckForUpdates.icon,
                isTablet = isTablet,
                trailingContent = statusText?.let { label ->
                    {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (availableUpdate != null) {
                                tokens.colors.accent
                            } else {
                                tokens.colors.textSecondary
                            },
                        )
                    }
                },
                onClick = onCheckForUpdatesClick,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.updates_auto_daily_check_title),
                description = stringResource(Res.string.updates_auto_daily_check_description),
                checked = autoDailyCheckEnabled,
                isTablet = isTablet,
                onCheckedChange = { enabled ->
                    if (controller != null) {
                        controller.setAutoDailyCheckEnabled(enabled)
                    } else {
                        UpdatePreferences.shared.setAutoDailyCheckEnabled(enabled)
                    }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.updates_release_notes),
                description = stringResource(Res.string.updates_release_notes_description),
                icon = SettingsPage.ReleaseNotes.icon,
                isTablet = isTablet,
                onClick = onReleaseNotesClick,
            )
        }
    }
}

@Composable
private fun updateFeedChannelLabel(feedInfo: UpdateFeedInfo): String = when (feedInfo.kind) {
    UpdateFeedKind.PUBLIC_GITHUB -> stringResource(Res.string.updates_channel_public)
    UpdateFeedKind.PRIVATE_MANIFEST -> stringResource(Res.string.updates_channel_private, feedInfo.location)
}

@Composable
private fun formatLastCheckedLabel(lastCheckEpochMs: Long?): String {
    if (lastCheckEpochMs == null) {
        return stringResource(Res.string.updates_last_checked_never)
    }
    val ageMs = AppUpdaterPlatform.nowEpochMs() - lastCheckEpochMs
    if (ageMs in 0L until 60_000L) {
        return stringResource(Res.string.updates_last_checked_just_now)
    }
    return stringResource(Res.string.updates_last_checked_at, formatLocalDateTime(lastCheckEpochMs))
}

@Composable
private fun SettingsPageRow(
    page: SettingsPage,
    description: StringResource,
    isTablet: Boolean,
    onPageClick: (SettingsPage) -> Unit,
) {
    SettingsNavigationRow(
        title = stringResource(page.titleRes),
        description = stringResource(description),
        icon = page.icon,
        isTablet = isTablet,
        onClick = { onPageClick(page) },
    )
}

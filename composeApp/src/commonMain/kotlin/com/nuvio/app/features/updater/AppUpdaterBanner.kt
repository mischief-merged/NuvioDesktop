package com.nuvio.app.features.updater

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.appTheme
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.themePalette
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_continue
import nuvio.composeapp.generated.resources.action_install
import nuvio.composeapp.generated.resources.action_later
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.action_update
import nuvio.composeapp.generated.resources.updates_action_restart_to_update
import nuvio.composeapp.generated.resources.updates_debug_test_complete
import nuvio.composeapp.generated.resources.updates_details_channel_label
import nuvio.composeapp.generated.resources.updates_details_size_label
import nuvio.composeapp.generated.resources.updates_details_title
import nuvio.composeapp.generated.resources.updates_details_version_range
import nuvio.composeapp.generated.resources.updates_downloading_progress
import nuvio.composeapp.generated.resources.updates_installing
import nuvio.composeapp.generated.resources.updates_message_allow_installs
import nuvio.composeapp.generated.resources.updates_message_ready
import nuvio.composeapp.generated.resources.updates_no_release_notes
import nuvio.composeapp.generated.resources.updates_preparing_download
import nuvio.composeapp.generated.resources.updates_quarantine_hint
import nuvio.composeapp.generated.resources.updates_ready_to_restart
import nuvio.composeapp.generated.resources.updates_release_notes
import nuvio.composeapp.generated.resources.updates_title_allow_installs
import nuvio.composeapp.generated.resources.updates_title_available
import nuvio.composeapp.generated.resources.updates_verifying_download
import nuvio.composeapp.generated.resources.updates_waiting_for_downloads
import nuvio.composeapp.generated.resources.updates_waiting_for_playback
import nuvio.composeapp.generated.resources.updates_waiting_for_playback_and_downloads
import nuvio.composeapp.generated.resources.updates_whats_new_dismiss
import nuvio.composeapp.generated.resources.updates_whats_new_subtitle
import nuvio.composeapp.generated.resources.updates_whats_new_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppUpdaterHost(
    controller: AppUpdaterController,
    modifier: Modifier = Modifier,
    canShowWhatsNew: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
        content()
        return
    }

    val state by controller.uiState.collectAsStateWithLifecycle()
    var showUpdateDetails by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(controller) {
        controller.ensureAutoCheckStarted()
    }
    LaunchedEffect(state.update?.tag) {
        showUpdateDetails = false
    }

    val update = state.update
    val showBanner = state.showDialog && update != null

    Column(modifier = modifier) {
        AnimatedVisibility(
            visible = showBanner,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(durationMillis = 300),
            ) + fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(durationMillis = 240),
            ) + fadeOut(animationSpec = tween(durationMillis = 150)),
        ) {
            update?.let { availableUpdate ->
                AppUpdateBanner(
                    state = state,
                    update = availableUpdate,
                    onDownload = controller::downloadUpdate,
                    onInstall = controller::installDownloadedUpdate,
                    onShowUpdateDetails = { showUpdateDetails = true },
                    onDismiss = controller::dismissDialog,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (showBanner) {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()
        }
    }

    if (showUpdateDetails && update != null) {
        if (AppUpdaterPlatform.usesForkUpdateFeed) {
            UpdateDetailsSheet(
                state = state,
                update = update,
                onUpdate = if (state.downloadedUpdatePath != null) {
                    controller::installDownloadedUpdate
                } else {
                    controller::downloadUpdate
                },
                onDismiss = { showUpdateDetails = false },
            )
        } else {
            LegacyReleaseNotesDialog(
                update = update,
                onDismiss = { showUpdateDetails = false },
            )
        }
    }

    if (AppUpdaterPlatform.usesForkUpdateFeed && canShowWhatsNew && state.showWhatsNew) {
        WhatsNewSheet(
            versionDisplay = state.currentVersionDisplay,
            entries = state.whatsNewEntries,
            onDismiss = controller::dismissWhatsNew,
        )
    }

    if (state.showInstallPermissionDialog) {
        UnknownSourcesDialog(
            onContinue = controller::resumeInstallation,
            onDismiss = controller::dismissDialog,
        )
    }
}

@Composable
private fun AppUpdateBanner(
    state: AppUpdaterUiState,
    update: AppUpdate,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onShowUpdateDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val usesForkFeed = AppUpdaterPlatform.usesForkUpdateFeed
    val busy = state.isDownloading || state.isVerifying || state.isInstalling
    val targetProgress = when {
        state.isDownloading -> state.downloadProgress ?: 0f
        state.isVerifying || state.isInstalling -> 1f
        state.isDebugTest && !state.isUpdateAvailable -> 1f
        else -> 0f
    }.coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 180),
        label = "updateBannerProgress",
    )
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val isWhiteTheme = MaterialTheme.appTheme == AppTheme.WHITE
    val progressBrush = MaterialTheme.themePalette.accentBrush()
    val progressAlpha = if (isWhiteTheme) 0.18f else 1f
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    val debugTestComplete = state.isDebugTest && !state.isDownloading && !state.isUpdateAvailable

    val notesSnippet = if (usesForkFeed) {
        ReleaseNotesParser.formatBannerSummary(state.pendingUpdateNotes)
            ?: ReleaseNotesParser.parse(update.notes).headline
    } else {
        update.notes
            .lineSequence()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .firstOrNull { it.isNotEmpty() }
    }
    val showingNotesSummary = usesForkFeed &&
        notesSnippet != null &&
        state.errorMessage == null &&
        !busy &&
        !debugTestComplete &&
        state.deferredInstallReason == null &&
        state.downloadedUpdatePath == null

    val subtitle = when {
        state.errorMessage != null -> state.errorMessage
        state.isInstalling -> stringResource(Res.string.updates_installing)
        state.isVerifying -> stringResource(Res.string.updates_verifying_download)
        state.isDownloading && state.downloadProgress != null -> stringResource(
            Res.string.updates_downloading_progress,
            (state.downloadProgress * 100).toInt().coerceIn(0, 100),
        )
        state.isDownloading -> stringResource(Res.string.updates_preparing_download)
        debugTestComplete -> stringResource(Res.string.updates_debug_test_complete)
        state.deferredInstallReason == InstallBlockReason.VIDEO_AND_DOWNLOAD ->
            stringResource(Res.string.updates_waiting_for_playback_and_downloads)
        state.deferredInstallReason == InstallBlockReason.VIDEO_PLAYING ->
            stringResource(Res.string.updates_waiting_for_playback)
        state.deferredInstallReason == InstallBlockReason.DOWNLOAD_ACTIVE ->
            stringResource(Res.string.updates_waiting_for_downloads)
        state.downloadedUpdatePath != null && usesForkFeed ->
            stringResource(Res.string.updates_ready_to_restart)
        state.downloadedUpdatePath != null -> stringResource(Res.string.updates_message_ready)
        notesSnippet != null -> notesSnippet
        else -> stringResource(Res.string.updates_title_available)
    }

    val channelShortLabel = if (usesForkFeed) formatFeedChannelShortLabel(state.feedInfo) else null
    val versionLabel = if (usesForkFeed) {
        "NuvioRoid ${update.versionDisplay}"
    } else {
        update.tag
    }
    val updateLabel = listOfNotNull(
        versionLabel,
        update.assetSizeBytes?.let(::formatFileSize),
        channelShortLabel,
    ).joinToString(separator = " • ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(containerColor)
                if (progress > 0f) {
                    drawRect(
                        brush = progressBrush,
                        alpha = progressAlpha,
                        size = Size(width = size.width * progress, height = size.height),
                    )
                }
                drawRect(
                    color = dividerColor,
                    topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = Size(width = size.width, height = 1.dp.toPx()),
                )
            }
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (debugTestComplete) Icons.Rounded.CheckCircle else Icons.Rounded.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = usesForkFeed || update.notes.isNotBlank(), onClick = onShowUpdateDetails),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = updateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        state.errorMessage != null -> MaterialTheme.colorScheme.error
                        busy && isWhiteTheme -> MaterialTheme.colorScheme.onSurface
                        busy -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = if (showingNotesSummary && state.pendingUpdateNotes.size > 1) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (usesForkFeed) {
                TextButton(
                    onClick = onShowUpdateDetails,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(Res.string.updates_details_title))
                }
            } else {
                IconButton(
                    onClick = onShowUpdateDetails,
                    enabled = update.notes.isNotBlank(),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(Res.string.updates_release_notes),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (!busy && !debugTestComplete) {
                Button(
                    onClick = if (state.downloadedUpdatePath != null) onInstall else onDownload,
                    enabled = (state.downloadedUpdatePath != null || state.isUpdateAvailable) &&
                        state.deferredInstallReason == null,
                    modifier = Modifier.heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        when {
                            state.errorMessage != null -> stringResource(Res.string.action_retry)
                            usesForkFeed -> stringResource(Res.string.updates_action_restart_to_update)
                            state.downloadedUpdatePath != null -> stringResource(Res.string.action_install)
                            else -> stringResource(Res.string.action_update)
                        },
                    )
                }
            }

            if (!busy) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateDetailsSheet(
    state: AppUpdaterUiState,
    update: AppUpdate,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val channelLabel = formatFeedChannelLabel(state.feedInfo)
    val sizeLabel = update.assetSizeBytes?.let(::formatFileSize)
    val busy = state.isDownloading || state.isVerifying || state.isInstalling
    val entries = state.pendingUpdateNotes.ifEmpty {
        val version = NuvioRoidVersion.parse(update.versionDisplay, explicitBuild = update.buildNumber)
            ?: NuvioRoidVersion.parse(update.tag)
            ?: NuvioRoidVersion.fromForkIdentity()
        listOf(
            ReleaseNoteEntry(
                version = version,
                versionDisplay = update.versionDisplay.ifBlank { version.format() },
                tag = update.tag,
                title = update.title,
                notes = update.notes,
                releaseUrl = update.releaseUrl,
            ),
        )
    }

    DialogSurface(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.updates_details_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        Res.string.updates_details_version_range,
                        state.currentVersionDisplay,
                        update.versionDisplay,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(Res.string.updates_details_channel_label, channelLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sizeLabel != null) {
                Text(
                    text = stringResource(Res.string.updates_details_size_label, sizeLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReleaseNotesVersionList(entries = entries)
            Text(
                text = stringResource(Res.string.updates_quarantine_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_close),
                onClick = onDismiss,
            )
            if (!busy && !state.isDebugTest && state.deferredInstallReason == null) {
                DialogButton(
                    text = stringResource(Res.string.updates_action_restart_to_update),
                    onClick = {
                        onDismiss()
                        onUpdate()
                    },
                    style = DialogButtonStyle.Primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsNewSheet(
    versionDisplay: String,
    entries: List<ReleaseNoteEntry>,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.updates_whats_new_title, versionDisplay),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.updates_whats_new_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReleaseNotesVersionList(
                entries = entries,
                showSingleHeader = entries.size > 1,
            )
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.updates_whats_new_dismiss),
                onClick = onDismiss,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyReleaseNotesDialog(
    update: AppUpdate,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.updates_release_notes),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = update.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = update.notes.ifBlank { stringResource(Res.string.updates_no_release_notes) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnknownSourcesDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.updates_title_allow_installs),
    ) {
        Text(
            text = stringResource(Res.string.updates_message_allow_installs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_later),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.action_continue),
                onClick = onContinue,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.SeasonBatchDownloads
import com.nuvio.app.features.downloads.SeasonBatchProgress
import com.nuvio.app.features.downloads.SeasonBatchRequest
import com.nuvio.app.features.downloads.SeasonBatchStart
import com.nuvio.app.features.downloads.SeasonDownloadChoices
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import com.nuvio.app.features.downloads.seasonBatchEpisodes
import com.nuvio.app.features.downloads.seasonBatchKey
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.LanguageSelectionDialog
import com.nuvio.app.features.settings.LanguageSelectionOption
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.isDesktop
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * "Download season" action for the season header. Desktop only. Asks once for quality, maximum
 * bitrate and audio language, starting from the last saved choices, then adds every released episode
 * of the season to the normal downloads queue. While the season is being added the button itself
 * shows how far it is and one click on it stops.
 */
@Composable
internal fun SeasonDownloadButton(
    meta: MetaDetails,
    seasonNumber: Int,
    modifier: Modifier = Modifier,
    profileId: Int = ProfileRepository.activeProfileId,
    startBatch: (SeasonBatchRequest) -> SeasonBatchStart = SeasonBatchDownloads::start,
    running: StateFlow<Map<String, SeasonBatchProgress>> = SeasonBatchDownloads.running,
    cancelBatch: (parentMetaId: String, seasonNumber: Int) -> Unit = SeasonBatchDownloads::cancel,
) {
    if (!isDesktop || !AppFeaturePolicy.downloadsEnabled || meta.type != "series") return

    val tokens = MaterialTheme.nuvio
    val runningBatches by running.collectAsState()
    val progress = runningBatches[seasonBatchKey(meta.id, seasonNumber)]
    // The open dialog. It keeps the profile it was opened for, so a profile switch while it is open
    // neither replaces what is being edited nor saves it for the other profile.
    var openChoices by remember { mutableStateOf<SeasonDownloadChoices?>(null) }
    val seasonLabel = if (seasonNumber <= 0) {
        stringResource(Res.string.episodes_specials)
    } else {
        stringResource(Res.string.episodes_season, seasonNumber)
    }
    val dialogTitle = stringResource(Res.string.downloads_season_dialog_title, seasonLabel)
    val buttonLabel = if (progress != null) {
        stringResource(Res.string.downloads_season_stop, progress.finished, progress.total)
    } else {
        stringResource(Res.string.downloads_season_button)
    }
    val buttonDescription = if (progress != null) buttonLabel else dialogTitle
    val startedText = stringResource(Res.string.downloads_season_started)
    val nothingToDownloadText = stringResource(Res.string.downloads_season_nothing)
    val alreadyRunningText = stringResource(Res.string.downloads_season_already_running)

    TextButton(
        modifier = modifier.semantics { contentDescription = buttonDescription },
        onClick = {
            if (progress != null) {
                cancelBatch(meta.id, seasonNumber)
            } else {
                openChoices = SeasonDownloadChoices(profileId)
            }
        },
        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (progress != null) Icons.Rounded.Close else Icons.Rounded.Download,
                contentDescription = null,
                modifier = Modifier.size(tokens.icons.sm),
            )
            Text(text = buttonLabel, style = MaterialTheme.typography.labelLarge)
        }
    }

    openChoices?.let { choices ->
        DownloadChoicesDialog(
            choices = choices,
            title = dialogTitle,
            message = stringResource(Res.string.downloads_season_dialog_message),
            onDismiss = { openChoices = null },
            onConfirm = { confirmed ->
                openChoices = null
                val result = startBatch(
                    SeasonBatchRequest(
                        contentType = meta.type,
                        parentMetaId = meta.id,
                        parentMetaType = meta.type,
                        title = meta.name,
                        logo = meta.logo,
                        poster = meta.poster,
                        background = meta.background,
                        seasonNumber = seasonNumber,
                        episodes = meta.seasonBatchEpisodes(seasonNumber, CurrentDateProvider.todayIsoDate()),
                        quality = confirmed.quality,
                        maxBitrateMbps = confirmed.maxBitrateMbps,
                        audioLanguage = confirmed.audioLanguage,
                    ),
                )
                NuvioToastController.show(
                    when (result) {
                        SeasonBatchStart.Started -> startedText
                        SeasonBatchStart.NothingToDownload -> nothingToDownloadText
                        SeasonBatchStart.AlreadyRunning -> alreadyRunningText
                    },
                )
            },
        )
    }
}

/**
 * Asks for quality, maximum bitrate and audio language, starting from [choices]. The choices are
 * saved, and handed to [onConfirm], only when the dialog is confirmed. [content] is shown between
 * the choices and the buttons.
 */
@Composable
internal fun DownloadChoicesDialog(
    choices: SeasonDownloadChoices,
    title: String,
    message: String,
    onConfirm: (SeasonDownloadDefaults) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(Res.string.downloads_season_start),
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    var picker by remember(choices) { mutableStateOf<SeasonDownloadPicker?>(null) }
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
        message = message,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap)) {
            SeasonDownloadChoiceRow(
                title = stringResource(Res.string.downloads_season_quality_heading),
                value = batchQualityLabel(choices.quality),
                onClick = { picker = SeasonDownloadPicker.Quality },
            )
            SeasonDownloadChoiceRow(
                title = stringResource(Res.string.downloads_season_max_bitrate),
                description = stringResource(Res.string.downloads_season_max_bitrate_description),
                value = maxBitrateLabel(choices.maxBitrateMbps),
                onClick = { picker = SeasonDownloadPicker.MaxBitrate },
            )
            SeasonDownloadChoiceRow(
                title = stringResource(Res.string.downloads_season_audio_language),
                value = audioLanguageLabel(choices.audioLanguage),
                onClick = { picker = SeasonDownloadPicker.AudioLanguage },
            )
        }
        content()
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = confirmLabel,
                style = DialogButtonStyle.Primary,
                enabled = confirmEnabled,
                onClick = { onConfirm(choices.confirm()) },
            )
        }
    }

    when (picker) {
        SeasonDownloadPicker.Quality -> DownloadChoiceDialog(
            title = stringResource(Res.string.downloads_season_quality_heading),
            options = BatchQuality.entries,
            selected = choices.quality,
            label = { batchQualityLabel(it) },
            onSelect = {
                choices.quality = it
                picker = null
            },
            onDismiss = { picker = null },
        )
        SeasonDownloadPicker.MaxBitrate -> DownloadChoiceDialog(
            title = stringResource(Res.string.downloads_season_max_bitrate),
            options = SeasonDownloadDefaults.maxBitrateOptions,
            selected = choices.maxBitrateMbps,
            label = { maxBitrateLabel(it) },
            onSelect = {
                choices.maxBitrateMbps = it
                picker = null
            },
            onDismiss = { picker = null },
        )
        SeasonDownloadPicker.AudioLanguage -> {
            val options = SeasonDownloadDefaults.audioLanguageOptions.map { language ->
                LanguageSelectionOption(value = language?.code, label = audioLanguageLabel(language))
            }
            LanguageSelectionDialog(
                title = stringResource(Res.string.downloads_season_audio_language),
                options = options,
                selectedValue = choices.audioLanguage?.code,
                onSelect = { code ->
                    choices.audioLanguage = SeasonDownloadDefaults.audioLanguageOptions.firstOrNull { it?.code == code }
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }
        null -> Unit
    }
}

private enum class SeasonDownloadPicker { Quality, MaxBitrate, AudioLanguage }

/**
 * A picker for one download choice. Every option is a radio button of one group that is named by
 * its label, so assistive technology reads the option and whether it is the chosen one.
 */
@Composable
internal fun <T> DownloadChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(onDismissRequest = onDismiss, title = title) {
        Column(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
        ) {
            options.forEach { option ->
                val optionLabel = label(option)
                DialogOption(
                    text = optionLabel,
                    modifier = Modifier.semantics { contentDescription = optionLabel },
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_done), onClick = onDismiss)
        }
    }
}

/**
 * One choice of the season dialog: a button named by [title] whose state is the current [value]. A
 * click opens its picker.
 */
@Composable
private fun SeasonDownloadChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    description: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = value
            }
            .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s12),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
            if (description != null) {
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
        }
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
    }
}

@Composable
private fun batchQualityLabel(quality: BatchQuality): String = when (quality) {
    BatchQuality.BestAvailable -> stringResource(Res.string.downloads_season_quality_any)
    BatchQuality.P2160 -> stringResource(Res.string.downloads_season_quality_2160)
    BatchQuality.P1080 -> stringResource(Res.string.downloads_season_quality_1080)
    BatchQuality.P720 -> stringResource(Res.string.downloads_season_quality_720)
    BatchQuality.P480 -> stringResource(Res.string.downloads_season_quality_480)
}

@Composable
private fun maxBitrateLabel(maxBitrateMbps: Int?): String =
    if (maxBitrateMbps == null) {
        stringResource(Res.string.downloads_season_bitrate_no_limit)
    } else {
        stringResource(Res.string.downloads_season_bitrate_value, maxBitrateMbps)
    }

@Composable
private fun audioLanguageLabel(language: DebridStreamLanguage?): String =
    language?.label ?: stringResource(Res.string.downloads_season_audio_language_any)

package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.components.DownloadPreferencePicker
import com.nuvio.app.features.details.components.DownloadPreferencePickers
import com.nuvio.app.features.details.components.DownloadPreferenceRows
import com.nuvio.app.features.downloads.PreferenceSetupAction
import com.nuvio.app.features.downloads.PreferenceSetupAnswer
import com.nuvio.app.features.downloads.PreferenceSetupRepository
import com.nuvio.app.features.downloads.SeasonDownloadChoices
import com.nuvio.app.features.streams.StreamAutoPlayMode
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_preferences_section_play
import nuvio.composeapp.generated.resources.settings_preferences_setup_message
import nuvio.composeapp.generated.resources.settings_preferences_setup_skip
import nuvio.composeapp.generated.resources.settings_preferences_setup_title
import org.jetbrains.compose.resources.stringResource

/**
 * Asks once per profile for the quality and language preferences, when the profile has not been
 * asked and has no saved download choices. Show it where the app rests on its main content:
 * [canInterrupt] is false while anything else has the screen, and the dialog then waits.
 *
 * A profile that already has saved choices is marked as asked without a dialog. Saving or closing
 * the dialog marks the profile as asked, so it does not come back.
 */
@Composable
internal fun PreferenceSetupHost(
    profileId: Int,
    canInterrupt: Boolean,
    playMode: StreamAutoPlayMode,
    onPlayModeChange: (StreamAutoPlayMode) -> Unit,
) {
    var answers by remember(profileId) { mutableIntStateOf(0) }
    val action = remember(profileId, canInterrupt, answers) {
        PreferenceSetupRepository.actionFor(profileId, canInterrupt)
    }
    LaunchedEffect(profileId, action) {
        if (action == PreferenceSetupAction.MarkAlreadyConfigured) {
            PreferenceSetupRepository.record(profileId, PreferenceSetupAnswer.AlreadyConfigured)
            answers++
        }
    }
    if (action == PreferenceSetupAction.Show) {
        PreferenceSetupDialog(
            profileId = profileId,
            playMode = playMode,
            onPlayModeChange = onPlayModeChange,
            onAnswered = { answer ->
                PreferenceSetupRepository.record(profileId, answer)
                answers++
            },
        )
    }
}

/**
 * The one-time dialog: quality, maximum bitrate, audio language and what Play does, starting from
 * what is saved for [profileId]. Save stores the three choices for the profile, and the Play choice
 * when it was changed. "Not now", and closing the dialog, store nothing.
 */
@Composable
internal fun PreferenceSetupDialog(
    profileId: Int,
    playMode: StreamAutoPlayMode,
    onPlayModeChange: (StreamAutoPlayMode) -> Unit,
    onAnswered: (PreferenceSetupAnswer) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val choices = remember(profileId) { SeasonDownloadChoices(profileId) }
    var selectedMode by remember(profileId) { mutableStateOf(playMode) }
    var picker by remember(profileId) { mutableStateOf<DownloadPreferencePicker?>(null) }
    DialogSurface(
        onDismissRequest = { onAnswered(PreferenceSetupAnswer.Skipped) },
        title = stringResource(Res.string.settings_preferences_setup_title),
        message = stringResource(Res.string.settings_preferences_setup_message),
    ) {
        Column(
            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
        ) {
            DownloadPreferenceRows(
                quality = choices.quality,
                maxBitrateMbps = choices.maxBitrateMbps,
                audioLanguage = choices.audioLanguage,
                onPick = { picker = it },
            )
            NuvioSectionLabel(
                text = stringResource(Res.string.settings_preferences_section_play),
                modifier = Modifier.semantics { heading() },
            )
            PlayChoiceOptions(
                mode = selectedMode,
                onModeChange = { selectedMode = it },
            )
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.settings_preferences_setup_skip),
                onClick = { onAnswered(PreferenceSetupAnswer.Skipped) },
            )
            DialogButton(
                text = stringResource(Res.string.action_save),
                style = DialogButtonStyle.Primary,
                onClick = {
                    choices.confirm()
                    if (selectedMode != playMode) onPlayModeChange(selectedMode)
                    onAnswered(PreferenceSetupAnswer.Saved)
                },
            )
        }
    }

    DownloadPreferencePickers(
        picker = picker,
        quality = choices.quality,
        maxBitrateMbps = choices.maxBitrateMbps,
        audioLanguage = choices.audioLanguage,
        onQualityChange = { choices.quality = it },
        onMaxBitrateChange = { choices.maxBitrateMbps = it },
        onAudioLanguageChange = { choices.audioLanguage = it },
        onDismiss = { picker = null },
    )
}

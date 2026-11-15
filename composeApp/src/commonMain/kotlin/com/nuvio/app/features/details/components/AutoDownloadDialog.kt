package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.downloads.AutoDownloadController
import com.nuvio.app.features.downloads.AutoDownloadRule
import com.nuvio.app.features.downloads.AutoDownloadScope
import com.nuvio.app.features.settings.SettingsSwitchRow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The automatic-download rule of one show: how many episodes to keep, which ones, and whether they
 * are deleted after they were watched. [rule] is the
 * show's current rule, or null when automatic downloads are off for it. Nothing changes until the
 * dialog is confirmed, and then only for [profileId], the profile the dialog was opened for.
 */
@Composable
internal fun AutoDownloadDialog(
    meta: MetaDetails,
    rule: AutoDownloadRule?,
    profileId: Int,
    controller: AutoDownloadController,
    onDismiss: () -> Unit,
    /** Opens the show's page. Null where the dialog is already on it: the button is not offered. */
    onGoToShow: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    var keepCount by remember { mutableStateOf(rule?.keepCount ?: AutoDownloadRule.DefaultKeepCount) }
    var scope by remember { mutableStateOf(rule?.scope ?: AutoDownloadScope.NextUnwatched) }
    var deleteAfterWatching by remember { mutableStateOf(rule?.deleteAfterWatching ?: false) }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.downloads_auto_dialog_title, meta.name),
        message = stringResource(Res.string.downloads_auto_dialog_message),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
        ) {
            NuvioSectionLabel(
                text = stringResource(Res.string.downloads_auto_keep_heading),
                modifier = Modifier.semantics { heading() },
            )
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            ) {
                AutoDownloadRule.keepCountOptions.forEach { count ->
                    val label = pluralStringResource(Res.plurals.downloads_auto_keep_option, count, count)
                    DialogOption(
                        text = label,
                        modifier = Modifier.semantics { contentDescription = label },
                        selected = keepCount == count,
                        onClick = { keepCount = count },
                    )
                }
            }
            NuvioSectionLabel(
                text = stringResource(Res.string.downloads_auto_scope_heading),
                modifier = Modifier
                    .padding(top = NuvioTokens.Space.s8)
                    .semantics { heading() },
            )
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            ) {
                mapOf(
                    AutoDownloadScope.NextUnwatched to stringResource(Res.string.downloads_auto_scope_next),
                    AutoDownloadScope.LatestReleased to stringResource(Res.string.downloads_auto_scope_latest),
                ).forEach { (option, label) ->
                    DialogOption(
                        text = label,
                        modifier = Modifier.semantics { contentDescription = label },
                        selected = scope == option,
                        onClick = { scope = option },
                    )
                }
            }
            Text(
                text = stringResource(Res.string.downloads_auto_choices_note),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            NuvioSectionLabel(
                text = stringResource(Res.string.downloads_auto_delete_heading),
                modifier = Modifier
                    .padding(top = NuvioTokens.Space.s8)
                    .semantics { heading() },
            )
            // The app's switch row, so that off is as visible as on. The row and the switch inside
            // it would be two unnamed controls to assistive technology, so the row is described as
            // one: a switch named by its title, with its state.
            val deleteTitle = stringResource(Res.string.downloads_auto_delete_after)
            val deleteNote = stringResource(Res.string.downloads_auto_delete_note)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(tokens.shapes.compactCard)
                    .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                    .clearAndSetSemantics {
                        contentDescription = deleteTitle
                        text = AnnotatedString(deleteNote)
                        role = Role.Switch
                        toggleableState = ToggleableState(deleteAfterWatching)
                        onClick {
                            deleteAfterWatching = !deleteAfterWatching
                            true
                        }
                    },
            ) {
                SettingsSwitchRow(
                    title = deleteTitle,
                    description = deleteNote,
                    checked = deleteAfterWatching,
                    isTablet = false,
                    horizontalPadding = NuvioTokens.Space.s14,
                    onCheckedChange = { deleteAfterWatching = it },
                )
            }
            if (!controller.usesSystemTrash) {
                Text(
                    text = stringResource(Res.string.downloads_auto_delete_no_trash_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        DialogButtons {
            if (onGoToShow != null) {
                DialogButton(
                    text = stringResource(Res.string.downloads_auto_go_to_show),
                    onClick = {
                        onDismiss()
                        onGoToShow()
                    },
                )
            }
            if (rule != null) {
                DialogButton(
                    text = stringResource(Res.string.downloads_auto_turn_off),
                    onClick = {
                        onDismiss()
                        controller.turnOff(profileId, rule.key)
                    },
                )
            }
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(if (rule != null) Res.string.action_save else Res.string.downloads_auto_turn_on),
                style = DialogButtonStyle.Primary,
                onClick = {
                    onDismiss()
                    controller.save(profileId, meta, keepCount, scope, deleteAfterWatching)
                },
            )
        }
    }
}

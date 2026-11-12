package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.downloads.AutoDownloadController
import com.nuvio.app.features.downloads.AutoDownloadRule
import com.nuvio.app.features.downloads.AutoDownloadScope
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The automatic-download rule of one show: how many episodes to keep and which ones. [rule] is the
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
) {
    val tokens = MaterialTheme.nuvio
    var keepCount by remember { mutableStateOf(rule?.keepCount ?: AutoDownloadRule.DefaultKeepCount) }
    var scope by remember { mutableStateOf(rule?.scope ?: AutoDownloadScope.NextUnwatched) }
    val turnedOnText = stringResource(Res.string.downloads_auto_toast_on)
    val turnedOffText = stringResource(Res.string.downloads_auto_toast_off)

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
        }
        DialogButtons {
            if (rule != null) {
                DialogButton(
                    text = stringResource(Res.string.downloads_auto_turn_off),
                    onClick = {
                        onDismiss()
                        controller.turnOff(profileId, rule.key)
                        NuvioToastController.show(turnedOffText)
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
                    controller.save(profileId, meta, keepCount, scope)
                    if (rule == null) NuvioToastController.show(turnedOnText)
                },
            )
        }
    }
}

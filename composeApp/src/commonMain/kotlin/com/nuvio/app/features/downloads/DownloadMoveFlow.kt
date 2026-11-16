package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.DialogProperties
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What the user asked to move, and where it starts. */
internal sealed interface MoveRequest {
    /** Use [path] for new downloads, then decide what happens to what is already downloaded. */
    data class ChangeDefault(val path: String) : MoveRequest

    /** Move everything that is not in [path] into it. */
    data class MoveAllTo(val path: String) : MoveRequest

    /** Move these downloads; the destination is asked for. */
    data class MoveItems(val itemIds: Set<String>) : MoveRequest
}

private sealed interface FlowStep {
    data object Checking : FlowStep
    data class PickDestination(val ids: Set<String>) : FlowStep
    data class Choice(val path: String) : FlowStep
    data class Checklist(val path: String) : FlowStep
    data class Confirm(val path: String, val ids: Set<String>, val makeDefault: Boolean) : FlowStep
    data object Running : FlowStep
    data class Done(val report: MoveReport) : FlowStep
    data class Problem(val problem: FolderProblem, val neededBytes: Long = 0L, val freeBytes: Long? = null) : FlowStep
}

@Composable
internal fun folderProblemText(problem: FolderProblem, neededBytes: Long, freeBytes: Long?): String = when (problem) {
    FolderProblem.NotAFolder -> stringResource(Res.string.download_folder_problem_not_a_folder)
    FolderProblem.NotConnected -> stringResource(Res.string.download_folder_problem_not_connected)
    FolderProblem.CannotCreate -> stringResource(Res.string.download_folder_problem_cannot_create)
    FolderProblem.NotWritable -> stringResource(Res.string.download_folder_problem_not_writable)
    FolderProblem.InsideAppData -> stringResource(Res.string.download_folder_problem_inside_app_data)
    FolderProblem.InsideProgram -> stringResource(Res.string.download_folder_problem_inside_program)
    FolderProblem.IsTrash -> stringResource(Res.string.download_folder_problem_trash)
    FolderProblem.OverlapsDownloadFolder -> stringResource(Res.string.download_folder_problem_overlaps)
    FolderProblem.MoveInProgress -> stringResource(Res.string.download_folder_problem_move_in_progress)
    FolderProblem.PreviousMoveUnresolved -> stringResource(Res.string.download_folder_problem_previous_move)
    FolderProblem.MarkerTaken -> stringResource(Res.string.download_folder_problem_marker_taken)
    FolderProblem.NotEnoughSpace -> stringResource(
        Res.string.download_folder_problem_not_enough_space,
        formatBytes(neededBytes),
        freeBytes?.let(::formatBytes) ?: "0",
    )
}

@Composable
private fun moveFailureText(reason: MoveFailureReason): String = stringResource(
    when (reason) {
        MoveFailureReason.NoSpace -> Res.string.download_folder_reason_no_space
        MoveFailureReason.ChangedWhileCopying -> Res.string.download_folder_reason_changed
        MoveFailureReason.CopyDidNotMatch -> Res.string.download_folder_reason_mismatch
        MoveFailureReason.NameTaken -> Res.string.download_folder_reason_name_taken
        MoveFailureReason.CouldNotRead -> Res.string.download_folder_reason_read
        MoveFailureReason.CouldNotWrite -> Res.string.download_folder_reason_write
        MoveFailureReason.Other -> Res.string.download_folder_reason_other
        MoveFailureReason.Symlink -> Res.string.download_folder_reason_symlink
        MoveFailureReason.RecordChanged -> Res.string.download_folder_reason_record_changed
        MoveFailureReason.Playing -> Res.string.download_folder_reason_playing
        MoveFailureReason.Busy -> Res.string.download_folder_reason_busy
        MoveFailureReason.StillWriting -> Res.string.download_folder_reason_still_writing
        MoveFailureReason.OriginalNotRemoved -> Res.string.download_folder_reason_original
        MoveFailureReason.KeptInBothPlaces -> Res.string.download_folder_reason_kept_both
        MoveFailureReason.OwnedByAnother -> Res.string.download_folder_reason_owned
    },
)

/** The sentence a finished move is reported with: "12 items moved to X, 1 could not be moved: reason". */
@Composable
internal fun moveReportLines(report: MoveReport): List<String> {
    val lines = mutableListOf<String>()
    if (report.moved > 0) {
        lines += pluralStringResource(Res.plurals.download_folder_result_moved, report.moved, report.moved, report.destinationName)
    }
    if (report.failures.isNotEmpty()) {
        val reasons = report.failures.map { failure ->
            stringResource(Res.string.download_folder_reason_item, failure.name, moveFailureText(failure.reason))
        }.joinToString("; ")
        lines += pluralStringResource(Res.plurals.download_folder_result_failed, report.failures.size, report.failures.size, reasons)
    }
    if (report.originalsKept.isNotEmpty()) {
        val why = report.originalsKept.map { it.detail ?: stringResource(Res.string.download_folder_reason_original) }.distinct().joinToString("; ")
        lines += stringResource(Res.string.download_folder_result_kept, report.originalsKept.size, why)
    }
    if (report.cancelled) lines += stringResource(Res.string.download_folder_result_cancelled, report.notReached)
    if (report.unreachable > 0) lines += stringResource(Res.string.download_folder_result_unreachable, report.unreachable)
    if (lines.isEmpty()) lines += stringResource(Res.string.download_folder_result_none)
    return lines
}

/**
 * The steps of a move: where to, what, a check that it fits, then the move itself with progress and a
 * way to cancel, and what came of it. Used by the setting and by the Downloads screen.
 */
@Composable
internal fun DownloadMoveFlow(
    request: MoveRequest,
    controller: DownloadFolderController = DownloadFolders.controller,
    items: List<DownloadItem>,
    onFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val folderState by controller.state.collectAsStateWithLifecycle()
    val progress by controller.progress.collectAsStateWithLifecycle()
    var step by remember(request) {
        mutableStateOf<FlowStep>(
            when (request) {
                is MoveRequest.MoveItems -> FlowStep.PickDestination(request.itemIds)
                else -> FlowStep.Checking
            },
        )
    }
    val savedToast = stringResource(Res.string.download_folder_saved_toast)

    fun locationOf(item: DownloadItem) = controller.locationIdOf(item)
    fun movableTo(path: String): List<DownloadItem> {
        val destination = folderState.locations.firstOrNull { it.path == path }?.id
        return items.filter { item ->
            locationOf(item) != destination &&
                folderState.location(locationOf(item))?.availability.let { it == null || it == FolderAvailability.Available }
        }
    }

    fun validateThen(path: String, then: (String) -> FlowStep) {
        step = FlowStep.Checking
        scope.launch {
            val check = withContext(Dispatchers.Default) { controller.check(path, 0L) }
            step = when (check) {
                FolderCheck.Ok -> then(path)
                is FolderCheck.Problem -> FlowStep.Problem(check.kind, check.neededBytes, check.freeBytes)
            }
        }
    }

    LaunchedEffect(request) {
        when (request) {
            is MoveRequest.ChangeDefault -> validateThen(request.path) { FlowStep.Choice(it) }
            is MoveRequest.MoveAllTo -> validateThen(request.path) { path ->
                FlowStep.Confirm(path, movableTo(path).map { it.id }.toSet(), makeDefault = false)
            }
            is MoveRequest.MoveItems -> Unit
        }
    }

    fun startMove(path: String, ids: Set<String>, makeDefault: Boolean) {
        step = FlowStep.Running
        scope.launch {
            val report = withContext(Dispatchers.Default) {
                if (makeDefault) controller.setDefault(path)
                controller.move(ids, path)
            }
            controller.refresh()
            step = FlowStep.Done(report)
        }
    }

    when (val current = step) {
        FlowStep.Checking -> Unit
        is FlowStep.PickDestination -> DestinationDialog(
            locations = folderState.locations,
            excludeIds = items.filter { it.id in current.ids }.map(::locationOf).toSet().takeIf { it.size == 1 }.orEmpty(),
            onPickLocation = { location ->
                validateThen(location.path) { path -> FlowStep.Confirm(path, current.ids, makeDefault = false) }
            },
            onPickOther = {
                step = FlowStep.Checking
                scope.launch {
                    val chosen = withContext(Dispatchers.Default) { controller.pickFolder() }
                    if (chosen == null) onFinished() else validateThen(chosen) { path -> FlowStep.Confirm(path, current.ids, makeDefault = false) }
                }
            },
            onDismiss = onFinished,
        )
        is FlowStep.Choice -> ChoiceDialog(
            path = current.path,
            movable = movableTo(current.path),
            onAll = { step = FlowStep.Confirm(current.path, movableTo(current.path).map { it.id }.toSet(), makeDefault = true) },
            onSome = { step = FlowStep.Checklist(current.path) },
            onNone = {
                scope.launch {
                    withContext(Dispatchers.Default) { controller.setDefault(current.path) }
                    controller.refresh()
                    NuvioToastController.show(savedToast.replace("%1\$s", current.path))
                    onFinished()
                }
            },
            onDismiss = onFinished,
        )
        is FlowStep.Checklist -> ChecklistDialog(
            path = current.path,
            groups = movableGroups(movableTo(current.path), ::locationOf),
            controller = controller,
            onContinue = { ids -> step = FlowStep.Confirm(current.path, ids, makeDefault = request is MoveRequest.ChangeDefault) },
            onDismiss = onFinished,
        )
        is FlowStep.Confirm -> ConfirmDialog(
            path = current.path,
            ids = current.ids,
            makeDefault = current.makeDefault,
            items = items,
            controller = controller,
            onConfirm = { startMove(current.path, current.ids, current.makeDefault) },
            onDismiss = onFinished,
        )
        FlowStep.Running -> ProgressDialog(progress = progress, onCancel = controller::cancelMove)
        is FlowStep.Done -> ResultDialog(report = current.report, onDismiss = onFinished)
        is FlowStep.Problem -> ProblemDialog(current, onDismiss = onFinished)
    }
    // The location list is also what the confirmation reads from.
    if (folderState.locations.isEmpty()) LaunchedEffect(Unit) { withContext(Dispatchers.Default) { controller.refresh() } }
}

@Composable
internal fun DestinationDialog(
    locations: List<DownloadLocationInfo>,
    excludeIds: Set<String>,
    onPickLocation: (DownloadLocationInfo) -> Unit,
    onPickOther: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.downloads_move_destination_title)) {
        // The folders already in use come first.
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            locations.filter { it.id !in excludeIds && (it.isDefault || it.itemCount > 0) }.forEach { location ->
                val connected = location.availability == FolderAvailability.Available
                val label = if (connected) {
                    stringResource(Res.string.downloads_move_destination_option, location.name, location.path)
                } else {
                    stringResource(Res.string.downloads_move_destination_not_connected, location.name)
                }
                TextButton(
                    onClick = { onPickLocation(location) },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(location.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (connected) location.path else stringResource(Res.string.downloads_move_destination_not_connected, location.path),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (onPickOther != null) {
                TextButton(onClick = onPickOther, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.downloads_move_destination_other))
                }
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
        }
    }
}

@Composable
private fun ChoiceDialog(
    path: String,
    movable: List<DownloadItem>,
    onAll: () -> Unit,
    onSome: () -> Unit,
    onNone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bytes = movable.sumOf { it.moveBytes }
    val message = if (movable.isEmpty()) {
        stringResource(Res.string.download_folder_change_message_none, path)
    } else {
        stringResource(
            Res.string.download_folder_change_message,
            path,
            pluralStringResource(Res.plurals.download_folder_items, movable.size, movable.size) + ", " + formatBytes(bytes),
        )
    }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.download_folder_change_title), message = message) {
        if (movable.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "" },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onAll, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.download_folder_choice_all))
                }
                TextButton(onClick = onSome, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.download_folder_choice_some))
                }
                TextButton(onClick = onNone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.download_folder_choice_none))
                }
                Text(
                    text = stringResource(Res.string.download_folder_choice_none_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss) }
        } else {
            DialogButtons {
                DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
                DialogButton(
                    text = stringResource(Res.string.download_folder_confirm_save),
                    onClick = onNone,
                    style = DialogButtonStyle.Primary,
                )
            }
        }
    }
}

@Composable
private fun ChecklistDialog(
    path: String,
    groups: List<MovableGroup>,
    controller: DownloadFolderController,
    onContinue: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<String>() }
    val expanded = remember { mutableStateListOf<String>() }
    val all = remember(groups) { groups.flatMap { it.items } }
    val selectedSet = selected.toSet()
    val total = selectedBytes(all, selectedSet)
    val destinationName = path.substringAfterLast('/').ifBlank { path }
    val free by produceState<Long?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) { controller.freeBytesAt(path) }
    }
    val needed by produceState(initialValue = total, selectedSet) {
        value = withContext(Dispatchers.Default) { controller.bytesToCopy(selectedSet, path) }
    }
    val fit = selectionFit(needed, free)
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.download_folder_checklist_title),
        message = stringResource(Res.string.download_folder_checklist_message, path),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(groups, key = { it.key }) { group ->
                val ids = group.items.map { it.id }
                val state = when {
                    ids.all { it in selected } -> ToggleableState.On
                    ids.none { it in selected } -> ToggleableState.Off
                    else -> ToggleableState.Indeterminate
                }
                val isOpen = group.key in expanded
                val groupLabel = stringResource(Res.string.download_folder_checklist_group_row, group.title, formatBytes(group.bytes))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .triStateToggleable(state = state, role = Role.Checkbox) {
                                if (state == ToggleableState.On) selected.removeAll(ids) else ids.filter { it !in selected }.forEach(selected::add)
                            }
                            .padding(vertical = 6.dp)
                            .semantics { contentDescription = groupLabel },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.TriStateCheckbox(state = state, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(group.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(formatBytes(group.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!group.isMovie) {
                        val toggleLabel = stringResource(
                            if (isOpen) Res.string.download_folder_checklist_collapse else Res.string.download_folder_checklist_expand,
                            group.title,
                        )
                        IconButton(onClick = { if (isOpen) expanded.remove(group.key) else expanded.add(group.key) }) {
                            Icon(
                                imageVector = if (isOpen) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                contentDescription = toggleLabel,
                            )
                        }
                    }
                }
                if (isOpen && !group.isMovie) {
                    group.items.forEach { item ->
                        val checked = item.id in selected
                        val label = stringResource(Res.string.download_folder_checklist_group_row, item.label, formatBytes(item.bytes))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp)
                                .toggleable(value = checked, role = Role.Checkbox) { on -> if (on) selected.add(item.id) else selected.remove(item.id) }
                                .padding(vertical = 4.dp)
                                .semantics { contentDescription = label },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(
                                text = label,
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selected.clear(); all.forEach { selected.add(it.id) } }) {
                Text(stringResource(Res.string.download_folder_checklist_select_all))
            }
            TextButton(onClick = { selected.clear() }) { Text(stringResource(Res.string.download_folder_checklist_select_none)) }
        }
        Text(
            text = stringResource(Res.string.download_folder_checklist_total, pluralStringResource(Res.plurals.download_folder_items, selected.size, selected.size), formatBytes(total)),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(Res.string.download_folder_checklist_free, free?.let(::formatBytes) ?: stringResource(Res.string.settings_download_folder_free_unknown), destinationName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (fit) {
            SelectionFit.DoesNotFit -> Text(
                text = stringResource(Res.string.download_folder_checklist_does_not_fit),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            SelectionFit.Tight -> Text(
                text = stringResource(Res.string.download_folder_checklist_tight),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Unit
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.action_continue),
                onClick = { onContinue(selectedSet) },
                style = DialogButtonStyle.Primary,
                enabled = selected.isNotEmpty() && fit != SelectionFit.DoesNotFit,
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    path: String,
    ids: Set<String>,
    makeDefault: Boolean,
    items: List<DownloadItem>,
    controller: DownloadFolderController,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val moving = items.filter { it.id in ids }
    val bytes = moving.sumOf { it.moveBytes }
    val from = moving.map { controller.locationIdOf(it) }.distinct()
        .mapNotNull { id -> controller.state.value.location(id)?.path }
        .joinToString(", ")
    val check by produceState<FolderCheck?>(initialValue = null, path, ids) {
        value = withContext(Dispatchers.Default) { controller.check(path, controller.bytesToCopy(ids, path)) }
    }
    val problem = check as? FolderCheck.Problem
    val count = pluralStringResource(Res.plurals.download_folder_items, moving.size, moving.size)
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.download_folder_confirm_title, count),
        message = stringResource(Res.string.download_folder_confirm_message, formatBytes(bytes), from, path),
    ) {
        if (problem != null) {
            Text(
                text = folderProblemText(problem.kind, problem.neededBytes, problem.freeBytes),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.download_folder_confirm_move),
                onClick = onConfirm,
                style = DialogButtonStyle.Primary,
                enabled = check != null && problem == null && moving.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun ProgressDialog(progress: MoveProgress?, onCancel: () -> Unit) {
    DialogSurface(
        onDismissRequest = {},
        title = stringResource(Res.string.download_folder_progress_title),
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        val overallLabel = stringResource(Res.string.download_folder_progress_overall)
        val currentLabel = stringResource(Res.string.download_folder_progress_current)
        if (progress == null) {
            Text(
                text = stringResource(Res.string.download_folder_progress_starting),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            // Said once for each file, never for each chunk.
            Text(
                text = progress.currentName?.let {
                    stringResource(Res.string.download_folder_progress_file, (progress.doneFiles + 1).coerceAtMost(progress.totalFiles), progress.totalFiles, it.removeSuffix(".part"))
                } ?: stringResource(Res.string.download_folder_progress_starting),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = progress.overallFraction,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = overallLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(progress.overallFraction, 0f..1f)
                },
            )
            Text(overallLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(
                progress = progress.currentFraction,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = currentLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(progress.currentFraction, 0f..1f)
                },
            )
            Text(currentLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DialogButtons {
            DialogButton(
                text = stringResource(
                    if (progress?.cancelling == true) Res.string.download_folder_progress_cancelling else Res.string.download_folder_progress_cancel,
                ),
                onClick = onCancel,
                enabled = progress?.cancelling != true,
            )
        }
    }
}

@Composable
private fun ResultDialog(report: MoveReport, onDismiss: () -> Unit) {
    val lines = moveReportLines(report)
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(
            when {
                report.cancelled -> Res.string.download_folder_result_title_cancelled
                else -> Res.string.download_folder_result_title
            },
        ),
    ) {
        Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            lines.forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.download_folder_result_ok), onClick = onDismiss, style = DialogButtonStyle.Primary)
        }
    }
}

@Composable
private fun ProblemDialog(problem: FlowStep.Problem, onDismiss: () -> Unit) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.download_folder_result_title_problem),
        message = folderProblemText(problem.problem, problem.neededBytes, problem.freeBytes),
    ) {
        DialogButtons {
            DialogButton(text = stringResource(Res.string.download_folder_result_ok), onClick = onDismiss, style = DialogButtonStyle.Primary)
        }
    }
}

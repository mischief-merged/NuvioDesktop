package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.tracking.outbox.QueueSyncResult
import com.nuvio.app.features.tracking.outbox.QueueSyncState
import com.nuvio.app.features.tracking.outbox.TrackerQueueController
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_trakt_queue_dismiss
import nuvio.composeapp.generated.resources.settings_trakt_queue_dropped
import nuvio.composeapp.generated.resources.settings_trakt_queue_dropped_description
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_all_sent
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_not_accepted
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_nothing_waiting
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_offline
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_partial
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_refused
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_retry_in
import nuvio.composeapp.generated.resources.settings_trakt_queue_sync_now
import nuvio.composeapp.generated.resources.settings_trakt_queue_syncing
import nuvio.composeapp.generated.resources.settings_trakt_queue_try_again
import nuvio.composeapp.generated.resources.settings_trakt_queue_undeliverable
import nuvio.composeapp.generated.resources.settings_trakt_queue_undeliverable_description
import nuvio.composeapp.generated.resources.settings_trakt_queue_waiting
import nuvio.composeapp.generated.resources.settings_trakt_queue_waiting_description
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Trakt queue inside the connected Trakt card: how many watches wait, a Sync now action with its
 * result in place, and the watches that were refused or removed for age, each with what the user can do.
 * Shows nothing while there is nothing to report.
 */
@Composable
internal fun TrackerQueueSection(
    queue: TrackerQueueController,
    modifier: Modifier = Modifier,
) {
    val counts by queue.counts.collectAsStateWithLifecycle()
    val syncState by queue.syncState.collectAsStateWithLifecycle()
    DisposableEffect(queue) { onDispose { queue.clearResult() } }

    val syncing = syncState is QueueSyncState.Syncing
    val result = (syncState as? QueueSyncState.Done)?.result
    if (counts.isEmpty && !syncing && result == null) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (counts.waiting > 0 || syncing) {
            Column(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = pluralStringResource(Res.plurals.settings_trakt_queue_waiting, counts.waiting, counts.waiting),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_trakt_queue_waiting_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.76f),
                )
            }
            TrackingBrandPrimaryButton(
                label = stringResource(Res.string.settings_trakt_queue_sync_now),
                loading = syncing,
                enabled = !syncing,
                onClick = queue::syncNow,
                showSyncIcon = true,
                busyDescription = stringResource(Res.string.settings_trakt_queue_syncing),
            )
        }
        if (syncing) {
            TrackingBrandMessage(
                text = stringResource(Res.string.settings_trakt_queue_syncing),
                isError = false,
                announce = true,
            )
        }
        result?.let { TrackingBrandMessage(text = queueResultText(it), isError = false, announce = true) }

        if (counts.undeliverable > 0) {
            val title = pluralStringResource(Res.plurals.settings_trakt_queue_undeliverable, counts.undeliverable, counts.undeliverable)
            QueueProblem(
                title = title,
                description = stringResource(Res.string.settings_trakt_queue_undeliverable_description),
            ) {
                if (counts.retryable > 0) {
                    QueueTextButton(
                        label = stringResource(Res.string.settings_trakt_queue_try_again),
                        target = title,
                        enabled = !syncing,
                        onClick = queue::retryUndeliverable,
                    )
                }
                QueueTextButton(
                    label = stringResource(Res.string.settings_trakt_queue_dismiss),
                    target = title,
                    enabled = true,
                    onClick = queue::dismissUndeliverable,
                )
            }
        }
        if (counts.droppedForAge > 0) {
            val title = pluralStringResource(Res.plurals.settings_trakt_queue_dropped, counts.droppedForAge, counts.droppedForAge)
            QueueProblem(
                title = title,
                description = stringResource(Res.string.settings_trakt_queue_dropped_description),
            ) {
                QueueTextButton(
                    label = stringResource(Res.string.settings_trakt_queue_dismiss),
                    target = title,
                    enabled = true,
                    onClick = queue::dismissDropped,
                )
            }
        }
    }
}

/** The sentence that tells what a "Sync now" did. */
@Composable
internal fun queueResultText(result: QueueSyncResult): String {
    val refused = if (result.newlyRefused > 0) {
        pluralStringResource(Res.plurals.settings_trakt_queue_result_refused, result.newlyRefused, result.newlyRefused)
    } else {
        null
    }
    val main = when {
        result.stillWaiting > 0 && result.offline -> stringResource(Res.string.settings_trakt_queue_result_offline)
        result.stillWaiting > 0 && result.sent > 0 ->
            stringResource(Res.string.settings_trakt_queue_result_partial, result.sent, result.stillWaiting)
        result.stillWaiting > 0 && result.retryInMs != null -> {
            val minutes = ((result.retryInMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
            pluralStringResource(Res.plurals.settings_trakt_queue_result_retry_in, minutes, minutes)
        }
        result.stillWaiting > 0 -> stringResource(Res.string.settings_trakt_queue_result_not_accepted)
        result.sent > 0 ->
            pluralStringResource(Res.plurals.settings_trakt_queue_result_all_sent, result.sent, result.sent)
        refused != null -> null
        else -> stringResource(Res.string.settings_trakt_queue_result_nothing_waiting)
    }
    return listOfNotNull(main, refused).joinToString(" ")
}

@Composable
private fun QueueProblem(
    title: String,
    description: String,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.76f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

/** Named by what it acts on, so two Dismiss buttons are told apart by assistive technology. */
@Composable
private fun QueueTextButton(
    label: String,
    target: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val named = "$label, $target"
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = named },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.38f),
        ),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

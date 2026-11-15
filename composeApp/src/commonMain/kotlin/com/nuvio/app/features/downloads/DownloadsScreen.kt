package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.network.NetworkStatusRepository
import kotlinx.coroutines.delay
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    initialShowId: String? = null,
    onNavigateToShow: ((showId: String, title: String) -> Unit)? = null,
    onBackFromShow: (() -> Unit)? = null,
    showAutoDownloads: Boolean = isDesktop,
    /**
     * Opens the list of sources for the title of a download, so that another one can be picked.
     * Null where the screen cannot reach it: the action is then not offered.
     */
    onChooseAnotherSource: ((DownloadItem) -> Unit)? = null,
    openDownloadsFolder: () -> Boolean = DownloadsPlatformDownloader::openDownloadsDirectory,
    /** Takes the user to where titles are found, from the empty state. Null where the screen cannot: the action is not offered. */
    onBrowse: (() -> Unit)? = null,
    /** Whether the empty state mentions downloading whole seasons and auto-download. Only where those exist. */
    showSeasonHint: Boolean = showAutoDownloads,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.completedItems.size) {
        DownloadsRepository.markCompletedSeen()
    }
    val network by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    val anyDownloading = uiState.items.any { it.status == DownloadStatus.Downloading }
    // Read again every few seconds while something downloads, so a transfer that went silent shows it.
    val now by produceState(initialValue = DownloadsClock.nowEpochMs(), anyDownloading) {
        value = DownloadsClock.nowEpochMs()
        while (anyDownloading) {
            delay(StatusRefreshMs)
            value = DownloadsClock.nowEpochMs()
        }
    }
    val clock = RowClock(nowEpochMs = now, offline = network.isOfflineLike)

    // The last run of each season, movie set and movie downloaded in one go, kept until dismissed.
    val batchRunProfileId = ProfileRepository.activeProfileId
    val batchRunRevision by BatchRunSummaries.revision.collectAsStateWithLifecycle()
    val batchRuns = remember(batchRunProfileId, batchRunRevision) { BatchRunSummaries.load(batchRunProfileId) }

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var downloadPendingDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)

    val completedEpisodes = remember(uiState.items) {
        uiState.completedItems
            .filter { it.isEpisode }
            .sortedForSeriesDownloads()
    }

    val selectedShowTitle = remember(selectedShowId, completedEpisodes) {
        selectedShowId?.let { showId ->
            completedEpisodes.firstOrNull { it.parentMetaId == showId }?.title
        }
    }

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = if (selectedShowId == null) {
                    stringResource(Res.string.compose_settings_root_downloads_title)
                } else {
                    selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
                },
                onBack = {
                    if (selectedShowId != null) {
                        onBackFromShow?.invoke() ?: run { selectedShowId = null }
                    } else {
                        onBack()
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!openDownloadsFolder()) {
                                NuvioToastController.show(openDownloadsDirectoryFailedText)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = stringResource(Res.string.downloads_open_directory),
                        )
                    }
                },
            )
        }

        if (selectedShowId == null) {
            if (showAutoDownloads) {
                item { AutoDownloadSection() }
            }
            batchRunSummaries(
                runs = batchRuns,
                onChooseAnotherSource = onChooseAnotherSource,
                onDismiss = { key -> BatchRunSummaries.dismiss(batchRunProfileId, key) },
            )
            downloadsRootContent(
                uiState = uiState,
                clock = clock,
                onChooseAnotherSource = onChooseAnotherSource,
                onOpenDownloadsFolder = {
                    if (!openDownloadsFolder()) NuvioToastController.show(openDownloadsDirectoryFailedText)
                },
                showSeasonHint = showSeasonHint,
                onBrowse = onBrowse,
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                },
                onDeleteDownload = { downloadPendingDeletionId = it },
            )
        } else {
            downloadsShowContent(
                showId = selectedShowId.orEmpty(),
                episodes = completedEpisodes,
                clock = clock,
                onOpenDownload = onOpenDownload,
                onDeleteDownload = { downloadPendingDeletionId = it },
            )
        }
    }

    val pendingDeletion = downloadPendingDeletionId?.let { id -> uiState.items.firstOrNull { it.id == id } }
    val deleteNotMovedText = stringResource(Res.string.downloads_delete_not_moved_toast)
    if (pendingDeletion != null) {
        val hasFile = pendingDeletion.status == DownloadStatus.Completed
        val usesTrash = DownloadsRepository.usesSystemTrash
        NuvioStatusModal(
            title = stringResource(Res.string.downloads_delete_title, pendingDeletion.nameForLabels()),
            message = stringResource(
                when {
                    !hasFile -> Res.string.downloads_delete_message_partial
                    usesTrash -> Res.string.downloads_delete_message_trash
                    else -> Res.string.downloads_delete_message_held
                },
            ),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(
                when {
                    !hasFile -> Res.string.downloads_delete_confirm_partial
                    usesTrash -> Res.string.downloads_delete_confirm_trash
                    else -> Res.string.action_delete
                },
            ),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                downloadPendingDeletionId = null
                if (DownloadsRepository.deleteDownload(pendingDeletion.id) == DownloadDeleteResult.NotMoved) {
                    NuvioToastController.show(deleteNotMovedText)
                }
            },
            onDismiss = { downloadPendingDeletionId = null },
        )
    }
}

private const val StatusRefreshMs = 5_000L

/** The time and network state that the rows work out their status from. */
private data class RowClock(val nowEpochMs: Long, val offline: Boolean)

private fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    clock: RowClock,
    onChooseAnotherSource: ((DownloadItem) -> Unit)?,
    onOpenDownloadsFolder: () -> Unit,
    showSeasonHint: Boolean,
    onBrowse: (() -> Unit)?,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onDeleteDownload: (String) -> Unit,
) {
    uiState.storeProblem?.let { problem ->
        item(key = "store-problem") {
            StoreProblemBanner(problem = problem, onOpenDownloadsFolder = onOpenDownloadsFolder)
        }
    }

    val activeItems = uiState.activeItems
    val completedMovies = uiState.completedItems.filterNot(DownloadItem::isEpisode)
    val completedShows = uiState.completedItems
        .filter(DownloadItem::isEpisode)
        .groupBy { it.parentMetaId }
        .mapNotNull { (_, episodes) ->
            episodes.firstOrNull()?.let { first ->
                first to episodes
            }
        }
        .sortedBy { (item, _) -> item.title.lowercase() }

    if (activeItems.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_active))
        }
        val failedCount = activeItems.count { it.status == DownloadStatus.Failed }
        if (failedCount > 0) {
            item(key = "failed-actions") {
                FailedActions(
                    failedCount = failedCount,
                    retryableCount = activeItems.count { it.status == DownloadStatus.Failed && !it.needsAnotherSource },
                )
            }
        }
        items(
            items = activeItems,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                clock = clock,
                onChooseAnotherSource = onChooseAnotherSource,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }

    if (completedMovies.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_movies))
        }
        items(
            items = completedMovies,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                clock = clock,
                onChooseAnotherSource = onChooseAnotherSource,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }

    if (completedShows.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_shows))
        }
        items(
            items = completedShows,
            key = { (item, _) -> item.parentMetaId },
        ) { (item, episodes) ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable(role = Role.Button) { onOpenShow(item.parentMetaId, item.title) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(Res.string.downloads_episode_count, episodes.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // A list that could not be read is not an empty one: the banner says so instead.
    if (uiState.items.isEmpty() && uiState.storeProblem == null) {
        item {
            EmptyDownloads(showSeasonHint = showSeasonHint, onBrowse = onBrowse)
        }
    }
}

private fun LazyListScope.downloadsShowContent(
    showId: String,
    episodes: List<DownloadItem>,
    clock: RowClock,
    onOpenDownload: (DownloadItem) -> Unit,
    onDeleteDownload: (String) -> Unit,
) {
    val showEpisodes = episodes
        .filter { it.parentMetaId == showId }
        .sortedForSeriesDownloads()

    val seasons = showEpisodes
        .groupBy { it.seasonNumber ?: 0 }
        .toList()
        .sortedWith(
            compareBy<Pair<Int, List<DownloadItem>>> { (season, _) ->
                if (season == 0) 0 else 1
            }.thenBy { (season, _) -> if (season == 0) 0 else season },
        )

    if (seasons.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    seasons.forEach { (seasonNumber, entries) ->
        item {
            SectionTitle(
                if (seasonNumber == 0) {
                    stringResource(Res.string.episodes_specials)
                } else {
                    stringResource(Res.string.episodes_season, seasonNumber)
                },
            )
        }

        val sortedEpisodes = entries.sortedForSeriesDownloads()

        items(
            items = sortedEpisodes,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                clock = clock,
                onChooseAnotherSource = null,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    clock: RowClock,
    onChooseAnotherSource: ((DownloadItem) -> Unit)?,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val rowStatus = downloadRowStatus(item, clock.nowEpochMs, clock.offline)
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(
        item = item,
        displayTitle = displayTitle,
    )

    val name = item.nameForLabels()
    val announcement = stringResource(
        Res.string.downloads_announce_row,
        name,
        if (rowStatus.phase == DownloadPhase.Failed) {
            stringResource(Res.string.downloads_status_failed_reason, failureReasonText(item))
        } else {
            stringResource(rowStatus.phase.announcement)
        },
    )
    val pauseDescription = stringResource(Res.string.downloads_pause_description, name)
    val resumeDescription = stringResource(Res.string.downloads_resume_description, name)
    val playDescription = stringResource(Res.string.downloads_play_description, name)
    val deleteDescription = stringResource(Res.string.downloads_delete_description, name)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .then(
                // One item for a screen reader: title, subtitle and status read together. Its own
                // buttons stay apart. A row that can be played is a button; one that cannot is not.
                if (item.isPlayable) {
                    Modifier.clickable(role = Role.Button, onClick = onOpen)
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = displaySubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText(item, rowStatus),
                        // Announced when the phase changes. The description has no sizes, so the
                        // bytes arriving do not make it speak again.
                        modifier = Modifier.semantics {
                            contentDescription = announcement
                            liveRegion = LiveRegionMode.Polite
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    resultText(rowStatus)?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (item.status) {
                        DownloadStatus.Downloading -> {
                            IconButton(onClick = onPause) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = pauseDescription,
                                )
                            }
                        }
                        DownloadStatus.Paused -> {
                            IconButton(onClick = onResume) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = resumeDescription,
                                )
                            }
                        }
                        DownloadStatus.Failed -> Unit
                        DownloadStatus.Completed -> {
                            IconButton(onClick = onOpen) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = playDescription,
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = deleteDescription,
                        )
                    }
                }
            }

            if (item.status == DownloadStatus.Failed) {
                FailureDetails(
                    item = item,
                    onRetry = onRetry,
                    onChooseAnotherSource = onChooseAnotherSource,
                )
            }

            if (item.status == DownloadStatus.Downloading) {
                if (item.totalBytes != null && item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = item.progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun DownloadItem.displayTitle(): String =
    if (isEpisode) {
        episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }

@Composable
private fun downloadDisplaySubtitle(
    item: DownloadItem,
    displayTitle: String,
): String {
    val seasonNumber = item.seasonNumber
    val episodeNumber = item.episodeNumber
    if (seasonNumber == null || episodeNumber == null) {
        return item.displaySubtitle
    }

    val episodeCode = stringResource(
        Res.string.compose_player_episode_code_full,
        seasonNumber,
        episodeNumber,
    )
    return listOf(
        episodeCode,
        item.episodeTitle?.trim().orEmpty().takeIf { it.isNotBlank() && it != displayTitle },
        item.title.trim().takeIf { it.isNotBlank() && it != displayTitle },
    ).filterNotNull().joinToString(" • ")
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun statusText(item: DownloadItem, status: DownloadRowStatus): String {
    val size = if (item.status == DownloadStatus.Completed) {
        formatBytes(item.totalBytes ?: item.downloadedBytes)
    } else if (item.totalBytes != null && item.totalBytes > 0L) {
        "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
    } else {
        formatBytes(item.downloadedBytes)
    }

    val line = when (status.phase) {
        DownloadPhase.Failed -> stringResource(Res.string.downloads_status_failed_reason, failureReasonText(item))
        else -> if (status.phase.showsSize) {
            stringResource(status.phase.message, size)
        } else {
            stringResource(status.phase.message)
        }
    }
    return if (status.isAuto) {
        stringResource(Res.string.downloads_status_auto) + " • " + line
    } else {
        line
    }
}

@Composable
private fun failureReasonText(item: DownloadItem): String =
    when (val reason = failureReasonMessage(item)) {
        is FailureText.Resource -> stringResource(reason.resource)
        is FailureText.Literal -> reason.text
    }

/** The next step of a failed row and the buttons that take it. */
@Composable
private fun FailureDetails(
    item: DownloadItem,
    onRetry: () -> Unit,
    onChooseAnotherSource: ((DownloadItem) -> Unit)?,
) {
    val name = item.nameForLabels()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(failureNextStepMessage(item)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onChooseAnotherSource != null) {
                val chooseText = stringResource(Res.string.downloads_choose_another_source)
                val chooseDescription = stringResource(Res.string.downloads_choose_another_source_description, name)
                TextButton(
                    modifier = Modifier.semantics { contentDescription = chooseDescription },
                    onClick = { onChooseAnotherSource(item) },
                ) {
                    Text(text = chooseText, style = MaterialTheme.typography.labelLarge)
                }
            }
            // A bad source is not fetched again without saying so: the button names what it does.
            val retryText = stringResource(
                if (item.needsAnotherSource) Res.string.downloads_try_source_again else Res.string.action_retry,
            )
            val retryDescription = if (item.needsAnotherSource) {
                stringResource(Res.string.downloads_try_source_again_description, name)
            } else {
                stringResource(Res.string.downloads_retry_description, name)
            }
            TextButton(
                modifier = Modifier.semantics { contentDescription = retryDescription },
                onClick = onRetry,
            ) {
                Text(text = retryText, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The empty list: what to do to get a download, and where to start. */
@Composable
private fun EmptyDownloads(showSeasonHint: Boolean, onBrowse: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.downloads_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showSeasonHint) {
                Text(
                    text = stringResource(Res.string.downloads_empty_hint_seasons),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onBrowse != null) {
            TextButton(onClick = onBrowse) {
                Text(text = stringResource(Res.string.downloads_empty_browse), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Says that the stored list could not be read, what was kept, and offers the downloads folder, where the files still are. */
@Composable
private fun StoreProblemBanner(problem: DownloadsStoreProblem, onOpenDownloadsFolder: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_store_problem_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    when (problem) {
                        DownloadsStoreProblem.EarlierCopyShown -> Res.string.downloads_store_problem_earlier_copy
                        DownloadsStoreProblem.StartedEmpty -> Res.string.downloads_store_problem_started_empty
                        DownloadsStoreProblem.NotSaving -> Res.string.downloads_store_problem_not_saving
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onOpenDownloadsFolder) {
                Text(
                    text = stringResource(Res.string.downloads_open_directory),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/** Retry all and clear, for a list with failed downloads. */
@Composable
private fun FailedActions(failedCount: Int, retryableCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = pluralStringResource(Res.plurals.downloads_failed_count, failedCount, failedCount),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (retryableCount > 0) {
            TextButton(onClick = { DownloadsRepository.retryAllFailed() }) {
                Text(text = stringResource(Res.string.downloads_retry_all_failed), style = MaterialTheme.typography.labelLarge)
            }
        }
        TextButton(onClick = { DownloadsRepository.clearFailed() }) {
            Text(text = stringResource(Res.string.downloads_clear_failed), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** What was checked about a finished file, or null for any other row. */
@Composable
private fun resultText(status: DownloadRowStatus): String? {
    val result = status.result ?: return null
    val message = stringResource(result.message)
    return if (status.pictureChecked) {
        message + " • " + stringResource(Res.string.downloads_result_picture_ok)
    } else {
        message
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.LocalOpenSettingsPage
import com.nuvio.app.features.settings.SettingsPage
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
    /** Called before a download the user deletes is removed, so that auto-download does not fetch it again. */
    rememberDeletion: suspend (DownloadItem) -> Unit = AutoDownloads::rememberUserDeletion,
    /** Opens the page of a show, from its auto-download rule. Null where the screen cannot: the action is not offered. */
    onOpenShowDetails: ((type: String, id: String, title: String) -> Unit)? = null,
    /** Links from an episode download to its page, season and show. Null where they are not offered. */
    episodeLinks: DownloadEpisodeLinks? = null,
) {
    DownloadsScreenContent(
        onBack = onBack,
        onOpenDownload = onOpenDownload,
        initialShowId = initialShowId,
        onNavigateToShow = onNavigateToShow,
        onBackFromShow = onBackFromShow,
        showAutoDownloads = showAutoDownloads,
        onChooseAnotherSource = onChooseAnotherSource,
        openDownloadsFolder = openDownloadsFolder,
        onBrowse = onBrowse,
        showSeasonHint = showSeasonHint,
        rememberDeletion = rememberDeletion,
        onOpenShowDetails = onOpenShowDetails,
        episodeLinks = episodeLinks,
    )
}

@Composable
internal fun DownloadsScreenContent(
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
    /** Called before a download the user deletes is removed, so that auto-download does not fetch it again. */
    rememberDeletion: suspend (DownloadItem) -> Unit = AutoDownloads::rememberUserDeletion,
    onOpenShowDetails: ((type: String, id: String, title: String) -> Unit)? = null,
    episodeLinks: DownloadEpisodeLinks? = null,
    /** Where the episodes that are about to be deleted after watching come from. Null where there is no auto-download. */
    autoDownloads: AutoDownloadController? = if (showAutoDownloads) AutoDownloads else null,
    /** Where downloads live. Where there are no locations the move actions and location notes are not shown. */
    folders: DownloadFolderController = DownloadFolders.controller,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.completedItems.size) {
        DownloadsRepository.markCompletedSeen()
    }
    val network by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    val scheduledRemovals by (autoDownloads?.removals ?: NoScheduledRemovals).collectAsStateWithLifecycle()
    val removalByDownload = remember(scheduledRemovals) { scheduledRemovals.associateBy { it.downloadId } }
    val activeProfileId = ProfileRepository.activeProfileId
    val anyDownloading = uiState.items.any { it.status == DownloadStatus.Downloading }
    val needsClock = anyDownloading || removalByDownload.isNotEmpty()
    // Read again every few seconds while something downloads, so a transfer that went silent shows it,
    // and while a deletion is waiting, so the hours left stay right.
    val now by produceState(initialValue = DownloadsClock.nowEpochMs(), needsClock) {
        value = DownloadsClock.nowEpochMs()
        while (needsClock) {
            delay(StatusRefreshMs)
            value = DownloadsClock.nowEpochMs()
        }
    }
    val folderState by folders.state.collectAsStateWithLifecycle()
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var moveRequest by remember { mutableStateOf<MoveRequest?>(null) }
    var chooseLocationFor by remember { mutableStateOf<DownloadItem?>(null) }
    var permanentDeletionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(folders) { if (folders.supported) folders.refresh() }
    val extras = if (folders.supported) {
        RowExtras(
            folderState = folderState,
            locationIdOf = folders::locationIdOf,
            selecting = selecting,
            selected = selectedIds,
            onToggleSelected = { ids ->
                selectedIds = if (ids.all { it in selectedIds }) selectedIds - ids else selectedIds + ids
            },
            onMove = { ids -> moveRequest = MoveRequest.MoveItems(ids) },
            onChooseLocation = { chooseLocationFor = it },
        )
    } else {
        null
    }
    val clock = RowClock(nowEpochMs = now, offline = network.isOfflineLike, extras = extras)

    // The last run of each season, movie set and movie downloaded in one go, kept until dismissed.
    val batchRunProfileId = ProfileRepository.activeProfileId
    val batchRunRevision by BatchRunSummaries.revision.collectAsStateWithLifecycle()
    val batchRuns = remember(batchRunProfileId, batchRunRevision) { BatchRunSummaries.load(batchRunProfileId) }

    // The speed limit, when one is set, so it is not a surprise why downloads are slow.
    val speedLimitBytes by remember {
        DownloadBandwidth.applyStoredLimit()
        DownloadBandwidth.userCapBytesPerSecond
    }.collectAsStateWithLifecycle()
    val openSettingsPage = LocalOpenSettingsPage.current

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var downloadPendingDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)
    val deleteScope = rememberCoroutineScope()

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
                    if (extras != null && uiState.completedItems.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selecting = !selecting
                                selectedIds = emptySet()
                            },
                        ) {
                            Text(
                                text = stringResource(if (selecting) Res.string.downloads_select_done else Res.string.downloads_select),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
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

        if (extras != null && selecting && selectedIds.isNotEmpty()) {
            item(key = "selection-bar") {
                SelectionBar(count = selectedIds.size, onMove = { moveRequest = MoveRequest.MoveItems(selectedIds) })
            }
        }

        if (selectedShowId == null) {
            speedLimitBytes?.let { limit ->
                item(key = "speed-limit") {
                    SpeedLimitLine(
                        megabytesPerSecond = (limit / 1_000_000L).toInt().coerceAtLeast(1),
                        onChange = openSettingsPage?.let { open -> { open(SettingsPage.Playback) } },
                    )
                }
            }
            if (showAutoDownloads) {
                item {
                    val goToShow = onOpenShowDetails?.let { open ->
                        { rule: AutoDownloadRule -> open(rule.parentMetaType, rule.parentMetaId, rule.title) }
                    }
                    if (autoDownloads != null) {
                        AutoDownloadSection(controller = autoDownloads, onGoToShow = goToShow)
                    } else {
                        AutoDownloadSection(onGoToShow = goToShow)
                    }
                }
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
                episodeLinks = episodeLinks,
                unreachable = folderState.unreachable,
            )
        } else {
            downloadsShowContent(
                showId = selectedShowId.orEmpty(),
                episodes = completedEpisodes,
                clock = clock,
                onOpenDownload = onOpenDownload,
                onDeleteDownload = { downloadPendingDeletionId = it },
                scheduledDeletions = removalByDownload,
                onKeepScheduled = { autoDownloads?.keepDownload(activeProfileId, it) },
                episodeLinks = episodeLinks,
            )
        }
    }

    val pendingDeletion = downloadPendingDeletionId?.let { id -> uiState.items.firstOrNull { it.id == id } }
    val deleteNotMovedText = stringResource(Res.string.downloads_delete_not_moved_toast)
    val deleteNotConnectedText = stringResource(Res.string.downloads_delete_not_connected_toast)
    val permanentDeletion = permanentDeletionId?.let { id -> uiState.items.firstOrNull { it.id == id } }
    if (permanentDeletion != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.downloads_delete_permanent_title, permanentDeletion.nameForLabels()),
            message = stringResource(Res.string.downloads_delete_not_moved_toast) + " " + stringResource(Res.string.downloads_delete_permanent_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.downloads_delete_permanent_confirm),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                permanentDeletionId = null
                deleteScope.launch {
                    if (DownloadsRepository.deleteDownload(permanentDeletion.id, permanently = true) == DownloadDeleteResult.NotMoved) {
                        NuvioToastController.show(deleteNotMovedText)
                    }
                }
            },
            onDismiss = { permanentDeletionId = null },
        )
    }
    moveRequest?.let { active ->
        DownloadMoveFlow(
            request = active,
            controller = folders,
            items = uiState.items,
            onFinished = {
                moveRequest = null
                selecting = false
                selectedIds = emptySet()
            },
        )
    }
    chooseLocationFor?.let { waiting ->
        DestinationDialog(
            locations = folderState.locations.filter { it.availability == FolderAvailability.Available },
            excludeIds = emptySet(),
            onPickLocation = { location ->
                chooseLocationFor = null
                DownloadsRepository.chooseLocationForWaiting(waiting.id, location.id)
            },
            onPickOther = null,
            onDismiss = { chooseLocationFor = null },
        )
    }
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
            ) + if (pendingDeletion.autoDownloadRuleKey != null) {
                " " + stringResource(Res.string.downloads_delete_auto_note)
            } else {
                ""
            },
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
                deleteScope.launch {
                    rememberDeletion(pendingDeletion)
                    when (DownloadsRepository.deleteDownload(pendingDeletion.id)) {
                        // Say so, and offer to delete it for good, which needs its own confirmation.
                        DownloadDeleteResult.NotMoved -> {
                            NuvioToastController.show(deleteNotMovedText)
                            permanentDeletionId = pendingDeletion.id
                        }
                        DownloadDeleteResult.NotReachable -> NuvioToastController.show(deleteNotConnectedText)
                        DownloadDeleteResult.Removed, DownloadDeleteResult.Gone -> Unit
                    }
                }
            },
            onDismiss = { downloadPendingDeletionId = null },
        )
    }
}

private const val StatusRefreshMs = 5_000L

private val NoScheduledRemovals = kotlinx.coroutines.flow.MutableStateFlow(emptyList<AutoDownloadRemoval>())

/** The time and network state that the rows work out their status from. */
private data class RowClock(val nowEpochMs: Long, val offline: Boolean, val extras: RowExtras? = null)

/** What the rows need to know about locations, selection and moving. Null where there are no locations. */
private class RowExtras(
    val folderState: DownloadFolderState,
    val locationIdOf: (DownloadItem) -> String,
    val selecting: Boolean,
    val selected: Set<String>,
    val onToggleSelected: (Set<String>) -> Unit,
    val onMove: (Set<String>) -> Unit,
    val onChooseLocation: (DownloadItem) -> Unit,
)

@Composable
private fun SelectionBar(count: Int, onMove: () -> Unit) {
    val moveDescription = stringResource(Res.string.downloads_move_selected_description, count)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.downloads_select_count, count),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.labelLarge,
        )
        TextButton(onClick = onMove, modifier = Modifier.semantics { contentDescription = moveDescription }) {
            Text(stringResource(Res.string.downloads_move_selected), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Tells which locations cannot be reached, and that what is in them is kept. */
@Composable
private fun LocationBanner(unreachable: List<DownloadLocationInfo>) {
    val names = unreachable.joinToString(", ") { it.name }
    val title = stringResource(Res.string.downloads_location_banner_title)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.downloads_location_banner_message, names),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

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
    episodeLinks: DownloadEpisodeLinks?,
    unreachable: List<DownloadLocationInfo> = emptyList(),
) {
    uiState.storeProblem?.let { problem ->
        item(key = "store-problem") {
            StoreProblemBanner(problem = problem, onOpenDownloadsFolder = onOpenDownloadsFolder)
        }
    }
    if (unreachable.isNotEmpty()) {
        item(key = "location-banner") { LocationBanner(unreachable) }
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
                episodeLinks = episodeLinks,
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
            val extras = clock.extras
            val episodeIds = episodes.map { it.id }.toSet()
            val showSelected = extras != null && episodeIds.all { it in extras.selected }
            val selectShowDescription = stringResource(Res.string.downloads_select_show_description, item.title)
            val moveShowDescription = stringResource(Res.string.downloads_move_show_description, item.title)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .then(
                        if (extras != null && extras.selecting) {
                            Modifier
                                .toggleable(value = showSelected, role = Role.Checkbox) { extras.onToggleSelected(episodeIds) }
                                .semantics { contentDescription = selectShowDescription }
                        } else {
                            Modifier.clickable(role = Role.Button) { onOpenShow(item.parentMetaId, item.title) }
                        },
                    ),
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
                    if (extras != null && extras.selecting) {
                        Checkbox(checked = showSelected, onCheckedChange = null)
                    } else {
                        if (extras != null) {
                            TextButton(
                                onClick = { extras.onMove(episodeIds) },
                                modifier = Modifier.semantics { contentDescription = moveShowDescription },
                            ) {
                                Text(stringResource(Res.string.downloads_move_to), style = MaterialTheme.typography.labelLarge)
                            }
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
    scheduledDeletions: Map<String, AutoDownloadRemoval>,
    onKeepScheduled: (downloadId: String) -> Unit,
    episodeLinks: DownloadEpisodeLinks?,
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
                scheduledDeletionDueAtEpochMs = scheduledDeletions[item.id]?.dueAtEpochMs,
                onKeepScheduled = { onKeepScheduled(item.id) },
                episodeLinks = episodeLinks,
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
    /** When finishing the episode scheduled its deletion: the time it is due. Null otherwise. */
    scheduledDeletionDueAtEpochMs: Long? = null,
    onKeepScheduled: () -> Unit = {},
    episodeLinks: DownloadEpisodeLinks? = null,
) {
    val extras = clock.extras
    val location = extras?.folderState?.location(extras.locationIdOf(item))
    val availability = location?.availability ?: FolderAvailability.Available
    val reachable = availability == FolderAvailability.Available
    val rowStatus = downloadRowStatus(item, clock.nowEpochMs, clock.offline, availability)
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
    val moveDescription = stringResource(Res.string.downloads_move_description, name)
    val chooseLocationDescription = stringResource(Res.string.downloads_choose_location_description, name)
    val selectable = extras != null && extras.selecting && item.status == DownloadStatus.Completed
    val selectedHere = extras != null && item.id in extras.selected
    val selectDescription = stringResource(Res.string.downloads_select_description, name)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .then(
                // One item for a screen reader: title, subtitle and status read together. Its own
                // buttons stay apart. A row that can be played is a button; one that cannot is not.
                if (selectable && extras != null) {
                    Modifier
                        .toggleable(value = selectedHere, role = Role.Checkbox) { extras.onToggleSelected(setOf(item.id)) }
                        .semantics { contentDescription = selectDescription }
                } else if (item.isPlayable && reachable) {
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
                    if (extras != null && extras.folderState.hasSeveralLocations && location != null) {
                        Text(
                            text = stringResource(Res.string.downloads_row_location, location.name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    resultText(rowStatus)?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (scheduledDeletionDueAtEpochMs != null) {
                        val hours = removalHoursLeft(scheduledDeletionDueAtEpochMs, clock.nowEpochMs)
                        val keepDescription = stringResource(
                            Res.string.downloads_auto_keep_description,
                            item.title,
                            item.seasonNumber ?: 0,
                            item.episodeNumber ?: 0,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (hours < 1) {
                                    stringResource(Res.string.downloads_row_deletes_soon)
                                } else {
                                    pluralStringResource(Res.plurals.downloads_row_deletes_in_hours, hours, hours)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                modifier = Modifier.semantics { contentDescription = keepDescription },
                                onClick = onKeepScheduled,
                            ) {
                                Text(text = stringResource(Res.string.downloads_auto_keep), style = MaterialTheme.typography.labelLarge)
                            }
                        }
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
                            if (selectable) {
                                Checkbox(checked = selectedHere, onCheckedChange = null)
                            } else {
                                if (extras != null && reachable) {
                                    TextButton(
                                        onClick = { extras.onMove(setOf(item.id)) },
                                        modifier = Modifier.semantics { contentDescription = moveDescription },
                                    ) {
                                        Text(stringResource(Res.string.downloads_move_to), style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                                IconButton(onClick = onOpen, enabled = reachable) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = playDescription,
                                    )
                                }
                            }
                        }
                    }
                    if (extras != null && item.status == DownloadStatus.Paused && item.pausedByFolder) {
                        TextButton(
                            onClick = { extras.onChooseLocation(item) },
                            modifier = Modifier.semantics { contentDescription = chooseLocationDescription },
                        ) {
                            Text(stringResource(Res.string.downloads_choose_location), style = MaterialTheme.typography.labelLarge)
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

            if (episodeLinks != null && item.isEpisode) {
                EpisodeLinkButtons(item = item, links = episodeLinks)
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

/**
 * Where the links of an episode download lead. Each one opens a page of the show the episode
 * belongs to; the screen only offers them where this is given.
 */
class DownloadEpisodeLinks(
    val onEpisodeDetails: (DownloadItem) -> Unit,
    val onGoToSeason: (DownloadItem) -> Unit,
    val onGoToShow: (DownloadItem) -> Unit,
)

/** "Episode details", "Go to season" and "Go to show" of an episode download, each with a label that names the episode. */
@Composable
private fun EpisodeLinkButtons(
    item: DownloadItem,
    links: DownloadEpisodeLinks,
) {
    val name = item.nameForLabels()
    val detailsDescription = stringResource(Res.string.downloads_episode_details_description, name)
    val seasonDescription = stringResource(Res.string.downloads_go_to_season_description, name)
    val showDescription = stringResource(Res.string.downloads_go_to_show_description, name)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            modifier = Modifier.semantics { contentDescription = detailsDescription },
            onClick = { links.onEpisodeDetails(item) },
        ) {
            Text(text = stringResource(Res.string.nav_episode_details), style = MaterialTheme.typography.labelLarge)
        }
        if (item.seasonNumber != null) {
            TextButton(
                modifier = Modifier.semantics { contentDescription = seasonDescription },
                onClick = { links.onGoToSeason(item) },
            ) {
                Text(text = stringResource(Res.string.nav_go_to_season), style = MaterialTheme.typography.labelLarge)
            }
        }
        TextButton(
            modifier = Modifier.semantics { contentDescription = showDescription },
            onClick = { links.onGoToShow(item) },
        ) {
            Text(text = stringResource(Res.string.downloads_auto_go_to_show), style = MaterialTheme.typography.labelLarge)
        }
    }
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

/** "Speed limit: 5 MB/s", with a link to the setting where it can be changed. */
@Composable
private fun SpeedLimitLine(megabytesPerSecond: Int, onChange: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                Res.string.downloads_speed_limit_line,
                stringResource(Res.string.settings_download_speed_limit_value, megabytesPerSecond),
            ),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onChange != null) {
            val description = stringResource(Res.string.downloads_speed_limit_change_description)
            TextButton(
                modifier = Modifier.semantics { contentDescription = description },
                onClick = onChange,
            ) {
                Text(text = stringResource(Res.string.downloads_speed_limit_change), style = MaterialTheme.typography.labelLarge)
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

internal fun formatBytes(bytes: Long): String {
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

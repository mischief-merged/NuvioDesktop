package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The last run of each season, movie set and movie that was downloaded in one go, kept until it is
 * dismissed: how many were added, were already there, were not found or failed, and which ones were
 * not found or failed, each with a way to choose another source where [onChooseAnotherSource] gives one.
 */
internal fun LazyListScope.batchRunSummaries(
    runs: List<BatchRunRecord>,
    onChooseAnotherSource: ((DownloadItem) -> Unit)?,
    onDismiss: (key: String) -> Unit,
) {
    if (runs.isEmpty()) return
    item(key = "batch-runs-heading") {
        Text(
            text = stringResource(Res.string.downloads_runs_heading),
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
    items(items = runs, key = { "batch-run-${it.key}" }) { run ->
        BatchRunCard(run = run, onChooseAnotherSource = onChooseAnotherSource, onDismiss = { onDismiss(run.key) })
    }
}

@Composable
private fun BatchRunCard(
    run: BatchRunRecord,
    onChooseAnotherSource: ((DownloadItem) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val title = batchRunTitle(run)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = batchRunCounts(run),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val dismissDescription = stringResource(Res.string.downloads_run_dismiss_description, title)
                TextButton(
                    modifier = Modifier.semantics { contentDescription = dismissDescription },
                    onClick = onDismiss,
                ) {
                    Text(text = stringResource(Res.string.downloads_run_dismiss), style = MaterialTheme.typography.labelLarge)
                }
            }
            run.problems.forEach { problem ->
                BatchRunProblemRow(
                    problem = problem,
                    finishedAtEpochMs = run.finishedAtEpochMs,
                    onChooseAnotherSource = onChooseAnotherSource,
                )
            }
            if (run.problemsOmitted > 0) {
                Text(
                    text = stringResource(Res.string.downloads_run_more, run.problemsOmitted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BatchRunProblemRow(
    problem: BatchRunProblem,
    finishedAtEpochMs: Long,
    onChooseAnotherSource: ((DownloadItem) -> Unit)?,
) {
    val name = batchRunProblemName(problem)
    val reason = stringResource(
        when (problem.kind) {
            BatchRunProblemKind.NotFound -> Res.string.downloads_run_problem_not_found
            BatchRunProblemKind.Failed -> Res.string.downloads_run_problem_failed
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(Res.string.downloads_run_problem_row, name, reason),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (onChooseAnotherSource != null) {
            val chooseDescription = stringResource(Res.string.downloads_choose_another_source_description, name)
            TextButton(
                modifier = Modifier.semantics { contentDescription = chooseDescription },
                onClick = { onChooseAnotherSource(problem.asSourceRequest(finishedAtEpochMs)) },
            ) {
                Text(
                    text = stringResource(Res.string.downloads_choose_another_source),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** "Show • Season 2" for a season, the name of the set or movie otherwise. */
@Composable
private fun batchRunTitle(run: BatchRunRecord): String {
    val season = run.seasonNumber ?: return run.title
    val seasonLabel = if (season <= 0) {
        stringResource(Res.string.episodes_specials)
    } else {
        stringResource(Res.string.episodes_season, season)
    }
    return stringResource(Res.string.downloads_run_title_season, run.title, seasonLabel)
}

/** "S1 E4 • Episode title" for an episode, the movie's title for a movie. */
@Composable
private fun batchRunProblemName(problem: BatchRunProblem): String {
    val season = problem.seasonNumber
    val episode = problem.episodeNumber
    if (season == null || episode == null) return problem.title
    val code = stringResource(Res.string.compose_player_episode_code_full, season, episode)
    val episodeTitle = problem.episodeTitle?.trim()?.takeIf { it.isNotBlank() }
    return if (episodeTitle == null) code else "$code • $episodeTitle"
}

/** "3 added, 1 already downloaded, 2 not found, 1 failed", with only the counts that are not zero. */
@Composable
private fun batchRunCounts(run: BatchRunRecord): String {
    val parts = buildList {
        if (run.added > 0) add(stringResource(Res.string.downloads_run_added, run.added))
        if (run.alreadyThere > 0) add(stringResource(Res.string.downloads_run_existing, run.alreadyThere))
        if (run.notFound > 0) add(stringResource(Res.string.downloads_run_not_found, run.notFound))
        if (run.paused > 0) add(stringResource(Res.string.downloads_run_paused, run.paused))
        if (run.failed > 0) add(stringResource(Res.string.downloads_run_failed, run.failed))
    }.joinToString(", ")
    return if (run.stopped) stringResource(Res.string.downloads_run_stopped, parts) else parts
}

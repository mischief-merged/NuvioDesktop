package com.nuvio.app.features.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.EpisodeListSidecar
import com.nuvio.app.features.downloads.SavedEpisodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_showing_saved
import org.jetbrains.compose.resources.stringResource

/**
 * The details of a title built only from what downloads saved: the download records and the
 * episode lists saved beside the files. Used by the show page and the episode page when the real
 * details cannot be loaded. Nothing is invented: a field nobody saved stays empty, the page's own
 * trailers, cast and ratings are absent, and an episode that is listed but not downloaded is marked
 * unavailable so that its page does not offer to play it.
 */
internal object SavedDetails {
    /** Null when the title has no finished download. Reads files, so it is called off the main thread. */
    suspend fun metaFor(type: String, id: String): MetaDetails? = withContext(Dispatchers.Default) {
        val items = DownloadsRepository.uiState.value.items.filter { it.parentMetaId == id && it.status == DownloadStatus.Completed }
        if (items.isEmpty()) return@withContext null
        val lists = items.mapNotNull { item ->
            item.localFileUri?.let { runCatching { EpisodeListSidecar.read(it) }.getOrNull() }
        }
        buildSavedMeta(type, id, items, lists)
    }
}

/** Pure part of [SavedDetails.metaFor]. */
internal fun buildSavedMeta(type: String, id: String, items: List<DownloadItem>, lists: List<SavedEpisodes>): MetaDetails? {
    val first = items.firstOrNull() ?: return null
    val downloaded = items.filter { it.isEpisode }.associateBy { it.seasonNumber!! to it.episodeNumber!! }
    val listed = LinkedHashMap<Pair<Int, Int>, MetaVideo>()
    for (list in lists) for (episode in list.episodes) {
        val key = episode.season to episode.episode
        listed[key] = MetaVideo(
            id = episode.id, title = episode.title, released = episode.released, thumbnail = episode.thumbnail,
            season = episode.season, episode = episode.episode, overview = episode.overview, runtime = episode.runtime,
            available = key in downloaded,
        )
    }
    for ((key, item) in downloaded) {
        val known = listed[key]
        listed[key] = (known ?: MetaVideo(
            id = item.videoId,
            title = item.episodeTitle?.takeIf { it.isNotBlank() } ?: item.displaySubtitle,
            thumbnail = item.episodeThumbnail,
            season = key.first,
            episode = key.second,
        )).copy(available = true, thumbnail = known?.thumbnail ?: item.episodeThumbnail)
    }
    val videos = listed.values.sortedWith(compareBy({ it.season }, { it.episode }))
    val list = lists.firstOrNull()
    return MetaDetails(
        id = id,
        type = type,
        name = list?.showName?.takeIf { it.isNotBlank() } ?: first.title,
        imdbId = list?.imdbId,
        poster = list?.poster ?: first.poster,
        background = first.background,
        logo = first.logo,
        videos = videos,
    )
}

/** The saved details of a title, while [wanted] (the real details are missing). Null until read, and when there are none. */
@Composable
internal fun rememberSavedDetails(type: String, id: String, wanted: Boolean): State<MetaDetails?> =
    produceState<MetaDetails?>(null, type, id, wanted, DownloadsRepository.uiState.value.items) {
        value = if (wanted) SavedDetails.metaFor(type, id) else null
    }

/** The plain line shown over a page built from saved details. */
@Composable
internal fun SavedDetailsNotice(modifier: Modifier = Modifier) {
    val text = stringResource(Res.string.details_showing_saved)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = text,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A trailer is streamed, so the trailers section is offered only with trailers to show and a connection. */
internal fun trailersOffered(trailerCount: Int, offline: Boolean): Boolean = trailerCount > 0 && !offline

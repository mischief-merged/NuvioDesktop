package com.nuvio.app.features.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.parseRuntimeMinutes
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.isUnreleased

/** The season number under which a batch of movies runs. Shows use it for specials, with other ids. */
internal const val MovieBatchSeasonNumber = 0

/** True for a request that downloads one movie, which is reported differently from a season. */
internal val SeasonBatchRequest.isSingleMovie: Boolean
    get() = episodes.size == 1 && episodes.single().movie != null

/**
 * A request that downloads this movie with [choices]: a batch of one item, so it is looked up,
 * queued, retried with the next matching source and limited exactly like an episode of a season.
 * The bitrate limit uses the movie's runtime.
 */
internal fun MetaDetails.movieBatchRequest(choices: SeasonDownloadDefaults): SeasonBatchRequest =
    SeasonBatchRequest(
        contentType = type,
        parentMetaId = id,
        parentMetaType = type,
        title = name,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = MovieBatchSeasonNumber,
        episodes = listOf(
            SeasonBatchEpisode(
                videoId = id,
                seasonNumber = MovieBatchSeasonNumber,
                episodeNumber = 1,
                title = null,
                thumbnail = null,
                runtimeMinutes = parseRuntimeMinutes(runtime)?.takeIf { it > 0 },
                movie = SeasonBatchMovie(
                    metaId = id,
                    metaType = type,
                    title = name,
                    logo = logo,
                    poster = poster,
                    background = background,
                ),
            ),
        ),
        quality = choices.quality,
        maxBitrateMbps = choices.maxBitrateMbps,
        audioLanguage = choices.audioLanguage,
    )

/** True for a request that downloads several movies of a set. */
internal val SeasonBatchRequest.isMovieSet: Boolean
    get() = !isSingleMovie && episodes.isNotEmpty() && episodes.all { it.movie != null }

/**
 * The id a set of movies runs under: the same on every page that shows the set, and different for
 * two sets that happen to share a name. It is made from the ids of the movies of the whole set.
 */
internal fun movieSetBatchId(movies: List<MetaPreview>): String =
    "movie-set:${movies.map { it.id.trim() }.sorted().firstOrNull().orEmpty()}"

/** Whether a movie of a set can be chosen for download. */
internal enum class MovieSetEntryState {
    Available,

    /** Downloaded, downloading or paused already. */
    InDownloads,
    Unreleased,
}

internal data class MovieSetEntry(
    val movie: MetaPreview,
    val state: MovieSetEntryState,
)

/** The movies of a set as the download dialog lists them, in the order of the set. */
internal fun movieSetEntries(
    movies: List<MetaPreview>,
    downloads: List<DownloadItem>,
    todayIsoDate: String,
    nowEpochMs: Long,
): List<MovieSetEntry> {
    // A finished download that is too small to be a movie is a placeholder clip and does not count.
    val inDownloads = downloads
        .filter { !it.isEpisode && it.status != DownloadStatus.Failed }
        .filterNot { it.status == DownloadStatus.Completed && (it.isPlaceholder || it.finishedSizeBytes < minPlausibleVideoBytes()) }
        .mapTo(mutableSetOf()) { it.parentMetaId.trim() }
    return movies.distinctBy { it.id }.map { movie ->
        MovieSetEntry(
            movie = movie,
            state = when {
                movie.id.trim() in inDownloads -> MovieSetEntryState.InDownloads
                movie.isUnreleased(todayIsoDate, nowEpochMs) -> MovieSetEntryState.Unreleased
                else -> MovieSetEntryState.Available
            },
        )
    }
}

/**
 * Which movies of a set are ticked in the download dialog. Every movie that can be downloaded
 * starts ticked; the others can never be ticked.
 */
internal class MovieSetSelection(val entries: List<MovieSetEntry>) {
    private val selectable = entries.filter { it.state == MovieSetEntryState.Available }.map { it.movie.id }.toSet()

    var selectedIds: Set<String> by mutableStateOf(selectable)
        private set

    /** The ticked movies, in the order of the set. */
    val selectedMovies: List<MetaPreview>
        get() = entries.map { it.movie }.filter { it.id in selectedIds }

    fun isSelected(movie: MetaPreview): Boolean = movie.id in selectedIds

    fun toggle(movie: MetaPreview) {
        if (movie.id !in selectable) return
        selectedIds = if (movie.id in selectedIds) selectedIds - movie.id else selectedIds + movie.id
    }

    fun selectAll() {
        selectedIds = selectable
    }

    fun clear() {
        selectedIds = emptySet()
    }
}

/**
 * A request that downloads [movies] of the set called [setName] with [choices], one item per movie,
 * so the set is queued like a season. [setId] is the [movieSetBatchId] of the whole set. A preview carries no runtime the bitrate limit could use
 * unless its runtime text can be read.
 */
internal fun movieSetBatchRequest(
    setId: String,
    setName: String,
    movies: List<MetaPreview>,
    choices: SeasonDownloadDefaults,
): SeasonBatchRequest =
    SeasonBatchRequest(
        contentType = "movie",
        parentMetaId = setId,
        parentMetaType = "movie",
        title = setName,
        logo = null,
        poster = null,
        background = null,
        seasonNumber = MovieBatchSeasonNumber,
        episodes = movies.mapIndexed { index, movie ->
            SeasonBatchEpisode(
                videoId = movie.id,
                seasonNumber = MovieBatchSeasonNumber,
                episodeNumber = index + 1,
                title = null,
                thumbnail = null,
                runtimeMinutes = parseRuntimeMinutes(movie.runtime)?.takeIf { it > 0 },
                movie = SeasonBatchMovie(
                    metaId = movie.id,
                    metaType = movie.type,
                    title = movie.name,
                    logo = movie.logo,
                    poster = movie.poster,
                    background = movie.banner,
                ),
            )
        },
        quality = choices.quality,
        maxBitrateMbps = choices.maxBitrateMbps,
        audioLanguage = choices.audioLanguage,
    )

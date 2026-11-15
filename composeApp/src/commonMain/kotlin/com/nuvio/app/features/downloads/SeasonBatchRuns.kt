package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** What a finished season, movie-set or movie run was about. */
@Serializable
internal enum class BatchRunKind { Season, MovieSet, Movie }

/** Why an item of a run needs attention. */
@Serializable
internal enum class BatchRunProblemKind { NotFound, Failed }

/**
 * One item of a run that was not downloaded: an episode or a movie, with what is needed to open the
 * list of sources for it. Every field has a default, so a record written by another version, or
 * with fields missing, still reads.
 */
@Serializable
internal data class BatchRunProblem(
    val kind: BatchRunProblemKind = BatchRunProblemKind.Failed,
    val contentType: String = "",
    val parentMetaId: String = "",
    val parentMetaType: String = "",
    val videoId: String = "",
    val title: String = "",
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
) {
    /** True for an episode, which is named by its season and episode number. */
    val isEpisode: Boolean get() = seasonNumber != null && episodeNumber != null

    /**
     * A stand-in for the download that does not exist, carrying what choosing another source needs:
     * the title and where it sits. It is never stored or played.
     */
    fun asSourceRequest(atEpochMs: Long): DownloadItem = DownloadItem(
        id = "",
        contentType = contentType,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        videoId = videoId,
        title = title,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeThumbnail,
        streamTitle = "",
        providerName = "",
        sourceUrl = "",
        fileName = "",
        status = DownloadStatus.Failed,
        createdAtEpochMs = atEpochMs,
        updatedAtEpochMs = atEpochMs,
    )
}

/** The last run for one season of a show, one movie set or one movie. */
@Serializable
internal data class BatchRunRecord(
    val key: String = "",
    val kind: BatchRunKind = BatchRunKind.Season,
    val title: String = "",
    /** The season, for [BatchRunKind.Season]. */
    val seasonNumber: Int? = null,
    val finishedAtEpochMs: Long = 0L,
    /** The run was stopped before it had gone through every item. */
    val stopped: Boolean = false,
    val added: Int = 0,
    val alreadyThere: Int = 0,
    val notFound: Int = 0,
    val failed: Int = 0,
    val paused: Int = 0,
    /** The items that were not found or failed, at most [MaxProblemsPerRun]. */
    val problems: List<BatchRunProblem> = emptyList(),
    /** Problems that did not fit in [problems]. */
    val problemsOmitted: Int = 0,
)

internal const val MaxBatchRuns = 20
internal const val MaxProblemsPerRun = 40

/**
 * What to keep of a finished run, or null when there is nothing worth keeping: a movie that was
 * queued or already there, or a run that was stopped before anything was decided.
 */
internal fun SeasonBatchSummary.toRunRecord(request: SeasonBatchRequest, nowEpochMs: Long): BatchRunRecord? {
    val decided = results.values.count { it != SeasonBatchEpisodeResult.Cancelled }
    if (decided == 0) return null
    val kind = when {
        request.isSingleMovie -> BatchRunKind.Movie
        request.isMovieSet -> BatchRunKind.MovieSet
        else -> BatchRunKind.Season
    }
    val problems = results.entries
        .sortedWith(compareBy({ it.key.seasonNumber }, { it.key.episodeNumber }))
        .mapNotNull { (episode, result) ->
            val problemKind = when (result) {
                SeasonBatchEpisodeResult.NoMatchingSource -> BatchRunProblemKind.NotFound
                SeasonBatchEpisodeResult.Failed -> BatchRunProblemKind.Failed
                else -> return@mapNotNull null
            }
            val movie = episode.movie
            BatchRunProblem(
                kind = problemKind,
                contentType = movie?.metaType ?: request.contentType,
                parentMetaId = movie?.metaId ?: request.parentMetaId,
                parentMetaType = movie?.metaType ?: request.parentMetaType,
                videoId = episode.videoId,
                title = movie?.title ?: request.title,
                logo = if (movie != null) movie.logo else request.logo,
                poster = if (movie != null) movie.poster else request.poster,
                background = if (movie != null) movie.background else request.background,
                seasonNumber = episode.seasonNumber.takeIf { movie == null },
                episodeNumber = episode.episodeNumber.takeIf { movie == null },
                episodeTitle = episode.title,
                episodeThumbnail = episode.thumbnail,
            )
        }
    if (kind == BatchRunKind.Movie && problems.isEmpty()) return null
    return BatchRunRecord(
        key = seasonBatchKey(request.parentMetaId, request.seasonNumber),
        kind = kind,
        title = request.title,
        seasonNumber = request.seasonNumber.takeIf { kind == BatchRunKind.Season },
        finishedAtEpochMs = nowEpochMs,
        stopped = wasCancelled,
        added = count(SeasonBatchEpisodeResult.Completed),
        alreadyThere = count(SeasonBatchEpisodeResult.AlreadyDownloaded),
        notFound = count(SeasonBatchEpisodeResult.NoMatchingSource),
        failed = count(SeasonBatchEpisodeResult.Failed),
        paused = count(SeasonBatchEpisodeResult.Paused),
        problems = problems.take(MaxProblemsPerRun),
        problemsOmitted = (problems.size - MaxProblemsPerRun).coerceAtLeast(0),
    )
}

/** [runs] with [record] first, replacing the run with the same key, and no more than [cap]. */
internal fun List<BatchRunRecord>.withRun(record: BatchRunRecord, cap: Int = MaxBatchRuns): List<BatchRunRecord> =
    (listOf(record) + filterNot { it.key == record.key }).take(cap)

/** The runs kept for the active profile: the last one per season, set or movie, newest first, capped. */
internal object BatchRunSummaries {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Counts up whenever the stored runs change, so a screen reads them again. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> get() = _revision

    /**
     * Reads the stored runs. Unreadable storage is no runs; a run that cannot be read is dropped and
     * the others are kept.
     */
    fun load(profileId: Int): List<BatchRunRecord> {
        val payload = SeasonDownloadDefaultsStorage.loadBatchRunsPayload(profileId)?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return decode(payload)
    }

    internal fun decode(payload: String): List<BatchRunRecord> = runCatching {
        (json.parseToJsonElement(payload) as? JsonArray).orEmpty().mapNotNull { element: JsonElement ->
            runCatching { json.decodeFromJsonElement(BatchRunRecord.serializer(), element) }
                .getOrNull()
                ?.takeIf { it.key.isNotBlank() }
        }
    }.getOrDefault(emptyList()).take(MaxBatchRuns)

    fun record(profileId: Int, record: BatchRunRecord) {
        save(profileId, load(profileId).withRun(record))
    }

    fun dismiss(profileId: Int, key: String) {
        save(profileId, load(profileId).filterNot { it.key == key })
    }

    private fun save(profileId: Int, runs: List<BatchRunRecord>) {
        SeasonDownloadDefaultsStorage.saveBatchRunsPayload(
            profileId,
            json.encodeToString(ListSerializer(BatchRunRecord.serializer()), runs),
        )
        _revision.update { it + 1 }
    }
}

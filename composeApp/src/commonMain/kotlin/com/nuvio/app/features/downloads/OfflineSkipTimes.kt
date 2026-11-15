package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipIntervalLookup
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.SkipLookupReport
import com.nuvio.app.features.player.skip.resolveSkipIntervalLookup
import com.nuvio.app.features.simkl.SimklConfig
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What to look up. Taken from a download, or from the title being played, and the same either way. */
internal data class SkipTimesRequest(
    val isMovie: Boolean,
    val parentMetaId: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    /** An IMDb id known from the title's details, used when the video id is an anime id. */
    val imdbId: String? = null,
)

internal fun DownloadItem.toSkipTimesRequest() = SkipTimesRequest(
    isMovie = contentType.ifBlank { parentMetaType }.equals("movie", ignoreCase = true),
    parentMetaId = parentMetaId,
    videoId = videoId,
    season = seasonNumber,
    episode = episodeNumber,
    imdbId = parentMetaId.takeIf { it.startsWith("tt") },
)

internal sealed interface SkipFetchResult {
    /** The services returned these. */
    data class Found(val intervals: List<SkipInterval>) : SkipFetchResult

    /** The services answered and have nothing for this title. */
    data object NoneFound : SkipFetchResult

    /** The services could not be asked or did not answer. Says nothing about the title. */
    data object Failed : SkipFetchResult
}

internal fun interface SkipTimesFetcher {
    suspend fun fetch(request: SkipTimesRequest): SkipFetchResult
}

/**
 * Asks the same services as playback does, whether or not "skip intro" is switched on, bypassing
 * the in-memory cache so that a changed answer is seen, and telling an unreachable service from one
 * that has nothing.
 */
internal object ServiceSkipTimesFetcher : SkipTimesFetcher {
    /** A lookup that takes longer than this is given up and counts as failed. */
    const val LOOKUP_TIMEOUT_MS = 25_000L

    override suspend fun fetch(request: SkipTimesRequest): SkipFetchResult {
        // With no service configured nothing was asked, so "none found" would be untrue.
        if (!SkipIntroRepository.introDbConfigured && SimklConfig.CLIENT_ID.isBlank()) return SkipFetchResult.Failed
        val report = SkipLookupReport()
        val intervals = try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { withContext(report) { lookup(request) } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        } ?: return SkipFetchResult.Failed
        return when {
            intervals.isNotEmpty() -> SkipFetchResult.Found(intervals)
            report.hadUnansweredRequest || NetworkStatusRepository.uiState.value.isOfflineLike -> SkipFetchResult.Failed
            else -> SkipFetchResult.NoneFound
        }
    }

    private suspend fun lookup(request: SkipTimesRequest): List<SkipInterval> {
        if (request.isMovie) {
            return SkipIntroRepository.getMovieSkipIntervals(
                contentId = request.parentMetaId,
                videoId = request.videoId,
                requireSkipIntroEnabled = false,
                useCache = false,
            )
        }
        val lookup = resolveSkipIntervalLookup(request.videoId, request.season, request.episode) ?: return emptyList()
        return when (lookup) {
            is SkipIntervalLookup.Imdb -> SkipIntroRepository.getSkipIntervals(
                imdbId = lookup.imdbId,
                season = lookup.season,
                episode = lookup.episode,
                requireSkipIntroEnabled = false,
                useCache = false,
            )
            is SkipIntervalLookup.Mal -> SkipIntroRepository.getSkipIntervalsForMal(
                malId = lookup.malId,
                episode = lookup.episode,
                requireSkipIntroEnabled = false,
                imdbId = request.imdbId,
                imdbSeason = request.season,
                imdbEpisode = request.episode ?: lookup.episode,
                useCache = false,
            )
            is SkipIntervalLookup.Kitsu -> SkipIntroRepository.getSkipIntervalsForKitsu(
                kitsuId = lookup.kitsuId,
                episode = lookup.episode,
                requireSkipIntroEnabled = false,
                imdbId = request.imdbId,
                imdbSeason = request.season,
                imdbEpisode = request.episode ?: lookup.episode,
                useCache = false,
            )
        }
    }
}

/** What a finished download's sidecar says, in words for a row. */
internal enum class SkipTimesNote { Saved, NoneFound }

/** What one background pass over the finished downloads did. */
internal data class SkipTimesBackfillSummary(
    val looked: Int = 0,
    val saved: Int = 0,
    val noneFound: Int = 0,
    val failed: Int = 0,
    val unavailable: Int = 0,
    /** The pass ended early: the connection was lost, or the services kept failing. */
    val stoppedEarly: Boolean = false,
)

/**
 * Saves, uses and refreshes the skip times of downloaded titles.
 *
 * What is saved is in [SkipTimesSidecar]. How it combines with the rest, at play time:
 * - Saved intervals are used at once, with no network, in the place the online intervals take.
 * - Chapters in the file keep their priority, through the same merge as for online intervals:
 *   a chapter wins for a kind of segment it covers, and saved intervals fill the kinds it does not.
 * - Online, the services are asked again in the background, at most once per [FoundRefreshAgeMs]
 *   for a title that has intervals and once per [NoneFoundRecheckAgeMs] for one that had none.
 *   A different answer replaces the saved intervals. An answer with nothing, or no answer, never
 *   removes saved intervals: an empty answer cannot be told from a service that is down.
 */
internal class OfflineSkipTimes(
    private val fetcher: SkipTimesFetcher = ServiceSkipTimesFetcher,
    private val isOnline: () -> Boolean = { NetworkStatusRepository.uiState.value.isOnline },
    private val nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = SynchronizedObject()
    private val running = mutableSetOf<String>()
    private val backfillFailedAt = mutableMapOf<String, Long>()
    private var backfillRunning = false

    /** The saved intervals of a file, at once and without the network. Empty when none are saved. */
    fun saved(localVideoUri: String): List<SkipInterval> =
        (SkipTimesSidecar.read(localVideoUri) as? SavedSkipTimesRead.Saved)?.times?.intervals.orEmpty()

    /** For a row of the list of downloads: null when nothing was looked up yet. */
    fun note(localVideoUri: String): SkipTimesNote? =
        (SkipTimesSidecar.read(localVideoUri) as? SavedSkipTimesRead.Saved)?.times?.let {
            if (it.noneFound) SkipTimesNote.NoneFound else SkipTimesNote.Saved
        }

    /** Starts saving the skip times of a download that just finished. Returns at once. */
    fun saveAfterDownload(item: DownloadItem, localVideoUri: String): Job =
        scope.launch { refresh(item.toSkipTimesRequest(), localVideoUri) }

    /**
     * Asks the services if the saved answer is due for it (always when there is none), and saves
     * what they say. Returns the intervals when they now differ from what was saved, else null.
     * Does nothing without a connection, and never touches a saved answer on failure.
     */
    suspend fun refresh(request: SkipTimesRequest, localVideoUri: String): List<SkipInterval>? {
        if (!isOnline()) return null
        val existing = SkipTimesSidecar.read(localVideoUri)
        if (!isDue(existing)) return null
        if (!claim(localVideoUri)) return null
        try {
            val result = fetcher.fetch(request)
            val now = nowEpochMs()
            val before = (existing as? SavedSkipTimesRead.Saved)?.times
            return when (result) {
                SkipFetchResult.Failed -> null
                SkipFetchResult.NoneFound -> {
                    // Keeps what is saved; only notes that the services were asked.
                    SkipTimesSidecar.write(localVideoUri, SavedSkipTimes(now, before?.intervals.orEmpty()))
                    null
                }
                is SkipFetchResult.Found -> {
                    SkipTimesSidecar.write(localVideoUri, SavedSkipTimes(now, result.intervals))
                    result.intervals.takeIf { it != before?.intervals }
                }
            }
        } finally {
            release(localVideoUri)
        }
    }

    /**
     * One quiet pass over finished downloads that have no sidecar (or only a stale "none found"),
     * a few at a time. Skips downloads whose file is not there, such as one on a drive that is not
     * connected. Stops when the connection is lost, after [BackfillMaxPerPass] lookups, or when
     * [BackfillStopAfterFailures] in a row fail. A download that failed is not tried again for
     * [BackfillRetryAfterMs]. Only one pass runs at a time.
     */
    suspend fun backfill(items: List<DownloadItem>, resolveLocalFileUri: (DownloadItem) -> String?): SkipTimesBackfillSummary {
        val started = synchronized(lock) { if (backfillRunning) false else { backfillRunning = true; true } }
        if (!started) return SkipTimesBackfillSummary()
        try {
            return runBackfill(items, resolveLocalFileUri)
        } finally {
            synchronized(lock) { backfillRunning = false }
        }
    }

    private suspend fun runBackfill(items: List<DownloadItem>, resolveLocalFileUri: (DownloadItem) -> String?): SkipTimesBackfillSummary {
        var summary = SkipTimesBackfillSummary()
        var consecutiveFailures = 0
        val pending = ArrayDeque<Pair<DownloadItem, String>>()
        for (item in items) {
            if (item.status != DownloadStatus.Completed) continue
            val uri = runCatching { resolveLocalFileUri(item) }.getOrNull()
            if (uri == null) {
                summary = summary.copy(unavailable = summary.unavailable + 1)
                continue
            }
            val read = SkipTimesSidecar.read(uri)
            if (!needsBackfill(read)) continue
            val failedAt = synchronized(lock) { backfillFailedAt[item.id] }
            if (failedAt != null && nowEpochMs() - failedAt < BackfillRetryAfterMs) continue
            pending += item to uri
        }
        while (pending.isNotEmpty()) {
            if (!isOnline() || summary.looked >= BackfillMaxPerPass || consecutiveFailures >= BackfillStopAfterFailures) {
                return summary.copy(stoppedEarly = true)
            }
            val room = BackfillMaxPerPass - summary.looked
            val batch = List(minOf(BackfillConcurrency, room, pending.size)) { pending.removeFirst() }
            val outcomes = coroutineScope {
                batch.map { (item, uri) -> async { Triple(item, uri, lookupForBackfill(item, uri)) } }.awaitAll()
            }
            for ((item, _, outcome) in outcomes) {
                if (outcome != BackfillOutcome.Busy) summary = summary.copy(looked = summary.looked + 1)
                when (outcome) {
                    // Another lookup for the same file is already running; it will save the answer.
                    BackfillOutcome.Busy -> Unit
                    BackfillOutcome.Saved -> { summary = summary.copy(saved = summary.saved + 1); consecutiveFailures = 0 }
                    BackfillOutcome.NoneFound -> { summary = summary.copy(noneFound = summary.noneFound + 1); consecutiveFailures = 0 }
                    BackfillOutcome.Failed -> {
                        summary = summary.copy(failed = summary.failed + 1)
                        consecutiveFailures++
                        synchronized(lock) { backfillFailedAt[item.id] = nowEpochMs() }
                    }
                }
            }
            if (pending.isNotEmpty()) pause(BackfillPauseMs)
        }
        return summary
    }

    private enum class BackfillOutcome { Saved, NoneFound, Failed, Busy }

    private suspend fun lookupForBackfill(item: DownloadItem, uri: String): BackfillOutcome {
        if (!claim(uri)) return BackfillOutcome.Busy
        try {
            return when (val result = fetcher.fetch(item.toSkipTimesRequest())) {
                SkipFetchResult.Failed -> BackfillOutcome.Failed
                SkipFetchResult.NoneFound -> {
                    SkipTimesSidecar.write(uri, SavedSkipTimes(nowEpochMs(), emptyList()))
                    BackfillOutcome.NoneFound
                }
                is SkipFetchResult.Found -> {
                    SkipTimesSidecar.write(uri, SavedSkipTimes(nowEpochMs(), result.intervals))
                    BackfillOutcome.Saved
                }
            }
        } finally {
            release(uri)
        }
    }

    private fun claim(uri: String): Boolean = synchronized(lock) { running.add(uri) }

    private fun release(uri: String) = synchronized(lock) { running.remove(uri) }

    /** Whether the services should be asked again, as at play time. */
    internal fun isDue(read: SavedSkipTimesRead): Boolean = when (read) {
        SavedSkipTimesRead.Missing, SavedSkipTimesRead.Unusable -> true
        SavedSkipTimesRead.NewerVersion -> false
        is SavedSkipTimesRead.Saved -> {
            val age = nowEpochMs() - read.times.fetchedAtEpochMs
            // A time in the future (a clock that was set back) must not stop rechecks for good.
            age < 0 || age >= if (read.times.noneFound) NoneFoundRecheckAgeMs else FoundRefreshAgeMs
        }
    }

    /** Backfill fills the gaps. A download that has intervals is left to be refreshed when played. */
    private fun needsBackfill(read: SavedSkipTimesRead): Boolean = when (read) {
        SavedSkipTimesRead.Missing, SavedSkipTimesRead.Unusable -> true
        SavedSkipTimesRead.NewerVersion -> false
        is SavedSkipTimesRead.Saved -> read.times.noneFound && isDue(read)
    }

    companion object {
        /** A title that had no skip times is looked up again only after this long: the databases fill in over time. */
        const val NoneFoundRecheckAgeMs = 14L * 24 * 60 * 60 * 1000

        /** A title with saved skip times is looked up again when played, if the answer is older than this. */
        const val FoundRefreshAgeMs = 24L * 60 * 60 * 1000

        /** Most lookups one background pass makes. */
        const val BackfillMaxPerPass = 30

        /** Lookups made at the same time. */
        const val BackfillConcurrency = 2

        /** Wait between one group of lookups and the next. */
        const val BackfillPauseMs = 2_000L

        /** Wait after the app is online before the first pass, so that startup and playback come first. */
        const val BackfillStartDelayMs = 20_000L

        /** A pass ends after this many failed lookups in a row. */
        const val BackfillStopAfterFailures = 3

        /** A download whose lookup failed is not tried again by a pass for this long. */
        const val BackfillRetryAfterMs = 30L * 60 * 1000

        /** The one the app uses. */
        val app: OfflineSkipTimes by lazy { OfflineSkipTimes() }
    }
}

/** Waits [OfflineSkipTimes.BackfillStartDelayMs], then fills in the finished downloads that have no skip times. */
internal suspend fun OfflineSkipTimes.backfillWhenIdle(): SkipTimesBackfillSummary {
    delay(OfflineSkipTimes.BackfillStartDelayMs)
    return backfill(DownloadsRepository.uiState.value.items, DownloadsRepository::playableLocalFileUri)
}

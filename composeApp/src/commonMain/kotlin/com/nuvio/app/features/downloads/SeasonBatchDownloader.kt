package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamMetadata
import com.nuvio.app.features.debrid.DebridStreamPreferences
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.buildPlaybackVideoId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/** Resolution the user picks once for a whole season download. */
internal enum class BatchQuality(val resolution: DebridStreamResolution?) {
    BestAvailable(null),
    P2160(DebridStreamResolution.P2160),
    P1080(DebridStreamResolution.P1080),
    P720(DebridStreamResolution.P720),
    P480(DebridStreamResolution.P480),
}

internal data class SeasonBatchEpisode(
    val videoId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String?,
    val thumbnail: String?,
)

internal data class SeasonBatchRequest(
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String?,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int,
    val episodes: List<SeasonBatchEpisode>,
    val quality: BatchQuality,
)

internal enum class SeasonBatchEpisodeResult {
    Completed,
    AlreadyDownloaded,
    NoMatchingSource,
    Failed,
    Cancelled,

    /** The download was paused (for example by pause all). Not a failure; no other source is tried. */
    Paused,
}

internal data class SeasonBatchSummary(
    val results: Map<SeasonBatchEpisode, SeasonBatchEpisodeResult>,
    val wasCancelled: Boolean,
) {
    fun count(result: SeasonBatchEpisodeResult): Int = results.values.count { it == result }

    /** The outcomes worth telling the user about, without the ones that did not happen. Failures come last. */
    fun nonZeroCounts(): List<Pair<SeasonBatchEpisodeResult, Int>> =
        listOf(
            SeasonBatchEpisodeResult.Completed,
            SeasonBatchEpisodeResult.AlreadyDownloaded,
            SeasonBatchEpisodeResult.NoMatchingSource,
            SeasonBatchEpisodeResult.Paused,
            SeasonBatchEpisodeResult.Failed,
        ).map { it to count(it) }.filter { it.second > 0 }
}

internal data class SeasonBatchProgress(
    val total: Int,
    val finished: Int,
)

internal enum class SeasonBatchStart {
    Started,
    NothingToDownload,
    AlreadyRunning,
}

private val resolutionOnlyPreferences = DebridStreamPreferences()

/**
 * The smallest size a downloaded episode or movie can plausibly have. Some sources answer with a
 * short placeholder clip in place of the file, a valid video of some tens or hundreds of kilobytes.
 * Two megabytes is several times the largest such clip seen and well below a real episode: even
 * one minute of video at 300 kbit/s is larger.
 */
internal const val MinPlausibleVideoBytes = 2_000_000L

/**
 * The bitrate below which a file cannot be the video its runtime says it is: 50 kbit/s, a fifth of
 * the lowest bitrate video is encoded at, so a runtime that is somewhat wrong does no harm.
 */
internal const val MinPlausibleVideoBitsPerSecond = 50_000L

/**
 * The size below which a download is taken for a placeholder and not for the video. With a known
 * runtime it is that runtime at [MinPlausibleVideoBitsPerSecond], which is less than
 * [MinPlausibleVideoBytes] for something shorter than about five minutes, so a short item is not
 * turned away, and more for a full episode or a movie. Without a runtime it is
 * [MinPlausibleVideoBytes].
 */
internal fun minPlausibleVideoBytes(runtimeMinutes: Int? = null): Long =
    runtimeMinutes?.takeIf { it > 0 }?.let { it * 60L * MinPlausibleVideoBitsPerSecond / 8 } ?: MinPlausibleVideoBytes

/**
 * The size of a finished download: the bytes that arrived. The size that was announced stands in
 * only where no bytes were counted.
 */
internal val DownloadItem.finishedSizeBytes: Long
    get() = downloadedBytes.takeIf { it > 0L } ?: totalBytes ?: 0L

/** The size the source says its file has, when it says so. */
internal fun StreamItem.announcedSizeBytes(): Long? =
    DebridStreamMetadata.facts(this, resolutionOnlyPreferences).size?.takeIf { it > 0L }

internal fun StreamItem.batchResolution(): DebridStreamResolution =
    DebridStreamMetadata.facts(this, resolutionOnlyPreferences).resolution

/**
 * Orders the sources worth trying for one episode: only formats the downloader can save and only the
 * chosen resolution. [BatchQuality.BestAvailable] keeps every resolution and tries the highest first.
 * Within one resolution, ready direct links come before ones that still need a debrid lookup.
 */
internal fun rankBatchCandidates(streams: List<StreamItem>, quality: BatchQuality): List<StreamItem> {
    val usable = streams
        .filter { stream ->
            val directUrl = stream.playableDirectUrl
            if (directUrl != null) {
                directUrl.isSupportedDownloadUrl()
            } else {
                stream.isDirectDebridStream || stream.needsLocalDebridResolve
            }
        }
        .map { it to it.batchResolution() }
        .filter { (_, resolution) -> quality.resolution == null || resolution == quality.resolution }
    val ordered = usable
        .sortedWith(
            compareByDescending<Pair<StreamItem, DebridStreamResolution>> { (_, resolution) -> resolution.value }
                .thenBy { (stream, _) -> stream.playableDirectUrl == null },
        )
        .map { it.first }
    // Two sources with the same URL are the same request only when they also send the same headers.
    val seenRequests = mutableSetOf<Pair<String, List<Pair<String, String>>>>()
    return ordered.filter { stream ->
        val url = stream.playableDirectUrl ?: return@filter true
        val headers = stream.behaviorHints.proxyHeaders?.request.orEmpty()
            .map { (name, value) -> name.lowercase() to value }
            .sortedWith(compareBy({ it.first }, { it.second }))
        seenRequests.add(url to headers)
    }
}

/** Released, numbered episodes of one season. Unaired episodes are left out. */
internal fun MetaDetails.seasonBatchEpisodes(seasonNumber: Int, todayIsoDate: String): List<SeasonBatchEpisode> =
    releasedEpisodesForSeason(seasonNumber = seasonNumber, todayIsoDate = todayIsoDate)
        .mapNotNull { video ->
            val season = video.season ?: return@mapNotNull null
            val episode = video.episode ?: return@mapNotNull null
            SeasonBatchEpisode(
                videoId = video.id.takeIf { it.isNotBlank() }
                    ?: buildPlaybackVideoId(
                        content = WatchingContentRef(type = type, id = id),
                        seasonNumber = season,
                        episodeNumber = episode,
                    ),
                seasonNumber = season,
                episodeNumber = episode,
                title = video.title.takeIf { it.isNotBlank() },
                thumbnail = video.thumbnail,
            )
        }

internal fun seasonBatchKey(parentMetaId: String, seasonNumber: Int): String =
    "${parentMetaId.trim()}|$seasonNumber"

/**
 * Queues a season through the normal downloads pipeline, a few episodes at a time.
 *
 * Each episode holds one of [maxConcurrent] slots (shared by every batch of this downloader) from
 * source lookup until its download finishes or is paused,
 * so a 20 episode season never opens 20 transfers or 20 addon lookups at once. A source that fails
 * is replaced by the next matching one, and at most [MaxSourcesPerEpisode] sources are tried per
 * episode. A source whose file is implausibly small ([minVideoBytes]) is a failed source: it is
 * skipped when the source itself announces such a size, stopped when the server announces it, and
 * otherwise thrown away once it has arrived, and the next source is tried. An episode counts as
 * downloaded only while its file is really there ([verifyCompleted]) and is not such a placeholder;
 * otherwise it is fetched again. Batch state is in memory only: after an app restart the downloads
 * that were running go on by themselves, and running Download Season again queues what is missing.
 * Those downloads take slots of their own ([adoptRunning]), so the limit also holds across a
 * restart.
 *
 * [start] and [cancel] can be called from any thread.
 */
internal class SeasonBatchDownloader(
    private val scope: CoroutineScope,
    private val downloads: StateFlow<DownloadsUiState>,
    private val fetchStreams: suspend (SeasonBatchEpisode) -> List<StreamItem>,
    /** Asked only when [fetchStreams] gave nothing usable (or every source it gave failed). */
    private val fetchFallbackStreams: (suspend (SeasonBatchEpisode) -> List<StreamItem>)? = null,
    private val prepareStream: suspend (StreamItem, SeasonBatchEpisode) -> StreamItem?,
    private val enqueue: (SeasonBatchRequest, SeasonBatchEpisode, StreamItem) -> DownloadEnqueueResult,
    private val onFinished: suspend (SeasonBatchRequest, SeasonBatchSummary) -> Unit = { _, _ -> },
    private val maxConcurrent: Int = DefaultMaxConcurrent,
    /** Removes a download this batch queued, with its file, because it is not the video. */
    private val discard: (DownloadItem) -> Unit = {},
    /** False when a finished download has lost its file. It is then no longer counted as downloaded. */
    private val verifyCompleted: (DownloadItem) -> Boolean = { true },
    /** The size below which the download of an episode is a placeholder. */
    private val minVideoBytes: (SeasonBatchEpisode) -> Long = { minPlausibleVideoBytes() },
) {
    private val _running = MutableStateFlow<Map<String, SeasonBatchProgress>>(emptyMap())

    /** Batches currently running, keyed by [seasonBatchKey]. */
    val running: StateFlow<Map<String, SeasonBatchProgress>> = _running.asStateFlow()

    // Guards the running batches and the step that queues an episode, so a stop or a pause is either
    // seen by that step or happens after the episode was queued.
    private val lock = SynchronizedObject()
    private val batches = mutableMapOf<String, Batch>()

    // One limit for every season batch of this downloader, so starting several seasons does not
    // multiply the number of simultaneous lookups and transfers.
    private val slots = Semaphore(maxConcurrent.coerceAtLeast(1))

    fun start(request: SeasonBatchRequest): SeasonBatchStart {
        if (request.episodes.isEmpty()) return SeasonBatchStart.NothingToDownload
        val key = seasonBatchKey(request.parentMetaId, request.seasonNumber)
        val batch = Batch()
        val job = synchronized(lock) {
            if (key in batches) return SeasonBatchStart.AlreadyRunning
            _running.update { it + (key to SeasonBatchProgress(request.episodes.size, 0)) }
            // The batch runs up to its first suspension before start returns, so a stop that arrives
            // straight away still finds it running and gets its summary.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { run(key, request, batch) }.also {
                batch.job = it
                batches[key] = batch
            }
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (batches[key] === batch) {
                    batches.remove(key)
                    _running.update { it - key }
                }
            }
        }
        return SeasonBatchStart.Started
    }

    /**
     * Lets downloads that are already running, because they were cut off when the app was closed
     * and went on at launch, take slots until they have settled. Each waits for a slot ahead of
     * the batches started later, so no episode is queued while more of them run than there are
     * slots. They themselves are not held back: more than the limit can run when more were running
     * when the app was closed.
     */
    fun adoptRunning(downloadIds: Collection<String>) {
        fun stillRunning(state: DownloadsUiState, id: String) = state.items.any { it.id == id && it.status == DownloadStatus.Downloading }
        downloadIds.filter { stillRunning(downloads.value, it) }.forEach { id ->
            // Started at once, so that it stands in line for a slot before a batch that starts next.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                slots.acquire()
                try {
                    downloads.first { state -> !stillRunning(state, id) }
                } finally {
                    slots.release()
                }
            }
        }
    }

    /**
     * Stops queueing further episodes: none is queued once this has returned. Downloads that are
     * already running stay in the Downloads screen.
     */
    fun cancel(parentMetaId: String, seasonNumber: Int) {
        val key = seasonBatchKey(parentMetaId, seasonNumber)
        val batch = synchronized(lock) { batches[key]?.also { it.stopped = true } }
        batch?.job?.cancel()
    }

    private suspend fun run(key: String, request: SeasonBatchRequest, batch: Batch) {
        val results = MutableStateFlow(emptyMap<SeasonBatchEpisode, SeasonBatchEpisodeResult>())
        var cancelled = false
        try {
            coroutineScope {
                request.episodes.forEach { episode ->
                    launch {
                        slots.acquire()
                        var slotHandedOver = false
                        val result = try {
                            // Once something is paused the batch stops starting more episodes.
                            if (synchronized(lock) { batch.sawPause(downloads.value, request) }) {
                                SeasonBatchEpisodeResult.Paused
                            } else {
                                processEpisode(request, episode, batch).also {
                                    if (it == SeasonBatchEpisodeResult.Paused) synchronized(lock) { batch.pauseSeen = true }
                                }
                            }
                        } catch (error: CancellationException) {
                            // Stop queueing leaves a running download in the Downloads screen. It keeps
                            // its slot until it settles, so stopping never raises the number of transfers.
                            if (downloads.value.itemFor(request, episode)?.status == DownloadStatus.Downloading) {
                                slotHandedOver = true
                                scope.launch {
                                    try {
                                        downloads.first { state -> state.itemFor(request, episode).isSettledOrGone() }
                                    } finally {
                                        slots.release()
                                    }
                                }
                            }
                            throw error
                        } finally {
                            if (!slotHandedOver) slots.release()
                        }
                        results.update { it + (episode to result) }
                        _running.update { current ->
                            current[key]?.let { current + (key to it.copy(finished = it.finished + 1)) } ?: current
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        } finally {
            val finished = results.value
            // A stopped batch still reports what it did, so the callback must run after cancellation.
            withContext(NonCancellable) {
                onFinished(
                    request,
                    SeasonBatchSummary(
                        results = request.episodes.associateWith { finished[it] ?: SeasonBatchEpisodeResult.Cancelled },
                        wasCancelled = cancelled,
                    ),
                )
            }
        }
    }

    private suspend fun processEpisode(
        request: SeasonBatchRequest,
        episode: SeasonBatchEpisode,
        batch: Batch,
    ): SeasonBatchEpisodeResult {
        val minBytes = minVideoBytes(episode)
        if (downloads.value.hasActiveOrFinishedDownload(request, episode, minBytes)) return SeasonBatchEpisodeResult.AlreadyDownloaded

        // Every source that is prepared counts, whether or not it could be queued, so an episode with
        // many unusable sources does not trigger a lookup for each of them.
        var attempts = 0
        var started = 0
        var sourceFailed = false
        for (source in listOfNotNull(fetchStreams, fetchFallbackStreams)) {
            if (attempts >= MaxSourcesPerEpisode) break
            val candidates = try {
                rankBatchCandidates(source(episode), request.quality)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                sourceFailed = true
                continue
            }

            for (candidate in candidates) {
                if (attempts >= MaxSourcesPerEpisode) break
                // A source that says itself that its file is tiny is not worth a lookup or a transfer.
                if (candidate.announcedSizeBytes()?.let { it < minBytes } == true) continue
                attempts++
                val prepared = try {
                    prepareStream(candidate, episode)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    sourceFailed = true
                    null
                } ?: continue

                currentCoroutineContext().ensureActive()
                when (queueIfStillWanted(request, episode, prepared, batch)) {
                    QueueOutcome.Stopped -> throw CancellationException("Season download stopped")
                    QueueOutcome.Paused -> return SeasonBatchEpisodeResult.Paused
                    QueueOutcome.AlreadyDownloaded -> return SeasonBatchEpisodeResult.AlreadyDownloaded
                    QueueOutcome.Unusable -> continue
                    QueueOutcome.Failed -> {
                        sourceFailed = true
                        continue
                    }
                    QueueOutcome.Queued -> {
                        started++
                        val item = downloads
                            .first { state ->
                                val current = state.itemFor(request, episode)
                                current.isSettledOrGone() || current.isAnnouncedSmallerThan(minBytes)
                            }
                            .itemFor(request, episode)
                        when {
                            item == null -> return SeasonBatchEpisodeResult.Cancelled
                            // A placeholder clip, announced by the server or arrived: not the video.
                            item.isAnnouncedSmallerThan(minBytes) || item.isCompletedSmallerThan(minBytes) -> discard(item)
                            item.status == DownloadStatus.Completed -> return SeasonBatchEpisodeResult.Completed
                            item.status == DownloadStatus.Paused -> return SeasonBatchEpisodeResult.Paused
                            else -> Unit // Failed: try the next matching source
                        }
                    }
                }
            }
        }
        // Not found means there was nothing usable to try. A source that was tried and went wrong,
        // at lookup, preparation, queueing or download, makes the episode failed.
        return if (started > 0 || sourceFailed) {
            SeasonBatchEpisodeResult.Failed
        } else {
            SeasonBatchEpisodeResult.NoMatchingSource
        }
    }

    /**
     * Queues [stream] unless something happened while its sources were resolving: the season was
     * stopped, one of its downloads was paused, or the episode was downloaded by hand. The checks and
     * the queueing are one step under [lock].
     */
    private fun queueIfStillWanted(
        request: SeasonBatchRequest,
        episode: SeasonBatchEpisode,
        stream: StreamItem,
        batch: Batch,
    ): QueueOutcome = synchronized(lock) {
        val state = downloads.value
        when {
            batch.stopped -> QueueOutcome.Stopped
            batch.sawPause(state, request) -> QueueOutcome.Paused
            state.hasActiveOrFinishedDownload(request, episode, minVideoBytes(episode)) -> QueueOutcome.AlreadyDownloaded
            else -> try {
                when (enqueue(request, episode, stream)) {
                    DownloadEnqueueResult.MissingUrl,
                    DownloadEnqueueResult.UnsupportedFormat -> QueueOutcome.Unusable
                    DownloadEnqueueResult.Started,
                    DownloadEnqueueResult.Replaced -> {
                        batch.queued += episode
                        QueueOutcome.Queued
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                QueueOutcome.Failed
            }
        }
    }

    /**
     * True once any download this batch queued has been seen paused. It stays true when that download
     * is resumed: the rest of the season is added by running Download season again.
     */
    private fun Batch.sawPause(state: DownloadsUiState, request: SeasonBatchRequest): Boolean {
        if (!pauseSeen) {
            pauseSeen = queued.any { state.itemFor(request, it)?.status == DownloadStatus.Paused }
        }
        return pauseSeen
    }

    /**
     * Whether the episode needs no download. A finished one counts only when it is not a placeholder
     * clip and its file is still there; queueing the episode again replaces such a record.
     */
    private fun DownloadsUiState.hasActiveOrFinishedDownload(
        request: SeasonBatchRequest,
        episode: SeasonBatchEpisode,
        minBytes: Long,
    ): Boolean {
        val item = itemFor(request, episode) ?: return false
        return when (item.status) {
            DownloadStatus.Completed -> !item.isCompletedSmallerThan(minBytes) && verifyCompleted(item)
            DownloadStatus.Downloading, DownloadStatus.Paused -> true
            DownloadStatus.Failed -> false
        }
    }

    private fun DownloadsUiState.itemFor(request: SeasonBatchRequest, episode: SeasonBatchEpisode): DownloadItem? =
        items.firstOrNull { item ->
            item.parentMetaId.trim() == request.parentMetaId.trim() &&
                item.seasonNumber == episode.seasonNumber &&
                item.episodeNumber == episode.episodeNumber
        }

    private fun DownloadItem?.isSettledOrGone(): Boolean =
        this == null || status != DownloadStatus.Downloading

    private fun DownloadItem?.isAnnouncedSmallerThan(minBytes: Long): Boolean =
        this != null && status == DownloadStatus.Downloading && totalBytes != null && totalBytes in 1 until minBytes

    private fun DownloadItem.isCompletedSmallerThan(minBytes: Long): Boolean =
        status == DownloadStatus.Completed && finishedSizeBytes < minBytes

    /** One running season. Every field is read and written under [lock]. */
    private class Batch {
        var job: Job? = null
        var stopped = false
        var pauseSeen = false
        val queued = mutableSetOf<SeasonBatchEpisode>()
    }

    private enum class QueueOutcome { Queued, Unusable, Failed, Paused, AlreadyDownloaded, Stopped }

    companion object {
        const val DefaultMaxConcurrent = 2
        const val MaxSourcesPerEpisode = 3
    }
}

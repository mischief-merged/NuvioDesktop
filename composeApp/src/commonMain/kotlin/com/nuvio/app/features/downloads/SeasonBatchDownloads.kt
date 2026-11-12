package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.runCatchingUnlessCancelled
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_season_count_existing
import nuvio.composeapp.generated.resources.downloads_season_count_failed
import nuvio.composeapp.generated.resources.downloads_season_count_new
import nuvio.composeapp.generated.resources.downloads_season_count_not_found
import nuvio.composeapp.generated.resources.downloads_season_count_paused
import nuvio.composeapp.generated.resources.downloads_season_finished
import nuvio.composeapp.generated.resources.downloads_season_stopped
import nuvio.composeapp.generated.resources.downloads_season_summary
import org.jetbrains.compose.resources.getString

/**
 * App wiring for [SeasonBatchDownloader]: installed addons supply the sources, the existing
 * [DownloadsRepository] queue does the downloading.
 *
 * Plugin scrapers and native server sources are not used for batches yet.
 */
internal object SeasonBatchDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val downloader = SeasonBatchDownloader(
        scope = scope,
        downloads = DownloadsRepository.uiState,
        // Embedded streams are what the app itself uses first; installed addons are asked when they
        // give nothing usable for the chosen quality.
        fetchStreams = { episode -> MetaDetailsRepository.findEmbeddedStreams(episode.videoId) },
        fetchFallbackStreams = ::fetchAddonStreams,
        prepareStream = ::prepareStream,
        enqueue = { request, episode, stream ->
            DownloadsRepository.enqueueFromStream(
                contentType = request.contentType,
                videoId = episode.videoId,
                parentMetaId = request.parentMetaId,
                parentMetaType = request.parentMetaType,
                title = request.title,
                logo = request.logo,
                poster = request.poster,
                background = request.background,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                episodeTitle = episode.title,
                episodeThumbnail = episode.thumbnail,
                stream = stream,
            )
        },
        onFinished = { _, summary -> NuvioToastController.show(summary.toastMessage()) },
        discard = { item -> DownloadsRepository.cancelDownload(item.id) },
        verifyCompleted = { item -> DownloadsRepository.verifyCompletedDownload(item.id) },
        inspect = DownloadsRepository::inspectCompletedDownload,
        preferAudioLanguage = { item, language -> DownloadsRepository.setPreferredAudioLanguage(item.id, language) },
    )

    val running: StateFlow<Map<String, SeasonBatchProgress>> get() = downloader.running

    fun start(request: SeasonBatchRequest): SeasonBatchStart {
        DownloadsRepository.ensureLoaded()
        downloader.adoptRunning(DownloadsRepository.takeDownloadsResumedAtLoad())
        return downloader.start(request)
    }

    fun cancel(parentMetaId: String, seasonNumber: Int) = downloader.cancel(parentMetaId, seasonNumber)

    private suspend fun fetchAddonStreams(episode: SeasonBatchEpisode): List<StreamItem> {
        val type = "series"
        return coroutineScope {
            AddonRepository.uiState.value.addons.enabledAddons()
                .mapNotNull { addon ->
                    val manifest = addon.manifest ?: return@mapNotNull null
                    if (!manifest.supportsStream(type, episode.videoId)) return@mapNotNull null
                    async {
                        runCatchingUnlessCancelled {
                            StreamParser.parse(
                                payload = fetchAddonResponseText(
                                    buildAddonResourceUrl(
                                        manifestUrl = manifest.transportUrl,
                                        resource = "stream",
                                        type = type,
                                        id = episode.videoId,
                                    ),
                                ),
                                addonName = addon.displayTitle.ifBlank { manifest.name },
                                addonId = addon.streamAddonInstanceId(manifest.id),
                                addonLogo = manifest.logoUrl,
                            )
                        }.getOrDefault(emptyList())
                    }
                }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun prepareStream(stream: StreamItem, episode: SeasonBatchEpisode): StreamItem? {
        val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
            stream = stream,
            season = episode.seasonNumber,
            episode = episode.episodeNumber,
        )
        return (resolved as? DirectDebridPlayableResult.Success)?.stream
    }

    private suspend fun SeasonBatchSummary.toastMessage(): String {
        val heading = getString(
            if (wasCancelled) Res.string.downloads_season_stopped else Res.string.downloads_season_finished,
        )
        val counts = nonZeroCounts().mapNotNull { (result, count) ->
            val label = when (result) {
                SeasonBatchEpisodeResult.Completed -> Res.string.downloads_season_count_new
                SeasonBatchEpisodeResult.AlreadyDownloaded -> Res.string.downloads_season_count_existing
                SeasonBatchEpisodeResult.NoMatchingSource -> Res.string.downloads_season_count_not_found
                SeasonBatchEpisodeResult.Paused -> Res.string.downloads_season_count_paused
                SeasonBatchEpisodeResult.Failed -> Res.string.downloads_season_count_failed
                SeasonBatchEpisodeResult.Cancelled -> return@mapNotNull null
            }
            getString(label, count)
        }
        return if (counts.isEmpty()) {
            heading
        } else {
            getString(Res.string.downloads_season_summary, heading, counts.joinToString(", "))
        }
    }
}

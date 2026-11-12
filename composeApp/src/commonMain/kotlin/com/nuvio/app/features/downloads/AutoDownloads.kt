package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.releasedPlayableEpisodes
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.isDesktop
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Emits each time the internet can be reached again after it could not. */
internal fun Flow<NetworkCondition>.internetReturns(): Flow<Unit> = flow {
    var wasGone = false
    collect { condition ->
        when (condition) {
            NetworkCondition.NoInternet -> wasGone = true
            NetworkCondition.Online, NetworkCondition.ServersUnreachable -> if (wasGone) {
                wasGone = false
                emit(Unit)
            }
            NetworkCondition.Unknown, NetworkCondition.Checking -> Unit
        }
    }
}

/** What the interface needs from automatic downloads. */
internal interface AutoDownloadController {
    val rules: StateFlow<List<AutoDownloadRule>>
    val statuses: StateFlow<Map<String, AutoDownloadStatus>>

    /** The storage limit for automatic downloads, in gigabytes. Null means no limit. */
    val storageLimitGb: StateFlow<Int?>

    /**
     * Turns automatic downloads on for [meta], or changes its rule, and checks the show. Nothing
     * happens when [profileId] is no longer the active profile.
     */
    fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope)

    fun turnOff(profileId: Int, ruleKey: String)

    fun setStorageLimit(profileId: Int, limitGb: Int?)

    fun checkNow()
}

/** Released, numbered episodes of every season of a show, in the order the app plays them. */
internal fun MetaDetails.releasedBatchEpisodes(todayIsoDate: String): List<SeasonBatchEpisode> =
    releasedPlayableEpisodes(todayIsoDate).mapNotNull { video ->
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
            runtimeMinutes = video.runtime,
        )
    }

/**
 * App wiring for automatic downloads. Desktop only.
 *
 * Rules are checked only while the app is open: a minute after it starts, every six hours, when a
 * rule is saved, when the user asks, and after a downloaded episode was played to the end. Every
 * ten minutes the rules with an episode whose wait is over are checked as well. Storage is read and
 * written on a background thread, never on the caller's.
 */
internal object AutoDownloads : AutoDownloadController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = atomic(false)

    private val store = AutoDownloadStore(PlatformAutoDownloadPayloads) { ProfileRepository.activeProfileId }

    private val engine = AutoDownloadEngine(
        store = store,
        loadEpisodes = { rule ->
            // Not from the details cache, which would hide episodes announced since it was filled.
            MetaDetailsRepository.fetch(rule.parentMetaType, rule.parentMetaId, cacheResult = false, useCached = false)
                ?.releasedBatchEpisodes(CurrentDateProvider.todayIsoDate())
        },
        isWatched = { rule, episode ->
            WatchedRepository.isWatched(
                id = rule.parentMetaId,
                type = rule.parentMetaType,
                season = episode.seasonNumber,
                episode = episode.episodeNumber,
            )
        },
        downloads = { DownloadsRepository.uiState.value.items },
        seasonDefaults = SeasonDownloadDefaultsRepository::load,
        startBatch = SeasonBatchDownloads::start,
        cancelBatch = SeasonBatchDownloads::cancel,
        nowEpochMs = { DownloadsClock.nowEpochMs() },
        autoDownloadBytes = { DownloadsRepository.autoDownloadBytes() },
        resumeDownload = DownloadsRepository::resumeDownload,
    )

    val isAvailable: Boolean get() = isDesktop && AppFeaturePolicy.downloadsEnabled

    override val rules: StateFlow<List<AutoDownloadRule>> get() = store.rules
    override val statuses: StateFlow<Map<String, AutoDownloadStatus>> get() = engine.statuses
    override val storageLimitGb: StateFlow<Int?> get() = store.storageLimitGb

    fun storageLimitBytes(): Long? = store.storageLimitBytes()

    fun start() {
        if (!isAvailable || !started.compareAndSet(expect = false, update = true)) return
        scope.launch {
            delay(StartupDelayMs)
            while (true) {
                if (engine.isFullCheckDue()) {
                    checkSafely()
                } else {
                    engine.rulesWithDueRetry().forEach { checkSafely(it) }
                }
                delay(PollIntervalMs)
            }
        }
        scope.launch {
            // What could not be fetched while the internet was gone is looked for again as soon as
            // the app sees it return, without waiting for the next periodic check.
            NetworkStatusRepository.uiState
                .map { it.condition }
                .internetReturns()
                .collect { checkSafely(ignoreWaiting = true) }
        }
        scope.launch {
            // Progress alone changes nothing here, so only the episodes and their states are compared.
            DownloadsRepository.uiState
                .distinctUntilChangedBy { state ->
                    state.items.map { listOf(it.id, it.status, it.autoDownloadRuleKey) }
                }
                .collect { state ->
                    // A storage error here must not end the observation.
                    runCatching { engine.onDownloadsChanged(store.profileId(), state.items) }
                }
        }
    }

    override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope) {
        if (!isAvailable) return
        val rule = AutoDownloadRule(
            parentMetaType = meta.type,
            parentMetaId = meta.id,
            title = meta.name,
            logo = meta.logo,
            poster = meta.poster,
            background = meta.background,
            keepCount = keepCount,
            scope = scope,
        )
        this.scope.launch {
            val saved = runCatching { store.upsert(profileId, rule) }.getOrDefault(false)
            if (saved) checkSafely(rule.key, ignoreWaiting = true)
        }
    }

    override fun turnOff(profileId: Int, ruleKey: String) {
        scope.launch {
            val removed = runCatching { store.remove(profileId, ruleKey) }.getOrDefault(false)
            if (removed) engine.forget(ruleKey)
        }
    }

    /** A higher limit may make room for what the old one held back, so the rules are checked. */
    override fun setStorageLimit(profileId: Int, limitGb: Int?) {
        scope.launch {
            val saved = runCatching { store.setStorageLimit(profileId, limitGb) }.getOrDefault(false)
            if (saved) checkSafely()
        }
    }

    override fun checkNow() {
        if (!isAvailable) return
        scope.launch { checkSafely(ignoreWaiting = true) }
    }

    /**
     * Loads the rules of the profile that is active now and stops what the rules of the previous
     * one were adding. The new profile's rules are checked at the next ten-minute poll.
     */
    fun onProfileChanged() {
        if (!started.value) return
        scope.launch { runCatching { engine.useProfile(store.profileId()) } }
    }

    /**
     * A downloaded file was played to the end on this device. The show it belongs to is checked, so
     * the episode that now comes next is fetched without waiting for the next periodic check.
     */
    fun onLocalPlaybackCompleted(localFileUri: String) {
        if (!started.value) return
        scope.launch {
            val item = DownloadsRepository.findCompletedDownloadByLocalFile(localFileUri) ?: return@launch
            // The watched state is saved by the player after it reports the end of playback.
            delay(PlaybackCheckDelayMs)
            checkSafely(autoDownloadRuleKey(item.parentMetaType, item.parentMetaId))
        }
    }

    fun onPrivateSourceSkipped(request: SeasonBatchRequest, episode: SeasonBatchEpisode) {
        engine.onPrivateSourceSkipped(request, episode)
    }

    fun onBatchFinished(request: SeasonBatchRequest, summary: SeasonBatchSummary) {
        engine.onBatchFinished(request, summary)
    }

    private suspend fun checkSafely(ruleKey: String? = null, ignoreWaiting: Boolean = false) {
        try {
            engine.runCheck(ruleKey, ignoreWaiting)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // One failed check must not end the periodic checks.
        }
    }

    private const val StartupDelayMs = 60_000L
    private const val PollIntervalMs = 10L * 60 * 1000
    private const val PlaybackCheckDelayMs = 5_000L
}

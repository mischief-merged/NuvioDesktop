package com.nuvio.app.features.tracking.outbox

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.trakt.TraktUndelivered
import com.nuvio.app.features.watching.sync.TraktOutboxAdapter
import com.nuvio.app.isDesktop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.nuvio.app.core.ui.NuvioToastController
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.trakt_queue_toast_saved
import org.jetbrains.compose.resources.getString

/** App wiring for the Trakt outbox. Only active on desktop: elsewhere nothing is queued or sent from here. */
internal object TrackerOutboxRuntime {
    private val log = Logger.withTag("TrackerOutbox")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val isActive: Boolean get() = isDesktop

    private val outbox: TrackerOutbox by lazy {
        TrackerOutbox(
            store = object : OutboxPayloadStore {
                override fun load(): String? = TrackerOutboxStorage.loadPayload()
                override fun save(payload: String) = TrackerOutboxStorage.savePayload(payload)
                override fun saveUnreadable(payload: String, savedAtEpochMs: Long) =
                    TrackerOutboxStorage.saveUnreadablePayload(payload, savedAtEpochMs)
            },
            adapter = TraktOutboxAdapter(),
            nowEpochMs = TraktPlatformClock::nowEpochMs,
            sleep = { delay(it) },
        )
    }

    private val pump: OutboxPump by lazy {
        OutboxPump(
            scope = scope,
            nowEpochMs = TraktPlatformClock::nowEpochMs,
            flush = { outbox.flush(ProfileRepository.activeProfileId) },
            nextWakeAtEpochMs = { outbox.nextWakeAtEpochMs(ProfileRepository.activeProfileId) },
        ).also { pump ->
            scope.launch {
                NetworkStatusRepository.uiState
                    .map { it.condition }
                    .distinctUntilChanged()
                    .collect { condition -> if (condition == NetworkCondition.Online) pump.wake() }
            }
            scope.launch {
                TraktAuthRepository.isAuthenticated.collect { connected -> if (connected) pump.wake() }
            }
        }
    }

    private val queuedNotice = QueuedWatchNotice {
        val message = runCatching { runBlocking { getString(Res.string.trakt_queue_toast_saved) } }.getOrNull()
        if (message != null) NuvioToastController.show(message, durationMillis = 4_500L)
    }

    private fun currentIdentity(): QueueIdentity {
        val profileId = ProfileRepository.activeProfileId
        return QueueIdentity(profileId, TraktAuthRepository.connectedAccountKey(profileId))
    }

    /**
     * What Settings > Tracking shows and asks for the Trakt queue, or null where the queue is not active.
     * The queue follows the active profile and the connected Trakt account.
     */
    val queueController: TrackerQueueController? by lazy {
        if (!isActive) return@lazy null
        val identity: StateFlow<QueueIdentity> = TraktAuthRepository.uiState
            .map { currentIdentity() }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, currentIdentity())
        OutboxQueueController(
            outbox = outbox,
            scope = scope,
            identity = identity,
            isOnline = { !NetworkStatusRepository.uiState.value.isOfflineLike },
            nowEpochMs = TraktPlatformClock::nowEpochMs,
        )
    }

    /** Safe to call again, for example after the active profile changed: it then only triggers a flush. */
    fun start() {
        if (!isActive) return
        pump.start()
        pump.wake()
    }

    /** Returns true when the queue holds the watch afterwards. */
    fun enqueueTraktWatch(
        profileId: Int,
        media: OutboxMedia,
        watchedAtEpochMs: Long,
        undelivered: TraktUndelivered,
    ): Boolean {
        if (!isActive) return false
        val accountKey = TraktAuthRepository.connectedAccountKey(profileId)
        if (accountKey == null) {
            log.w { "A Trakt watch was not queued: the connected account is not known" }
            return false
        }
        val held = guarded(default = false) {
            outbox.enqueue(
                OutboxWatch(
                    profileId = profileId,
                    accountKey = accountKey,
                    media = media,
                    watchedAtEpochMs = watchedAtEpochMs,
                    maybeDelivered = undelivered.maybeDelivered,
                    notBeforeEpochMs = undelivered.retryAfterMs?.let { TraktPlatformClock.nowEpochMs() + it } ?: 0L,
                ),
            )
        }
        if (held) {
            queuedNotice.onQueued()
            pump.wake()
        }
        return held
    }

    fun hasQueuedTraktWatches(profileId: Int): Boolean =
        isActive && guarded(default = false) { outbox.hasEntries(profileId) }

    /** The titles were un-watched: a watch still queued for one of them must not reach Trakt later. */
    suspend fun cancelTraktWatches(profileId: Int, media: Collection<OutboxMedia>) {
        if (!isActive || media.isEmpty()) return
        try {
            outbox.cancel(profileId, media)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Could not cancel queued Trakt watches: ${error.message}" }
        }
    }

    private inline fun <T> guarded(default: T, block: () -> T): T =
        try {
            block()
        } catch (error: Throwable) {
            log.w { "Tracker outbox storage failed: ${error.message}" }
            default
        }
}

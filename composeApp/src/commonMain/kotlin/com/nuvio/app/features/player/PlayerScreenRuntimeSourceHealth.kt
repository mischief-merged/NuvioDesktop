package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_error_source_stalled
import nuvio.composeapp.generated.resources.player_error_source_startup_timeout
import org.jetbrains.compose.resources.getString

private val log = Logger.withTag("PlayerSourceHealth")

private val sourceHealthClock = kotlin.time.TimeSource.Monotonic.markNow()

private fun sourceHealthNowMs(): Long = sourceHealthClock.elapsedNow().inWholeMilliseconds

private fun isDeviceOffline(): Boolean {
    val condition = NetworkStatusRepository.uiState.value.condition
    return condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable
}

/**
 * Watches the source that was just handed to the player until it fails, the user leaves, or it is replaced.
 * One path decides, so the start and the stall never both fire:
 *
 *  1. Until playback has advanced, [SourceStartMonitor] judges the start. Its no-progress deadline is the
 *     user's "time for a source to start" setting. Its clock stands still while the user has paused or the
 *     device is offline.
 *  2. After that, [PlaybackStallDetector] judges a stall: the position standing still while the user wants
 *     playback. It is a failure the planner handles like a source that died, resuming a little before where it stopped.
 *
 * Both read the player's latest snapshot once a second. They apply only while the setting to try the next source
 * is on, and the planner still decides whether a replacement may be tried (offline, the limit, the failed set).
 * Desktop only: other platforms keep the flat start timeout.
 */
internal suspend fun PlayerScreenRuntime.monitorSourceHealth() {
    var startup = SourceStartMonitor.Progress()
    var stall: PlaybackStallDetector.State? = null
    val baselineMs = activeInitialPositionMs.coerceAtLeast(0L)
    var startedAtMs = sourceHealthNowMs()
    var lastTickMs = startedAtMs
    // The start was given up on but moving on did not apply (a source picked by hand): the source stays, and a
    // stall is watched for once it does start playing.
    var startGivenUp = false

    fun restartClocks(nowMs: Long) {
        startup = SourceStartMonitor.Progress()
        stall = null
        startGivenUp = false
        startedAtMs = nowMs
    }

    while (true) {
        delay(SourceStartMonitor.POLL_INTERVAL_MS)
        if (sourceFailoverCancelled) return
        val nowMs = sourceHealthNowMs()
        val deltaMs = nowMs - lastTickMs
        lastTickMs = nowMs
        val settings = playerSettingsUiState
        val offline = isDeviceOffline()
        // Nothing is judged while the error screen shows, the setting is off, or a replacement is being looked up.
        if (errorMessage != null || !settings.streamAutoPlayFallbackActive || sourceFallbackJob?.isActive == true) {
            restartClocks(nowMs)
            continue
        }
        val snapshot = playbackSnapshot
        val currentStall = stall
        if (currentStall == null && startGivenUp) {
            if (snapshot.isPlaying && snapshot.positionMs > baselineMs) {
                stall = PlaybackStallDetector.initial(nowMs, snapshot.positionMs, snapshot.bufferedPositionMs)
                startGivenUp = false
            }
        } else if (currentStall == null) {
            // The clock of the start stands still while the user has paused or the device is offline.
            if (!shouldPlay || offline) startedAtMs += deltaMs
            val limits = SourceStartMonitor.Limits.fromSettingSeconds(settings.streamAutoPlayFallbackStartupTimeoutSeconds)
            val step = SourceStartMonitor.next(
                progress = startup,
                reading = SourceStartMonitor.Reading(
                    elapsedMs = nowMs - startedAtMs,
                    playing = snapshot.isPlaying,
                    positionMs = snapshot.positionMs,
                    bufferedMs = snapshot.bufferedPositionMs,
                    durationMs = snapshot.durationMs,
                    resumeFromMs = baselineMs,
                ),
                limits = limits,
            )
            when (step) {
                is SourceStartMonitor.Step.Pending -> startup = step.progress
                SourceStartMonitor.Step.Playing ->
                    stall = PlaybackStallDetector.initial(nowMs, snapshot.positionMs, snapshot.bufferedPositionMs)
                is SourceStartMonitor.Step.GiveUp -> {
                    val elapsedSeconds = ((nowMs - startedAtMs) / 1_000L).toInt().coerceAtLeast(1)
                    log.i { "Source did not start: ${step.cause.name} after ${nowMs - startedAtMs} ms" }
                    val handled = tryFallbackToNextSourceAfterError(
                        message = getString(Res.string.player_error_source_startup_timeout, elapsedSeconds),
                        failureOverride = SourceFailure.FailedToStart,
                    )
                    if (handled) return
                    // Not taken care of: a source picked by hand stays on the screen as it always did. When only
                    // the device went offline in the meantime, wait and look again instead.
                    if (isDeviceOffline()) restartClocks(nowMs) else startGivenUp = true
                }
            }
        } else {
            val step = PlaybackStallDetector.step(
                state = currentStall,
                sample = PlaybackStallDetector.Sample(
                    nowMs = nowMs,
                    positionMs = snapshot.positionMs,
                    bufferedPositionMs = snapshot.bufferedPositionMs,
                    durationMs = snapshot.durationMs,
                    isLoading = snapshot.isLoading,
                    isPlaying = snapshot.isPlaying,
                    wantsToPlay = shouldPlay,
                    isEnded = snapshot.isEnded,
                    isUserSeeking = isScrubbingTimeline || scrubbingPositionMs != null,
                    isOffline = offline,
                ),
            )
            when (step) {
                is PlaybackStallDetector.Step.Continue -> stall = step.state
                is PlaybackStallDetector.Step.Stalled -> {
                    log.i { "Source stopped responding: position still for ${step.stillForMs} ms" }
                    val handled = tryFallbackToNextSourceAfterError(
                        message = getString(Res.string.player_error_source_stalled),
                        failureOverride = SourceFailure.StalledDuringPlayback,
                    )
                    if (handled) return
                    // Moving on does not apply right now (offline, the limit, an engine switch): look again later.
                    stall = PlaybackStallDetector.initial(nowMs, snapshot.positionMs, snapshot.bufferedPositionMs)
                }
            }
        }
    }
}

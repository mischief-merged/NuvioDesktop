package com.nuvio.app.features.player

import com.nuvio.app.core.network.NetworkCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/** How the planner treats a source that stopped responding after playback had started. */
class SourceFailoverStallTest {

    private fun decide(
        settingEnabled: Boolean = true,
        manualMode: Boolean = false,
        chosenByApp: Boolean = true,
        userCancelled: Boolean = false,
        network: NetworkCondition = NetworkCondition.Online,
        attempts: Int = 0,
        maxAttempts: Int = 3,
        failed: Set<String> = setOf("a"),
    ) = decideSourceFailover(
        failure = SourceFailure.StalledDuringPlayback,
        settingEnabled = settingEnabled,
        manualMode = manualMode,
        failedSourceChosenByApp = chosenByApp,
        userCancelled = userCancelled,
        network = network,
        attempts = attempts,
        maxAttempts = maxAttempts,
        ranked = listOf("a", "b", "c"),
        failedKeys = failed,
        keyOf = { it },
    )

    @Test
    fun aStallMovesOnLikeASourceThatDied() {
        assertEquals(SourceFailoverDecision.TryNext("b"), decide())
    }

    @Test
    fun aStallAppliesToAHandPickedSourceInManualMode() {
        assertEquals(SourceFailoverDecision.TryNext("b"), decide(manualMode = true, chosenByApp = false))
    }

    @Test
    fun aStallObeysTheSettingTheLimitTheFailedSetOfflineAndCancellation() {
        assertEquals(SourceFailoverDecision.Stop(SourceFailoverStop.Disabled), decide(settingEnabled = false))
        assertEquals(SourceFailoverDecision.Stop(SourceFailoverStop.LimitReached), decide(attempts = 3))
        assertEquals(SourceFailoverDecision.Stop(SourceFailoverStop.Exhausted), decide(failed = setOf("a", "b", "c")))
        assertEquals(SourceFailoverDecision.Stop(SourceFailoverStop.Offline), decide(network = NetworkCondition.NoInternet))
        assertEquals(SourceFailoverDecision.Stop(SourceFailoverStop.UserCancelled), decide(userCancelled = true))
    }

    @Test
    fun aStallResumesALittleBeforeWhereItStopped() {
        assertEquals(58_000L, sourceFailoverResumePositionMs(positionMs = 60_000L, durationMs = 3_600_000L))
    }
}

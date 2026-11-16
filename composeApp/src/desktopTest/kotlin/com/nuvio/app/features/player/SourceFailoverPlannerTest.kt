package com.nuvio.app.features.player

import com.nuvio.app.core.network.NetworkCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceFailoverPlannerTest {

    private val ranked = listOf("a", "b", "c", "d")

    private fun decide(
        failure: SourceFailure = SourceFailure.FailedToStart,
        settingEnabled: Boolean = true,
        manualMode: Boolean = false,
        autoSelected: Boolean = true,
        userCancelled: Boolean = false,
        network: NetworkCondition = NetworkCondition.Online,
        attempts: Int = 0,
        maxAttempts: Int = 3,
        list: List<String> = ranked,
        failed: Set<String> = setOf("a"),
    ) = decideSourceFailover(
        failure = failure,
        settingEnabled = settingEnabled,
        manualMode = manualMode,
        failedSourceChosenByApp = autoSelected,
        userCancelled = userCancelled,
        network = network,
        attempts = attempts,
        maxAttempts = maxAttempts,
        ranked = list,
        failedKeys = failed,
        keyOf = { it },
    )

    private fun stop(reason: SourceFailoverStop) = SourceFailoverDecision.Stop(reason)

    @Test
    fun picksTheFirstRankedSourceThatHasNotFailed() {
        assertEquals(SourceFailoverDecision.TryNext("b"), decide())
    }

    @Test
    fun keepsTheRankedOrderAndSkipsEveryFailedSource() {
        assertEquals(SourceFailoverDecision.TryNext("d"), decide(failed = setOf("a", "b", "c")))
        assertEquals(SourceFailoverDecision.TryNext("a"), decide(failed = setOf("b")))
    }

    @Test
    fun exhaustedWhenEverySourceFailed() {
        assertEquals(stop(SourceFailoverStop.Exhausted), decide(failed = ranked.toSet()))
    }

    @Test
    fun exhaustedWhenTheListIsEmpty() {
        assertEquals(stop(SourceFailoverStop.Exhausted), decide(list = emptyList(), failed = emptySet()))
    }

    @Test
    fun aDuplicateOfAFailedSourceIsNotTriedAgain() {
        assertEquals(
            SourceFailoverDecision.TryNext("b"),
            decide(list = listOf("a", "a", "b", "a")),
        )
        assertEquals(
            stop(SourceFailoverStop.Exhausted),
            decide(list = listOf("a", "a", "a")),
        )
    }

    @Test
    fun aSourceWithoutIdentityIsStillBoundedByTheLimit() {
        val result = decideSourceFailover(
            failure = SourceFailure.DiedDuringPlayback,
            settingEnabled = true,
            manualMode = false,
            failedSourceChosenByApp = true,
            userCancelled = false,
            network = NetworkCondition.Online,
            attempts = 3,
            maxAttempts = 3,
            ranked = listOf<String?>(null),
            failedKeys = emptySet(),
            keyOf = { it },
        )
        assertEquals(stop(SourceFailoverStop.LimitReached), result)
    }

    @Test
    fun stopsWhenTheLimitIsReached() {
        assertEquals(SourceFailoverDecision.TryNext("b"), decide(attempts = 2, maxAttempts = 3))
        assertEquals(stop(SourceFailoverStop.LimitReached), decide(attempts = 3, maxAttempts = 3))
        assertEquals(stop(SourceFailoverStop.LimitReached), decide(attempts = 9, maxAttempts = 3))
    }

    @Test
    fun theLimitIsNeverBelowOneOrAboveTheSettingsRange() {
        assertEquals(SourceFailoverDecision.TryNext("b"), decide(attempts = 0, maxAttempts = 0))
        assertEquals(stop(SourceFailoverStop.LimitReached), decide(attempts = 1, maxAttempts = -4))
        assertEquals(
            stop(SourceFailoverStop.LimitReached),
            decide(attempts = SOURCE_FALLBACK_MAX_SOURCES_RANGE.last, maxAttempts = 500),
        )
    }

    @Test
    fun userCancelledWinsOverEverythingElse() {
        assertEquals(stop(SourceFailoverStop.UserCancelled), decide(userCancelled = true))
        assertEquals(
            stop(SourceFailoverStop.UserCancelled),
            decide(userCancelled = true, settingEnabled = false, network = NetworkCondition.NoInternet),
        )
    }

    @Test
    fun settingOffStopsBothKindsOfFailure() {
        assertEquals(stop(SourceFailoverStop.Disabled), decide(settingEnabled = false))
        assertEquals(
            stop(SourceFailoverStop.Disabled),
            decide(settingEnabled = false, failure = SourceFailure.DiedDuringPlayback, manualMode = true),
        )
    }

    @Test
    fun offlineIsNotASourceFailure() {
        assertEquals(stop(SourceFailoverStop.Offline), decide(network = NetworkCondition.NoInternet))
        assertEquals(stop(SourceFailoverStop.Offline), decide(network = NetworkCondition.ServersUnreachable))
        assertEquals(
            stop(SourceFailoverStop.Offline),
            decide(network = NetworkCondition.NoInternet, failure = SourceFailure.DiedDuringPlayback),
        )
    }

    @Test
    fun anUnknownOrCheckingNetworkDoesNotBlockMovingOn() {
        assertEquals(SourceFailoverDecision.TryNext("b"), decide(network = NetworkCondition.Unknown))
        assertEquals(SourceFailoverDecision.TryNext("b"), decide(network = NetworkCondition.Checking))
    }

    @Test
    fun offlineDoesNotUseUpAnythingBecauseNothingIsTried() {
        // The decision only reads the counters; the caller keeps them as they were.
        assertEquals(stop(SourceFailoverStop.Offline), decide(network = NetworkCondition.NoInternet, attempts = 0))
    }

    @Test
    fun manualModeDoesNotReplaceASourceThatNeverStarted() {
        assertEquals(
            stop(SourceFailoverStop.NotApplicable),
            decide(manualMode = true, autoSelected = false),
        )
        assertEquals(SourceFailoverDecision.TryNext("b"), decide(manualMode = true, autoSelected = false, failure = SourceFailure.DiedDuringPlayback))
    }

    @Test
    fun manualModeMovesOnFromASourceTheAppChoseAfterAnEarlierFailure() {
        assertEquals(SourceFailoverDecision.TryNext("b"), decide(manualMode = true, autoSelected = true))
    }

    @Test
    fun midPlayFailureAppliesToASourcePickedByHandInAnyMode() {
        assertEquals(
            SourceFailoverDecision.TryNext("b"),
            decide(failure = SourceFailure.DiedDuringPlayback, manualMode = false, autoSelected = false),
        )
    }

    @Test
    fun autoModeDoesNotReplaceAStartingSourceTheUserPicked() {
        assertEquals(stop(SourceFailoverStop.NotApplicable), decide(manualMode = false, autoSelected = false))
    }

    @Test
    fun resumeIsAFewSecondsBeforeThePositionWhereTheSourceDied() {
        assertEquals(58_000L, sourceFailoverResumePositionMs(positionMs = 60_000L, durationMs = 3_600_000L))
    }

    @Test
    fun resumeAtTheStartStaysAtTheStart() {
        assertEquals(0L, sourceFailoverResumePositionMs(positionMs = 0L, durationMs = 3_600_000L))
        assertEquals(0L, sourceFailoverResumePositionMs(positionMs = 1_500L, durationMs = 3_600_000L))
        assertEquals(0L, sourceFailoverResumePositionMs(positionMs = SOURCE_FAILOVER_RESUME_BACKOFF_MS, durationMs = 3_600_000L))
        assertEquals(0L, sourceFailoverResumePositionMs(positionMs = -500L, durationMs = 0L))
    }

    @Test
    fun resumeNearTheEndStaysInsideTheVideo() {
        assertEquals(3_597_500L, sourceFailoverResumePositionMs(positionMs = 3_599_500L, durationMs = 3_600_000L))
        assertEquals(3_598_000L, sourceFailoverResumePositionMs(positionMs = 3_600_000L, durationMs = 3_600_000L))
        // Died past the reported length: pulled back to the end margin.
        assertEquals(3_599_000L, sourceFailoverResumePositionMs(positionMs = 4_000_000L, durationMs = 3_600_000L))
    }

    @Test
    fun resumeWithAnUnknownLengthOnlyStepsBack() {
        assertEquals(98_000L, sourceFailoverResumePositionMs(positionMs = 100_000L, durationMs = 0L))
        assertEquals(98_000L, sourceFailoverResumePositionMs(positionMs = 100_000L, durationMs = -1L))
    }

    @Test
    fun goingOfflineWhileALinkIsResolvedStopsBeforeLoading() {
        assertEquals(SourceFailoverStop.Offline, sourceFailoverStopBeforeLoading(false, NetworkCondition.NoInternet))
        assertEquals(SourceFailoverStop.Offline, sourceFailoverStopBeforeLoading(false, NetworkCondition.ServersUnreachable))
    }

    @Test
    fun leavingWhileALinkIsResolvedStopsBeforeLoadingEvenWhenOffline() {
        assertEquals(SourceFailoverStop.UserCancelled, sourceFailoverStopBeforeLoading(true, NetworkCondition.NoInternet))
        assertEquals(SourceFailoverStop.UserCancelled, sourceFailoverStopBeforeLoading(true, NetworkCondition.Online))
    }

    @Test
    fun anOnlineOrUnknownNetworkLetsTheReplacementLoad() {
        assertEquals(null, sourceFailoverStopBeforeLoading(false, NetworkCondition.Online))
        assertEquals(null, sourceFailoverStopBeforeLoading(false, NetworkCondition.Unknown))
        assertEquals(null, sourceFailoverStopBeforeLoading(false, NetworkCondition.Checking))
    }

    private val minute = 60_000L

    @Test
    fun aClipFarShorterThanTheRuntimeIsAPlaceholder() {
        assertTrue(looksLikePlaceholder(durationMs = 30_000L, expectedRuntimeMinutes = 45))
        assertTrue(looksLikePlaceholder(durationMs = 2 * minute, expectedRuntimeMinutes = 120))
    }

    @Test
    fun theDurationBoundariesAreStrict() {
        // Exactly 10 minutes is not under 10 minutes.
        assertFalse(looksLikePlaceholder(PLACEHOLDER_MAX_DURATION_MS, expectedRuntimeMinutes = 120))
        assertTrue(looksLikePlaceholder(PLACEHOLDER_MAX_DURATION_MS - 1, expectedRuntimeMinutes = 120))
        // 20% of 10 minutes is exactly 2 minutes.
        assertFalse(looksLikePlaceholder(2 * minute, expectedRuntimeMinutes = 10))
        assertTrue(looksLikePlaceholder(2 * minute - 1, expectedRuntimeMinutes = 10))
    }

    @Test
    fun theClipThatWasThreeHundredAndNineTenthsOfASecondLongAgainstAFilmOfTwoHoursIsAPlaceholder() {
        // A provider's clip of 300.9 seconds was 0.9 seconds past the old limit of five minutes.
        assertTrue(looksLikePlaceholder(durationMs = 300_900L, expectedRuntimeMinutes = 120))
        assertTrue(looksLikePlaceholder(durationMs = 300_900L, expectedRuntimeMinutes = 118))
    }

    @Test
    fun aShortEpisodeOfAShortShowIsNotAPlaceholder() {
        assertFalse(looksLikePlaceholder(durationMs = 4 * minute, expectedRuntimeMinutes = 5))
        assertFalse(looksLikePlaceholder(durationMs = 4 * minute, expectedRuntimeMinutes = 8))
        // Nine minutes of an eleven minute cartoon: under ten minutes, but not under 20% of its runtime.
        assertFalse(looksLikePlaceholder(durationMs = 9 * minute, expectedRuntimeMinutes = 11))
        assertFalse(looksLikePlaceholder(durationMs = 3 * minute, expectedRuntimeMinutes = 12))
    }

    @Test
    fun aFileOfFullLengthIsNeverAPlaceholderEvenIfItIsOnePicture() {
        assertFalse(looksLikePlaceholder(durationMs = 45 * minute, expectedRuntimeMinutes = 45))
        // Six minutes of a film that runs 100: far shorter than the film, so a placeholder under the rule
        // of 10 minutes and 20%. It was not one under the old five minutes.
        assertTrue(looksLikePlaceholder(durationMs = 6 * minute, expectedRuntimeMinutes = 100))
        // Six minutes of something that runs 25 is 24%: not far enough below its running time.
        assertFalse(looksLikePlaceholder(durationMs = 6 * minute, expectedRuntimeMinutes = 25))
    }

    @Test
    fun nothingIsClaimedWhenTheRuntimeOrTheDurationIsUnknown() {
        assertFalse(looksLikePlaceholder(durationMs = 30_000L, expectedRuntimeMinutes = null))
        assertFalse(looksLikePlaceholder(durationMs = 30_000L, expectedRuntimeMinutes = 0))
        assertFalse(looksLikePlaceholder(durationMs = 0L, expectedRuntimeMinutes = 45))
        assertFalse(looksLikePlaceholder(durationMs = -1L, expectedRuntimeMinutes = 45))
    }

    @Test
    fun aPlaceholderMovesOnInEveryModeIncludingASourcePickedByHand() {
        assertEquals(
            SourceFailoverDecision.TryNext("b"),
            decide(failure = SourceFailure.PlaceholderClip, manualMode = true, autoSelected = false),
        )
        assertEquals(
            SourceFailoverDecision.TryNext("b"),
            decide(failure = SourceFailure.PlaceholderClip, manualMode = false, autoSelected = false),
        )
    }

    @Test
    fun aPlaceholderObeysTheSettingTheLimitAndTheFailedSet() {
        assertEquals(stop(SourceFailoverStop.Disabled), decide(failure = SourceFailure.PlaceholderClip, settingEnabled = false))
        assertEquals(
            stop(SourceFailoverStop.LimitReached),
            decide(failure = SourceFailure.PlaceholderClip, attempts = 3, maxAttempts = 3),
        )
        assertEquals(
            SourceFailoverDecision.TryNext("c"),
            decide(failure = SourceFailure.PlaceholderClip, failed = setOf("a", "b")),
        )
        assertEquals(
            stop(SourceFailoverStop.Exhausted),
            decide(failure = SourceFailure.PlaceholderClip, failed = ranked.toSet()),
        )
        assertEquals(
            stop(SourceFailoverStop.Offline),
            decide(failure = SourceFailure.PlaceholderClip, network = NetworkCondition.NoInternet),
        )
        assertEquals(
            stop(SourceFailoverStop.UserCancelled),
            decide(failure = SourceFailure.PlaceholderClip, userCancelled = true),
        )
    }

    /** Feeds snapshots (timeMs, positionMs, durationMs, loading) to the check from a first frame at time 0, position 0. */
    private fun run(
        vararg snapshots: List<Any>,
        network: NetworkCondition = NetworkCondition.Online,
        startPositionMs: Long = 0L,
    ): PlaceholderCheckStep {
        var state = PlaceholderCheckState(firstFrameAtMs = 0L, firstFramePositionMs = startPositionMs)
        var step: PlaceholderCheckStep = PlaceholderCheckStep.Continue(state)
        for (snapshot in snapshots) {
            step = stepPlaceholderCheck(
                state = state,
                nowMs = snapshot[0] as Long,
                positionMs = snapshot[1] as Long,
                durationMs = snapshot[2] as Long,
                isLoading = snapshot[3] as Boolean,
                network = network,
            )
            when (step) {
                is PlaceholderCheckStep.Continue -> state = step.state
                else -> return step
            }
        }
        return step
    }

    private fun snap(timeMs: Long, positionMs: Long, durationMs: Long, loading: Boolean = false) =
        listOf<Any>(timeMs, positionMs, durationMs, loading)

    @Test
    fun aStableShortDurationIsJudged() {
        val step = run(
            snap(0, 0, 30_000), snap(1_500, 1_500, 30_000), snap(3_000, 3_000, 30_000),
        )
        assertEquals(PlaceholderCheckStep.Stable(30_000L), step)
    }

    @Test
    fun aStableFullDurationIsJudgedToo() {
        val step = run(snap(0, 0, 2_700_000), snap(1_500, 1_500, 2_700_000), snap(3_000, 3_000, 2_700_000))
        assertEquals(PlaceholderCheckStep.Stable(2_700_000L), step)
    }

    @Test
    fun threeSnapshotsAreNotEnoughWithoutEnoughTime() {
        val step = run(snap(0, 0, 30_000), snap(500, 500, 30_000), snap(1_000, 1_000, 30_000))
        assertTrue(step is PlaceholderCheckStep.Continue)
    }

    @Test
    fun enoughTimeIsNotEnoughWithTooFewSnapshots() {
        val step = run(snap(0, 0, 30_000), snap(4_000, 4_000, 30_000))
        assertTrue(step is PlaceholderCheckStep.Continue)
    }

    @Test
    fun aGrowingDurationIsNeverFinalAndClaimsNothingWhenTheWindowEnds() {
        // Grows by 5 seconds at each snapshot while buffering a long stream.
        val growing = (0..5).map { i -> snap(i * 1_000L, i * 1_000L, 20_000L + i * 5_000L) }
        assertTrue(run(*growing.toTypedArray()) is PlaceholderCheckStep.Continue)
        val late = growing + listOf(snap(PLACEHOLDER_CHECK_WINDOW_MS + 1, 9_000, 60_000))
        assertEquals(PlaceholderCheckStep.Finished, run(*late.toTypedArray()))
    }

    @Test
    fun aSlowDriftBeyondTheToleranceStartsTheRunAgain() {
        // Each report is within tolerance of the previous one, but not of the first of the run.
        val step = run(
            snap(0, 0, 30_000), snap(1_500, 1_500, 30_800), snap(3_000, 3_000, 31_600), snap(3_500, 3_500, 31_600),
        )
        assertTrue(step is PlaceholderCheckStep.Continue)
    }

    @Test
    fun aFlappingDurationResetsTheRun() {
        val step = run(
            snap(0, 0, 30_000), snap(1_500, 1_500, 0), snap(3_000, 3_000, 30_000), snap(4_500, 4_500, 30_000),
        )
        assertTrue(step is PlaceholderCheckStep.Continue)
        val stable = run(
            snap(0, 0, 30_000), snap(1_500, 1_500, 0), snap(3_000, 3_000, 30_000), snap(4_500, 4_500, 30_000), snap(6_000, 6_000, 30_000),
        )
        assertEquals(PlaceholderCheckStep.Stable(30_000L), stable)
    }

    @Test
    fun bufferingNeverCountsAndResetsTheRun() {
        val step = run(
            snap(0, 0, 30_000), snap(1_500, 1_500, 30_000), snap(3_000, 3_000, 30_000, loading = true), snap(4_500, 3_000, 30_000),
        )
        assertTrue(step is PlaceholderCheckStep.Continue)
    }

    @Test
    fun pausingDoesNotExtendTheWindow() {
        // Paused at 1 second with an unknown duration; the length shows up a minute later.
        val step = run(snap(0, 0, 0), snap(1_000, 1_000, 0), snap(60_000, 1_000, 30_000))
        assertEquals(PlaceholderCheckStep.Finished, step)
    }

    @Test
    fun aLateDurationAfterTheWindowClaimsNothing() {
        val step = run(snap(0, 0, 0), snap(PLACEHOLDER_CHECK_WINDOW_MS + 1, 20_000, 30_000))
        assertEquals(PlaceholderCheckStep.Finished, step)
    }

    @Test
    fun seekingBackEndsTheCheck() {
        val step = run(snap(0, 10_000, 0), snap(2_000, 5_000, 0), startPositionMs = 10_000L)
        assertEquals(PlaceholderCheckStep.Finished, step)
    }

    @Test
    fun seekingAheadEndsTheCheck() {
        assertEquals(PlaceholderCheckStep.Finished, run(snap(0, 0, 0), snap(1_000, 600_000, 0)))
    }

    @Test
    fun watchingPastTheLimitEndsTheCheck() {
        val step = run(snap(0, 0, 0), snap(PLACEHOLDER_WATCHED_LIMIT_MS + 1, PLACEHOLDER_WATCHED_LIMIT_MS + 1, 0))
        assertEquals(PlaceholderCheckStep.Finished, step)
    }

    @Test
    fun resumingFarIntoTheVideoDoesNotCountAsWatching() {
        val step = run(
            snap(0, 3_600_000, 6_000_000), snap(1_500, 3_601_500, 6_000_000), snap(3_000, 3_603_000, 6_000_000),
            startPositionMs = 3_600_000L,
        )
        assertEquals(PlaceholderCheckStep.Stable(6_000_000L), step)
    }

    @Test
    fun offlineSnapshotsAreNotCountedButTimeStillRuns() {
        val offline = run(snap(0, 0, 30_000), snap(1_500, 1_500, 30_000), snap(3_000, 3_000, 30_000), network = NetworkCondition.NoInternet)
        assertTrue(offline is PlaceholderCheckStep.Continue)
        val expired = run(snap(PLACEHOLDER_CHECK_WINDOW_MS + 1, 1_000, 30_000), network = NetworkCondition.NoInternet)
        assertEquals(PlaceholderCheckStep.Finished, expired)
    }
}

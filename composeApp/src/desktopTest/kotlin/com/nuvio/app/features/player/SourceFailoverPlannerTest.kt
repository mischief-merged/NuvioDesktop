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
        // Exactly 5 minutes is not under 5 minutes.
        assertFalse(looksLikePlaceholder(PLACEHOLDER_MAX_DURATION_MS, expectedRuntimeMinutes = 120))
        assertTrue(looksLikePlaceholder(PLACEHOLDER_MAX_DURATION_MS - 1, expectedRuntimeMinutes = 120))
        // 30% of 10 minutes is exactly 3 minutes.
        assertFalse(looksLikePlaceholder(3 * minute, expectedRuntimeMinutes = 10))
        assertTrue(looksLikePlaceholder(3 * minute - 1, expectedRuntimeMinutes = 10))
    }

    @Test
    fun aShortEpisodeOfAShortShowIsNotAPlaceholder() {
        assertFalse(looksLikePlaceholder(durationMs = 4 * minute, expectedRuntimeMinutes = 5))
        assertFalse(looksLikePlaceholder(durationMs = 4 * minute, expectedRuntimeMinutes = 8))
    }

    @Test
    fun aFileOfFullLengthIsNeverAPlaceholderEvenIfItIsOnePicture() {
        assertFalse(looksLikePlaceholder(durationMs = 45 * minute, expectedRuntimeMinutes = 45))
        assertFalse(looksLikePlaceholder(durationMs = 6 * minute, expectedRuntimeMinutes = 100))
    }

    @Test
    fun nothingIsClaimedWhenTheRuntimeOrTheDurationIsUnknown() {
        assertFalse(looksLikePlaceholder(durationMs = 30_000L, expectedRuntimeMinutes = null))
        assertFalse(looksLikePlaceholder(durationMs = 30_000L, expectedRuntimeMinutes = 0))
        assertFalse(looksLikePlaceholder(durationMs = 0L, expectedRuntimeMinutes = 45))
        assertFalse(looksLikePlaceholder(durationMs = -1L, expectedRuntimeMinutes = 45))
    }

    @Test
    fun theCheckRunsOncePerSourceAtTheStartAndNotOffline() {
        assertTrue(placeholderCheckDue(false, 0L, NetworkCondition.Online))
        assertTrue(placeholderCheckDue(false, PLACEHOLDER_CHECK_WINDOW_MS, NetworkCondition.Unknown))
        assertFalse(placeholderCheckDue(true, 0L, NetworkCondition.Online))
        assertFalse(placeholderCheckDue(false, PLACEHOLDER_CHECK_WINDOW_MS + 1, NetworkCondition.Online))
        assertFalse(placeholderCheckDue(false, 0L, NetworkCondition.NoInternet))
        assertFalse(placeholderCheckDue(false, 0L, NetworkCondition.ServersUnreachable))
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
}

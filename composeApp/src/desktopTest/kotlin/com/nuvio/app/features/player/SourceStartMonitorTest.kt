package com.nuvio.app.features.player

import com.nuvio.app.features.player.SourceStartMonitor.GiveUpCause
import com.nuvio.app.features.player.SourceStartMonitor.Limits
import com.nuvio.app.features.player.SourceStartMonitor.Progress
import com.nuvio.app.features.player.SourceStartMonitor.Reading
import com.nuvio.app.features.player.SourceStartMonitor.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourceStartMonitorTest {

    private fun reading(
        at: Long,
        playing: Boolean = false,
        pos: Long = 0L,
        buffered: Long = 0L,
        duration: Long = 0L,
        resume: Long = 0L,
    ) = Reading(at, playing, pos, buffered, duration, resume)

    /** Reads once a second and returns the first non-pending step, or the last progress. */
    private fun runUntil(
        limits: Limits = Limits(),
        untilMs: Long,
        make: (Long) -> Reading,
    ): Pair<Progress, Step?> {
        var progress = Progress()
        var t = 0L
        while (t < untilMs) {
            t += SourceStartMonitor.POLL_INTERVAL_MS
            when (val step = SourceStartMonitor.next(progress, make(t), limits)) {
                is Step.Pending -> progress = step.progress
                else -> return progress to step
            }
        }
        return progress to null
    }

    @Test
    fun aBufferThatKeepsFillingIsNotGivenUpOnEarly() {
        val (_, step) = runUntil(untilMs = 50_000L) { t -> reading(t, buffered = t * 3) }
        assertEquals(null, step)
    }

    @Test
    fun aPositionThatAdvancesAloneCountsAsProgress() {
        val (_, step) = runUntil(untilMs = 50_000L) { t -> reading(t, pos = t / 2) }
        assertEquals(null, step)
    }

    @Test
    fun aSilentSourceIsGivenUpAtTheNoSignalLimit() {
        val (_, early) = runUntil(untilMs = 19_000L) { t -> reading(t) }
        assertEquals(null, early)
        val (_, step) = runUntil(untilMs = 25_000L) { t -> reading(t) }
        assertEquals(Step.GiveUp(GiveUpCause.NothingMoved), step)
    }

    @Test
    fun aKnownLengthAloneBuysTheLongerLimitButNotForever() {
        val (_, at30) = runUntil(untilMs = 30_000L) { t -> reading(t, duration = 7_200_000L) }
        assertEquals(null, at30)
        val (_, step) = runUntil(untilMs = 40_000L) { t -> reading(t, duration = 7_200_000L) }
        assertEquals(Step.GiveUp(GiveUpCause.NothingMoved), step)
    }

    @Test
    fun aSourceThatMovedAndThenStoodStillIsGivenUpAfterTheIdleLimit() {
        val (_, step) = runUntil(untilMs = 60_000L) { t -> reading(t, buffered = minOf(t, 2_000L) * 2) }
        assertEquals(Step.GiveUp(GiveUpCause.WentIdle), step)
        // The last advance was at 2 s, so the limit ran out at 14 s.
        var progress = Progress()
        for (t in 1_000L..13_000L step 1_000L) {
            progress = (SourceStartMonitor.next(progress, reading(t, buffered = minOf(t, 2_000L) * 2)) as Step.Pending).progress
        }
        assertIs<Step.GiveUp>(SourceStartMonitor.next(progress, reading(14_000L, buffered = 4_000L)))
    }

    @Test
    fun aCreepingBufferIsCutOffAtTheOverallCap() {
        val (_, step) = runUntil(untilMs = 120_000L) { t -> reading(t, buffered = t / 4) }
        assertEquals(Step.GiveUp(GiveUpCause.TooSlow), step)
    }

    @Test
    fun playingWithSomethingBehindItEndsTheStart() {
        assertEquals(Step.Playing, SourceStartMonitor.next(Progress(), reading(3_000L, playing = true, pos = 120L)))
    }

    @Test
    fun anEngineClaimingToPlayFromNowhereIsNotStarted() {
        val (_, step) = runUntil(untilMs = 25_000L) { t -> reading(t, playing = true) }
        assertEquals(Step.GiveUp(GiveUpCause.NothingMoved), step)
    }

    @Test
    fun aDeadSourceResumedAtAPositionIsGivenUpNotCalledStarted() {
        val resume = 22 * 60_000L
        val (_, step) = runUntil(untilMs = 25_000L) { t -> reading(t, playing = true, pos = resume, resume = resume) }
        assertEquals(Step.GiveUp(GiveUpCause.NothingMoved), step)
    }

    @Test
    fun aHealthySourceResumedAtAPositionStarts() {
        val resume = 22 * 60_000L
        val step = SourceStartMonitor.next(
            Progress(),
            reading(2_000L, playing = true, pos = resume + 300L, buffered = resume + 4_000L, resume = resume),
        )
        assertEquals(Step.Playing, step)
    }

    @Test
    fun aResumedSourceThatFilledItsBufferAndStoppedGoesIdleNotStarted() {
        val resume = 22 * 60_000L
        val (_, step) = runUntil(untilMs = 60_000L) { t -> reading(t, pos = resume, buffered = resume + 4_000L, resume = resume) }
        assertEquals(Step.GiveUp(GiveUpCause.WentIdle), step)
    }

    @Test
    fun aSlowStartThatBeginsPlayingInTimeIsKept() {
        val resume = 359_818L
        val (progress, step) = runUntil(untilMs = 24_000L) { t -> reading(t, duration = 7_094_176L, resume = resume) }
        assertEquals(null, step)
        assertEquals(Step.Playing, SourceStartMonitor.next(progress, reading(24_000L, playing = true, pos = resume + 100L, buffered = resume + 2_000L, duration = 7_094_176L, resume = resume)))
    }

    @Test
    fun theSettingDrivesTheNoSignalLimit() {
        val limits = Limits.fromSettingSeconds(15)
        val (_, early) = runUntil(limits, 14_000L) { t -> reading(t) }
        assertEquals(null, early)
        val (_, step) = runUntil(limits, 20_000L) { t -> reading(t) }
        assertEquals(Step.GiveUp(GiveUpCause.NothingMoved), step)
    }

    @Test
    fun theLongerLimitsNeverDropBelowTheirDefaultsAndStayPastTheSetting() {
        val short = Limits.fromSettingSeconds(5)
        assertEquals(SourceStartMonitor.DEFAULT_SIGN_OF_LIFE_MS, short.signOfLifeMs)
        assertEquals(SourceStartMonitor.DEFAULT_OVERALL_MS, short.overallMs)
        val long = Limits.fromSettingSeconds(45)
        assertTrue(long.signOfLifeMs >= long.noSignalMs)
        assertTrue(long.overallMs > long.signOfLifeMs)
    }

    @Test
    fun aKnownLengthDoesNotShortenTheLimitForTheSetting() {
        val (_, step) = runUntil(Limits.fromSettingSeconds(15), 30_000L) { t -> reading(t, duration = 7_200_000L) }
        assertEquals(null, step)
    }
}
